package Source;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
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

    /**
     * Root directory for static frontend files.
     * Default works when the server is launched from the project root.
     * Override on AWS: -Dfrontend.dir=/home/ec2-user/Frontend
     */
    private static final String FRONTEND_DIR =
            System.getProperty("frontend.dir", "Frontend");

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
            server.createContext("/api/status",   this::handleStatus);
            server.createContext("/",             this::handleStatic);
            server.setExecutor(Executors.newCachedThreadPool());
            server.start();
            System.out.printf("[HTTPGateway] HTTP server listening on port %d%n", HTTP_PORT);
            System.out.printf("[HTTPGateway] Serving frontend from: %s%n",
                    new File(FRONTEND_DIR).getCanonicalPath());
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
     * Serves static files from FRONTEND_DIR.
     *   /                        → Frontend/src/index.html
     *   /src/Gravitational.html  → Frontend/src/Gravitational.html
     *   /Resources/images/x.png  → Frontend/Resources/images/x.png
     *
     * Path traversal is blocked by canonical-path check.
     */
    private void handleStatic(HttpExchange ex) throws IOException {
        String uriPath = ex.getRequestURI().getPath();

        if (uriPath.equals("/")) {
            uriPath = "/src/index.html";
        } else if (!uriPath.startsWith("/Resources/")) {
            uriPath = "/src" + uriPath;
        }

        File root = new File(FRONTEND_DIR).getCanonicalFile();
        File file = new File(root, uriPath).getCanonicalFile();

        // Security: block any path that escapes the frontend root
        if (!file.getPath().startsWith(root.getPath())) {
            send404(ex); return;
        }

        if (!file.exists() || !file.isFile()) {
            send404(ex); return;
        }

        byte[] body = Files.readAllBytes(file.toPath());
        ex.getResponseHeaders().set("Content-Type", mimeType(file.getName()));
        ex.sendResponseHeaders(200, body.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(body); }
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
