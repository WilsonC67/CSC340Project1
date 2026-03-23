package Source;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;

/**
 * HTTPGateway — Lightweight HTTP bridge that lets browser frontends reach the
 * microservices cluster without needing raw TCP sockets.
 *
 * Runs as a daemon thread alongside DoormanListener and HeartbeatMonitor.
 * Listens on HTTP_PORT (5000) and proxies to DoormanListener on TCP 5101.
 *
 * Endpoints:
 *   GET  /ping          → { "status": "alive" }
 *   GET  /api/status    → { "services": [ { "name": "...", "serviceNum": N } ] }
 *   POST /api/service   → proxies JSON body through Pipe to the appropriate SN
 *   GET  /*             → static files from FRONTEND_DIR
 *
 * All service requests use /api/service regardless of which service is targeted.
 * The JSON payload must contain "service": N so Pipe can route to the right node.
 *
 * TCP protocol followed (matches Pipe.java exactly):
 *   1. Connect to DoormanListener on TCP 5101.
 *   2. Read the AVAILABLE_SERVICES line (terminated with '\n') — discarded.
 *   3. Write the JSON payload.
 *   4. Call shutdownOutput() — signals EOF; Pipe's readAllBytes() returns.
 *   5. Read remaining bytes — the service node result JSON.
 *   6. Return result as the HTTP response body.
 */
public class HTTPGateway implements Runnable {

    public static final int HTTP_PORT = 5050;

    private static final String DOORMAN_HOST  = "127.0.0.1";
    private static final int    DOORMAN_PORT  = DoormanListener.TCP_PORT;

    private static final String FRONTEND_RESOURCE_ROOT = "Frontend";

    // -----------------------------------------------------------------------
    // Runnable entry point
    // -----------------------------------------------------------------------

    @Override
    public void run() {
        try {
            HttpServer server = HttpServer.create(
                    new InetSocketAddress(HTTP_PORT), /* backlog */ 32);
            server.createContext("/ping",         this::handlePing);
            server.createContext("/api/service",  this::handleService);
            server.createContext("/api/stream",   this::handleStream);
            server.createContext("/api/status",   this::handleStatus);
            server.createContext("/",             this::handleStatic);
            server.setExecutor(Executors.newCachedThreadPool());
            server.start();
            System.out.printf("[HTTPGateway] HTTP server listening on port %d%n", HTTP_PORT);
            System.out.printf("[HTTPGateway] Serving frontend from classpath: %s%n", FRONTEND_RESOURCE_ROOT);
        } catch (IOException e) {
            System.err.printf("[HTTPGateway] Failed to start: %s%n", e.getMessage());
        }
    }

    // -----------------------------------------------------------------------
    // GET /ping
    // -----------------------------------------------------------------------

    private void handlePing(HttpExchange ex) throws IOException {
        byte[] body = "{\"status\":\"alive\"}".getBytes(StandardCharsets.UTF_8);
        addCors(ex);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(200, body.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(body); }
    }

    // -----------------------------------------------------------------------
    // POST /api/service  — generic proxy for all other services
    // -----------------------------------------------------------------------

    private void handleService(HttpExchange ex) throws IOException {
        addCors(ex);

        if ("OPTIONS".equalsIgnoreCase(ex.getRequestMethod())) {
            ex.sendResponseHeaders(204, -1);
            return;
        }

        try (Socket socket = new Socket(DOORMAN_HOST, DOORMAN_PORT)) {
            InputStream  tcpIn  = socket.getInputStream();
            OutputStream tcpOut = socket.getOutputStream();

            // Step 1: read AVAILABLE_SERVICES line Pipe sends immediately on connect
            readLine(tcpIn);

            // Step 2: stream HTTP request body → TCP (no in-memory buffering)
            ex.getRequestBody().transferTo(tcpOut);
            socket.shutdownOutput(); // signal EOF so Pipe's readAllBytes() returns

            // Step 3: stream TCP response → HTTP response (chunked, no size needed)
            ex.getResponseHeaders().set("Content-Type", "application/json");
            ex.sendResponseHeaders(200, 0);
            try (OutputStream os = ex.getResponseBody()) { tcpIn.transferTo(os); }

        } catch (IOException e) {
            sendError(ex, "Could not reach DoormanListener: " + e.getMessage());
        }
    }

    // -----------------------------------------------------------------------
    // POST /api/stream — raw binary protocol for compression service
    // -----------------------------------------------------------------------

