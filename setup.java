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
<title>Etendo Setup</title>
<style>
  * { margin: 0; padding: 0; box-sizing: border-box; }
  body {
    font-family: 'Inter', -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif;
    background: #f5f6fa;
    color: #1a1a2e;
    display: flex;
    flex-direction: column;
    align-items: center;
    justify-content: center;
    min-height: 100vh;
  }
  .header {
    margin-bottom: 32px;
    text-align: center;
  }
  .logo {
    display: inline-flex;
    align-items: center;
    gap: 10px;
    margin-bottom: 4px;
  }
  .logo-icon {
    width: 36px;
    height: 36px;
    background: #004aca;
    border-radius: 8px;
    display: flex;
    align-items: center;
    justify-content: center;
  }
  .logo-title {
    font-size: 20px;
    font-weight: 700;
    color: #004aca;
    letter-spacing: 0.5px;
  }
  .logo-subtitle {
    font-size: 12px;
    color: #6b7280;
    margin-top: 2px;
  }
  .card {
    background: #ffffff;
    border-radius: 12px;
    box-shadow: 0 1px 4px rgba(0,0,0,0.08), 0 4px 16px rgba(0,74,202,0.06);
    padding: 40px 48px;
    max-width: 460px;
    width: 90%;
    text-align: center;
  }
  .step-label {
    display: inline-block;
    background: #e3f2fd;
    color: #004aca;
    font-size: 11px;
    font-weight: 600;
    letter-spacing: 1px;
    text-transform: uppercase;
    border-radius: 20px;
    padding: 3px 12px;
    margin-bottom: 16px;
  }
  h1 {
    font-size: 22px;
    font-weight: 700;
    color: #111827;
    margin-bottom: 8px;
  }
  .subtitle {
    color: #6b7280;
    font-size: 14px;
    line-height: 1.5;
    margin-bottom: 28px;
  }
  .divider {
    height: 1px;
    background: #f0f0f5;
    margin: 0 -48px 28px;
  }
  .instruction {
    font-size: 13px;
    color: #6b7280;
    margin-bottom: 10px;
  }
  .url-chip {
    display: inline-block;
    background: #e3f2fd;
    color: #004aca;
    font-size: 13px;
    font-weight: 500;
    border-radius: 6px;
    padding: 5px 14px;
    margin-bottom: 20px;
    text-decoration: none;
    border: 1px solid #bbdefb;
  }
  .url-chip:hover { background: #bbdefb; }
  .code-box {
    background: #f5f6fa;
    border: 2px solid #004aca;
    border-radius: 8px;
    padding: 18px 20px;
    margin: 0 0 8px;
    font-family: 'SF Mono', 'Fira Code', Menlo, Consolas, monospace;
    font-size: 30px;
    font-weight: 700;
    letter-spacing: 6px;
    color: #004aca;
    user-select: all;
  }
  .copied {
    font-size: 12px;
    color: #4caf50;
    margin-bottom: 20px;
    height: 16px;
    opacity: 0;
    transition: opacity 0.2s;
  }
  .copied.show { opacity: 1; }
  .buttons {
    display: flex;
    gap: 10px;
    justify-content: center;
    margin-bottom: 28px;
  }
  .btn {
    padding: 9px 20px;
    border-radius: 8px;
    font-size: 14px;
    font-weight: 600;
    cursor: pointer;
    text-decoration: none;
    display: inline-flex;
    align-items: center;
    gap: 6px;
    transition: background 0.15s, box-shadow 0.15s;
    border: none;
    outline: none;
  }
  .btn-primary {
    background: #004aca;
    color: #fff;
    box-shadow: 0 2px 6px rgba(0,74,202,0.25);
  }
  .btn-primary:hover { background: #003494; box-shadow: 0 4px 10px rgba(0,74,202,0.3); }
  .btn-secondary {
    background: #ffffff;
    color: #004aca;
    border: 1px solid #bbdefb;
  }
  .btn-secondary:hover { background: #e3f2fd; }
  .status {
    font-size: 13px;
    color: #6b7280;
    display: flex;
    align-items: center;
    justify-content: center;
    gap: 8px;
    padding: 12px 16px;
    background: #f5f6fa;
    border-radius: 8px;
  }
  .spinner {
    display: inline-block;
    width: 15px;
    height: 15px;
    border: 2px solid #bbdefb;
    border-top-color: #004aca;
    border-radius: 50%;
    animation: spin 0.8s linear infinite;
    flex-shrink: 0;
  }
  @keyframes spin { to { transform: rotate(360deg); } }
  .success { color: #4caf50; font-weight: 600; }
  .error { color: #f44336; font-weight: 600; }
</style>
</head>
<body>

<div class="header">
  <div class="logo">
    <div class="logo-icon">
      <svg width="20" height="20" viewBox="0 0 20 20" fill="none">
        <path d="M4 10L8 14L16 6" stroke="white" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round"/>
      </svg>
    </div>
    <span class="logo-title">ETENDO TOOL</span>
  </div>
  <div class="logo-subtitle">Control Center</div>
</div>

<div class="card">
  <span class="step-label">Setup Required</span>
  <h1>GitHub Authentication</h1>
  <p class="subtitle">A GitHub token is needed to download Etendo packages.<br>Complete the steps below to continue.</p>

  <div class="divider"></div>

  <p class="instruction">1. Open GitHub and enter this code:</p>
  <a class="url-chip" href="https://github.com/login/device" target="_blank" rel="noopener">
    github.com/login/device ↗
  </a>

  <p class="instruction">2. Enter the code shown below:</p>
  <div class="code-box" id="code">{{USER_CODE}}</div>
  <div class="copied" id="copied-msg">&#10003; Copied to clipboard</div>

  <div class="buttons">
    <button class="btn btn-secondary" onclick="copyCode()">
      <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5"><rect x="9" y="9" width="13" height="13" rx="2"/><path d="M5 15H4a2 2 0 01-2-2V4a2 2 0 012-2h9a2 2 0 012 2v1"/></svg>
      Copy code
    </button>
    <a class="btn btn-primary" href="https://github.com/login/device" target="_blank" rel="noopener">
      <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5"><path d="M18 13v6a2 2 0 01-2 2H5a2 2 0 01-2-2V8a2 2 0 012-2h6M15 3h6v6M10 14L21 3"/></svg>
      Open GitHub
    </a>
  </div>

  <div class="status" id="status">
    <span class="spinner"></span>
    <span>Waiting for authorization...</span>
  </div>
</div>

<script>
function copyCode() {
  var code = document.getElementById('code').textContent.trim();
  navigator.clipboard.writeText(code).then(function() {
    var msg = document.getElementById('copied-msg');
    msg.classList.add('show');
    setTimeout(function() { msg.classList.remove('show'); }, 2000);
  });
}

function poll() {
  fetch('/api/status')
    .then(function(r) { return r.json(); })
    .then(function(data) {
      var el = document.getElementById('status');
      if (data.status === 'success') {
        el.innerHTML = '<span class="success">&#10003; Token saved — launching Etendo setup...</span>';
      } else if (data.status === 'error') {
        el.innerHTML = '<span class="error">&#x26A0; ' + (data.message || 'Unknown error') + '</span>';
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
