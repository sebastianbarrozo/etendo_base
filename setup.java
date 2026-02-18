import com.sun.net.httpserver.*;
import java.net.http.*;
import java.net.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/**
 * setup.java — Standalone GitHub auth UI before Gradle runs.
 *
 * Java 11+ single-file program (zero external dependencies).
 * Starts a mini HTTP server on :3850 with a browser UI for the
 * GitHub Device Flow, saves the token to gradle.properties,
 * then launches ./gradlew setup.web.
 *
 * Usage:  java setup.java [gradleTask]
 */
public class setup {

    static final String CLIENT_ID = "Ov23li1PuCseVXZZVH6O";
    static final String SCOPE = "read:packages";
    static final int PORT = 3850;
    static final Path PROPS_FILE = Path.of("gradle.properties");

    // Shared state between HTTP handlers and the polling thread
    static final AtomicReference<String> authStatus = new AtomicReference<>("pending");
    static final AtomicReference<String> errorMessage = new AtomicReference<>("");
    static volatile String userCode = "";
    static volatile String deviceCode = "";
    static volatile int pollInterval = 5;
    static volatile int expiresIn = 900;

    public static void main(String[] args) throws Exception {
        String task = args.length > 0 ? args[0] : "setup.web";

        // 1. Check if token already set
        String existing = getExistingToken();
        if (existing != null && !existing.isEmpty()) {
            System.out.println("githubToken already set in " + PROPS_FILE + ". Skipping auth.");
            launchGradle(task);
            return;
        }

        // 2. Start Device Flow
        System.out.println();
        System.out.println("githubToken is not set. Starting GitHub authentication UI...");
        System.out.println();

        if (!startDeviceFlow()) {
            System.exit(1);
        }

        // 3. Start HTTP server
        HttpServer server = HttpServer.create(new InetSocketAddress(PORT), 0);
        server.createContext("/", setup::handleIndex);
        server.createContext("/api/status", setup::handleStatus);
        server.setExecutor(Executors.newFixedThreadPool(4));
        server.start();
        System.out.println("Auth UI running at http://localhost:" + PORT);

        // 4. Open browser
        openBrowser("http://localhost:" + PORT);

        // 5. Background thread polls GitHub until token received
        CompletableFuture<String> tokenFuture = CompletableFuture.supplyAsync(() -> {
            long deadline = System.currentTimeMillis() + (expiresIn * 1000L);
            while (System.currentTimeMillis() < deadline) {
                try {
                    Thread.sleep(pollInterval * 1000L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return null;
                }
                try {
                    String result = pollForToken();
                    if (result != null) return result;
                } catch (Exception e) {
                    authStatus.set("error");
                    errorMessage.set(e.getMessage());
                    return null;
                }
            }
            authStatus.set("error");
            errorMessage.set("Authorization timed out. Please run again.");
            return null;
        });

        String token = tokenFuture.get();
        // Give browser time to pick up the success status
        Thread.sleep(2000);
        server.stop(0);

        if (token == null || token.isEmpty()) {
            System.err.println("Error: " + errorMessage.get());
            System.exit(1);
        }

        // 6. Save token
        saveToken(token);
        String masked = token.substring(0, 4) + "..." + token.substring(token.length() - 4);
        System.out.println("Token saved to " + PROPS_FILE + " (" + masked + ")");
        System.out.println();

        // 7. Launch Gradle
        launchGradle(task);
    }

    // ---- Device Flow ----

    static boolean startDeviceFlow() throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("https://github.com/login/device/code"))
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        "client_id=" + CLIENT_ID + "&scope=" + SCOPE))
                .build();

        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        String body = resp.body();

        userCode = jsonValue(body, "user_code");
        deviceCode = jsonValue(body, "device_code");
        String intervalStr = jsonValue(body, "interval");
        String expiresStr = jsonValue(body, "expires_in");

        if (userCode.isEmpty() || deviceCode.isEmpty()) {
            System.err.println("Error: Failed to start GitHub Device Flow.");
            System.err.println("Response: " + body);
            return false;
        }

        if (!intervalStr.isEmpty()) pollInterval = Integer.parseInt(intervalStr);
        if (!expiresStr.isEmpty()) expiresIn = Integer.parseInt(expiresStr);

        System.out.println("Your code: " + userCode);
        return true;
    }

    static String pollForToken() throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("https://github.com/login/oauth/access_token"))
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        "client_id=" + CLIENT_ID
                                + "&device_code=" + deviceCode
                                + "&grant_type=urn:ietf:params:oauth:grant-type:device_code"))
                .build();

        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        String body = resp.body();

        String accessToken = jsonValue(body, "access_token");
        if (!accessToken.isEmpty()) {
            authStatus.set("success");
            return accessToken;
        }

        String error = jsonValue(body, "error");
        switch (error) {
            case "authorization_pending":
                break;
            case "slow_down":
                pollInterval += 5;
                break;
            case "expired_token":
                authStatus.set("error");
                errorMessage.set("Authorization expired. Please run again.");
                throw new RuntimeException(errorMessage.get());
            case "access_denied":
                authStatus.set("error");
                errorMessage.set("Authorization denied by user.");
                throw new RuntimeException(errorMessage.get());
            default:
                if (!error.isEmpty()) {
                    authStatus.set("error");
                    errorMessage.set(error);
                    throw new RuntimeException(error);
                }
                break;
        }
        return null;
    }

    // ---- HTTP Handlers ----

    static void handleIndex(HttpExchange ex) throws IOException {
        String html = INDEX_HTML.replace("{{USER_CODE}}", escapeHtml(userCode));
        byte[] bytes = html.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
        ex.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    static void handleStatus(HttpExchange ex) throws IOException {
        String status = authStatus.get();
        String json;
        if ("error".equals(status)) {
            json = "{\"status\":\"error\",\"message\":\"" + escapeJson(errorMessage.get()) + "\"}";
        } else {
            json = "{\"status\":\"" + status + "\"}";
        }
        byte[] bytes = json.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    // ---- Token persistence ----

    static String getExistingToken() {
        try {
            if (!Files.exists(PROPS_FILE)) return null;
            for (String line : Files.readAllLines(PROPS_FILE)) {
                if (line.startsWith("githubToken=")) {
                    String val = line.substring("githubToken=".length()).trim();
                    return val.isEmpty() ? null : val;
                }
            }
        } catch (IOException e) {
            // ignore
        }
        return null;
    }

    static void saveToken(String token) throws IOException {
        if (!Files.exists(PROPS_FILE)) {
            Files.writeString(PROPS_FILE, "githubToken=" + token + "\n");
            return;
        }
        List<String> lines = Files.readAllLines(PROPS_FILE);
        boolean found = false;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).startsWith("githubToken=")) {
                lines.set(i, "githubToken=" + token);
                found = true;
                break;
            }
        }
        if (!found) {
            lines.add("githubToken=" + token);
        }
        Files.write(PROPS_FILE, lines);
    }

    // ---- Gradle launcher ----

    static void launchGradle(String task) throws Exception {
        String gradlew = System.getProperty("os.name").toLowerCase().contains("win")
                ? "gradlew.bat" : "./gradlew";
        ProcessBuilder pb = new ProcessBuilder(gradlew, task);
        pb.inheritIO();
        Process proc = pb.start();
        System.exit(proc.waitFor());
    }

    // ---- Browser ----

    static void openBrowser(String url) {
        String os = System.getProperty("os.name").toLowerCase();
        try {
            if (os.contains("mac")) Runtime.getRuntime().exec(new String[]{"open", url});
            else if (os.contains("win")) Runtime.getRuntime().exec(new String[]{"rundll32", "url.dll,FileProtocolHandler", url});
            else Runtime.getRuntime().exec(new String[]{"xdg-open", url});
        } catch (Exception ignored) {}
    }

    // ---- Minimal JSON helpers (no dependencies) ----

    static String jsonValue(String json, String key) {
        // Handles both "key":"stringVal" and "key":numberVal
        String strPattern = "\"" + key + "\":\"";
        int idx = json.indexOf(strPattern);
        if (idx >= 0) {
            int start = idx + strPattern.length();
            int end = json.indexOf('"', start);
            return end > start ? json.substring(start, end) : "";
        }
        String numPattern = "\"" + key + "\":";
        idx = json.indexOf(numPattern);
        if (idx >= 0) {
            int start = idx + numPattern.length();
            int end = start;
            while (end < json.length() && (Character.isDigit(json.charAt(end)) || json.charAt(end) == '-')) {
                end++;
            }
            return end > start ? json.substring(start, end) : "";
        }
        return "";
    }

    static String escapeHtml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    static String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }

    // ---- HTML ----

    static final String INDEX_HTML = """
<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Etendo Setup - GitHub Authentication</title>
<style>
  * { margin: 0; padding: 0; box-sizing: border-box; }
  body {
    font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Helvetica, Arial, sans-serif;
    background: #0d1117;
    color: #c9d1d9;
    display: flex;
    align-items: center;
    justify-content: center;
    min-height: 100vh;
  }
  .card {
    background: #161b22;
    border: 1px solid #30363d;
    border-radius: 12px;
    padding: 48px;
    max-width: 480px;
    width: 90%;
    text-align: center;
  }
  h1 {
    font-size: 24px;
    color: #f0f6fc;
    margin-bottom: 8px;
  }
  .subtitle {
    color: #8b949e;
    font-size: 14px;
    margin-bottom: 32px;
  }
  .code-box {
    background: #0d1117;
    border: 2px solid #58a6ff;
    border-radius: 8px;
    padding: 20px;
    margin: 24px 0;
    font-family: 'SF Mono', 'Fira Code', 'Fira Mono', Menlo, Consolas, monospace;
    font-size: 32px;
    font-weight: bold;
    letter-spacing: 4px;
    color: #f0f6fc;
    user-select: all;
  }
  .buttons {
    display: flex;
    gap: 12px;
    justify-content: center;
    margin: 24px 0;
  }
  .btn {
    padding: 10px 20px;
    border-radius: 6px;
    border: 1px solid #30363d;
    font-size: 14px;
    font-weight: 600;
    cursor: pointer;
    text-decoration: none;
    display: inline-flex;
    align-items: center;
    gap: 6px;
    transition: background 0.15s;
  }
  .btn-primary {
    background: #238636;
    color: #fff;
    border-color: #2ea043;
  }
  .btn-primary:hover { background: #2ea043; }
  .btn-secondary {
    background: #21262d;
    color: #c9d1d9;
  }
  .btn-secondary:hover { background: #30363d; }
  .status {
    margin-top: 24px;
    font-size: 14px;
    color: #8b949e;
  }
  .spinner {
    display: inline-block;
    width: 16px;
    height: 16px;
    border: 2px solid #30363d;
    border-top-color: #58a6ff;
    border-radius: 50%;
    animation: spin 0.8s linear infinite;
    vertical-align: middle;
    margin-right: 8px;
  }
  @keyframes spin { to { transform: rotate(360deg); } }
  .success {
    color: #3fb950;
    font-weight: 600;
  }
  .error {
    color: #f85149;
    font-weight: 600;
  }
  .url {
    color: #58a6ff;
    font-size: 13px;
    word-break: break-all;
  }
  .copied {
    color: #3fb950;
    font-size: 12px;
    margin-top: 4px;
    opacity: 0;
    transition: opacity 0.2s;
  }
  .copied.show { opacity: 1; }
</style>
</head>
<body>
<div class="card">
  <h1>Etendo Setup</h1>
  <p class="subtitle">GitHub authentication required to download packages</p>

  <p class="url">https://github.com/login/device</p>
  <div class="code-box" id="code">{{USER_CODE}}</div>
  <div class="copied" id="copied-msg">Copied!</div>

  <div class="buttons">
    <button class="btn btn-secondary" onclick="copyCode()">Copy code</button>
    <a class="btn btn-primary" href="https://github.com/login/device" target="_blank" rel="noopener">Open GitHub</a>
  </div>

  <div class="status" id="status">
    <span class="spinner"></span> Waiting for authorization...
  </div>
</div>

<script>
function copyCode() {
  var code = document.getElementById('code').textContent;
  navigator.clipboard.writeText(code).then(function() {
    var msg = document.getElementById('copied-msg');
    msg.classList.add('show');
    setTimeout(function() { msg.classList.remove('show'); }, 1500);
  });
}

function poll() {
  fetch('/api/status')
    .then(function(r) { return r.json(); })
    .then(function(data) {
      var el = document.getElementById('status');
      if (data.status === 'success') {
        el.innerHTML = '<span class="success">&#10003; Token saved! Launching setup...</span>';
      } else if (data.status === 'error') {
        el.innerHTML = '<span class="error">Error: ' + (data.message || 'Unknown error') + '</span>';
      } else {
        setTimeout(poll, 3000);
      }
    })
    .catch(function() { setTimeout(poll, 3000); });
}
setTimeout(poll, 3000);
</script>
</body>
</html>
""";
}