    /**
     * Raw binary streaming endpoint used by the compression service.
     *
     * Request body (from CompDecomp.html):
     *   {"service":3,"operation":"compress"|"decompress","filename":"<name>"}\n
     *   <raw binary file bytes>
     *
     * Response from node:
     *   {"status":"ok","filename":"<output>"}\n<raw binary result bytes>
     *     — or —
     *   {"status":"error","message":"<details>"}\n
     *
     * This gateway reads the one-line JSON response header from the node,
     * sets Content-Disposition, then streams the remaining raw bytes as the
     * HTTP response body — no base64 anywhere.
     */
    private void handleStream(HttpExchange ex) throws IOException {
        addCors(ex);

        if ("OPTIONS".equalsIgnoreCase(ex.getRequestMethod())) {
            ex.sendResponseHeaders(204, -1);
            return;
        }

        System.out.printf("[HTTPGateway/stream] %s %s%n",
                ex.getRequestMethod(), ex.getRequestURI());

        try (Socket socket = new Socket(DOORMAN_HOST, DOORMAN_PORT)) {
            InputStream  tcpIn  = socket.getInputStream();
            OutputStream tcpOut = socket.getOutputStream();

            // Step 1: consume AVAILABLE_SERVICES line Pipe sends on connect
            readLine(tcpIn);

            // Step 2: send request body in a background thread so we can simultaneously
            // read the response. Without this, a streaming node that writes output while
            // reading input causes a bidirectional TCP deadlock once the kernel socket
            // buffers fill up — identical to the same fix applied in Pipe.forwardToNode.
            InputStream httpBody = ex.getRequestBody();
            Thread sender = new Thread(() -> {
                try {
                    httpBody.transferTo(tcpOut);
                    socket.shutdownOutput();
                } catch (IOException ignored) {}
            }, "HTTPStreamSender");
            sender.setDaemon(true);
            sender.start();

            // Step 3: read the one-line JSON response header from the node
            String responseHeader = readLine(tcpIn);
            System.out.printf("[HTTPGateway/stream] Node response header: %s%n", responseHeader);
            String status   = extractJsonField(responseHeader, "status");
            String filename = extractJsonField(responseHeader, "filename");
            String message  = extractJsonField(responseHeader, "message");

            if (!"ok".equals(status)) {
                String esc  = (message != null ? message : "Unknown error")
                        .replace("\\", "\\\\").replace("\"", "\\\"");
                byte[] body = ("{\"status\":\"error\",\"message\":\"" + esc + "\"}")
                        .getBytes(StandardCharsets.UTF_8);
                ex.getResponseHeaders().set("Content-Type", "application/json");
                ex.sendResponseHeaders(500, body.length);
                try (OutputStream os = ex.getResponseBody()) { os.write(body); }
                return;
            }

            // Step 4: stream raw binary result bytes back to the browser
            String safeFilename = (filename != null && !filename.isBlank())
                    ? filename.replaceAll("[^\\w. _()\\[\\]-]", "_")
                    : "output";
            ex.getResponseHeaders().set("Content-Type", "application/octet-stream");
            ex.getResponseHeaders().set("Content-Disposition",
                    "attachment; filename=\"" + safeFilename + "\"");
            ex.sendResponseHeaders(200, 0); // 0 = chunked / unknown length
            try (OutputStream os = ex.getResponseBody()) { tcpIn.transferTo(os); }

            try { sender.join(30_000); } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

        } catch (IOException e) {
            sendError(ex, "Could not reach DoormanListener: " + e.getMessage());
        }
    }

    /**
     * Extracts the string value of a JSON field using a simple char scanner.
     * Handles basic backslash escapes. Returns null if the field is not found.
     */
    private static String extractJsonField(String json, String key) {
        String needle = "\"" + key + "\":\"";
        int pos = json.indexOf(needle);
        if (pos < 0) return null;
        pos += needle.length();
        StringBuilder sb = new StringBuilder();
        while (pos < json.length()) {
            char c = json.charAt(pos++);
            if (c == '"') break;
            if (c == '\\' && pos < json.length()) c = json.charAt(pos++);
            sb.append(c);
        }
        return sb.toString();
    }

    // -----------------------------------------------------------------------
    // GET /api/status — live service list via TCP pipeline
    // -----------------------------------------------------------------------

    /**
     * Opens a TCP connection to DoormanListener, reads the AVAILABLE_SERVICES
     * line that Pipe sends immediately on connect, then closes without sending
     * a payload (Pipe sees empty bytes and exits cleanly).
     *
     * Returns JSON: { "services": [ { "name": "...", "serviceNum": N }, ... ] }
     */
    private void handleStatus(HttpExchange ex) throws IOException {
        addCors(ex);
        if ("OPTIONS".equalsIgnoreCase(ex.getRequestMethod())) {
            ex.sendResponseHeaders(204, -1);
            return;
        }

        try (Socket socket = new Socket(DOORMAN_HOST, DOORMAN_PORT)) {
            String line = readLine(socket.getInputStream());
            // line format: "AVAILABLE_SERVICES:NAME1,NAME2,..."

            StringBuilder sb = new StringBuilder("{\"services\":[");
            String prefix = "AVAILABLE_SERVICES:";
            if (line.startsWith(prefix)) {
                String list = line.substring(prefix.length());
                if (!list.isEmpty()) {
                    String[] names = list.split(",");
                    boolean first = true;
                    for (String name : names) {
                        name = name.trim();
                        if (name.isEmpty()) continue;
                        int num = serviceNumber(name);
                        if (!first) sb.append(",");
                        sb.append("{\"name\":\"").append(name)
                          .append("\",\"serviceNum\":").append(num).append("}");
                        first = false;
                    }
                }
            }
            sb.append("]}");

            byte[] body = sb.toString().getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "application/json");
            ex.sendResponseHeaders(200, body.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(body); }

        } catch (IOException e) {
            sendError(ex, "Could not reach DoormanListener: " + e.getMessage());
        }
    }

    private static int serviceNumber(String name) {
        return switch (name) {
            case "N_BODY_GRAVITATIONAL_STEPPER" -> 1;
            case "BASE64_ENCODE_DECODE"         -> 2;
            case "COMPRESSION_DECOMPRESSION"    -> 3;
            case "CSV_STATS"                    -> 4;
            case "IMAGE_TO_ASCII"               -> 5;
            default                             -> -1;
        };
    }

    // -----------------------------------------------------------------------
    // GET /* — static frontend files
    // -----------------------------------------------------------------------

    /**
     * Serves static files bundled in the jar under Frontend/.
     *   /                        → Frontend/src/index.html
     *   /src/Gravitational.html  → Frontend/src/Gravitational.html
     *   /Resources/images/x.png  → Frontend/Resources/images/x.png
     */
    private void handleStatic(HttpExchange ex) throws IOException {
        String uriPath = ex.getRequestURI().getPath();

        if (uriPath.equals("/")) {
            uriPath = "/src/index.html";
        } else if (!uriPath.startsWith("/Resources/")) {
            uriPath = "/src" + uriPath;
        }

        // Block path traversal attempts
        if (uriPath.contains("..")) {
            send404(ex); return;
        }

        String resourcePath = FRONTEND_RESOURCE_ROOT + uriPath;
        try (InputStream is = HTTPGateway.class.getClassLoader().getResourceAsStream(resourcePath)) {
            if (is == null) {
                send404(ex); return;
            }
            byte[] body = is.readAllBytes();
            ex.getResponseHeaders().set("Content-Type", mimeType(resourcePath));
            ex.sendResponseHeaders(200, body.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(body); }
        }
    }

    private static String mimeType(String name) {
        if (name.endsWith(".html")) return "text/html; charset=utf-8";
        if (name.endsWith(".css"))  return "text/css; charset=utf-8";
        if (name.endsWith(".js"))   return "application/javascript";
        if (name.endsWith(".png"))  return "image/png";
        if (name.endsWith(".jpg") || name.endsWith(".jpeg")) return "image/jpeg";
        if (name.endsWith(".ico"))  return "image/x-icon";
        if (name.endsWith(".json")) return "application/json";
        return "application/octet-stream";
    }

    private static void send404(HttpExchange ex) throws IOException {
        byte[] body = "404 Not Found".getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "text/plain");
        ex.sendResponseHeaders(404, body.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(body); }
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private void addCors(HttpExchange ex) {
        ex.getResponseHeaders().set("Access-Control-Allow-Origin",  "*");
        ex.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        ex.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type");
    }

    private void sendError(HttpExchange ex, String message) throws IOException {
        String esc  = message.replace("\\", "\\\\").replace("\"", "\\\"");
        byte[] body = ("{\"status\":\"error\",\"message\":\"" + esc + "\"}")
                .getBytes(StandardCharsets.UTF_8);
        addCors(ex);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(500, body.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(body); }
    }

    /** Reads one '\n'-terminated line from the stream (used to consume the AVAILABLE_SERVICES line). */
    private static String readLine(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        int b;
        while ((b = in.read()) != -1) {
            if (b == '\n') break;
            if (b != '\r') sb.append((char) b);
        }
        return sb.toString();
    }
}
