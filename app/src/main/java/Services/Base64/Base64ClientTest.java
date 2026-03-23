package Services.Base64;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;

/**
 * Base64ClientTest — Client-side test harness for the Base64 microservice.
 *
 * HOW TO RUN (in order):
 *   1.  Start Server.java              (HeartbeatMonitor + DoormanListener)
 *   2.  Start Base64Node.java          (registers via heartbeat, listens on 5102)
 *   3.  Run this class                 (connects to server, sends JSON, prints result)
 *
 * The test sends two requests back-to-back:
 *   Test A — encode "Hello, World!"   → expects "SGVsbG8sIFdvcmxkIQ=="
 *   Test B — decode "SGVsbG8sIFdvcmxkIQ==" → expects "Hello, World!"
 *
 * JSON format sent to the server (matches what Pipe.java parses):
 *   {
 *     "service"   : 2,
 *     "operation" : "encode" | "decode",
 *     "filename"  : "test.txt",
 *     "data"      : "<value>"
 *   }
 */
public class Base64ClientTest {

    private static final String SERVER_IP   = "127.0.0.1";
    private static final int    SERVER_PORT = 5101;          // DoormanListener.TCP_PORT

    public static void main(String[] args) throws Exception {
        System.out.println("=== Base64 Microservice Client Test ===\n");

        // Wait briefly to give Base64Node time to send its first heartbeat
        // and register with the server before we send a request.
        System.out.println("[Test] Waiting 6s for Base64Node to register via heartbeat...");
        Thread.sleep(6_000);

        // ----------------------------------------------------------------
        // Test A — Encode
        // ----------------------------------------------------------------
        System.out.println("\n--- Test A: Encode ---");
        String encodePayload = buildJson("encode", "Hello, World!");
        System.out.println("[Test] Sending: " + encodePayload);
        String encodeResponse = sendRequest(encodePayload);
        System.out.println("[Test] Response: " + encodeResponse);
        assertContains(encodeResponse, "SGVsbG8sIFdvcmxkIQ==", "Test A");

        // ----------------------------------------------------------------
        // Test B — Decode
        // ----------------------------------------------------------------
        System.out.println("\n--- Test B: Decode ---");
        String decodePayload = buildJson("decode", "SGVsbG8sIFdvcmxkIQ==");
        System.out.println("[Test] Sending: " + decodePayload);
        String decodeResponse = sendRequest(decodePayload);
        System.out.println("[Test] Response: " + decodeResponse);
        assertContains(decodeResponse, "Hello, World!", "Test B");

        // ----------------------------------------------------------------
        // Test C — Invalid Base64 (error handling)
        // ----------------------------------------------------------------
        System.out.println("\n--- Test C: Invalid Base64 (error case) ---");
        String badPayload = buildJson("decode", "this is not base64!!!");
        System.out.println("[Test] Sending: " + badPayload);
        String badResponse = sendRequest(badPayload);
        System.out.println("[Test] Response: " + badResponse);
        assertContains(badResponse, "error", "Test C");

        System.out.println("\n=== All tests completed. ===");
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /**
     * Builds a JSON payload matching the format Pipe.java expects.
     * Service 2 = BASE64_ENCODE_DECODE.
     */
    private static String buildJson(String operation, String data) {
        // Escape backslashes and double-quotes in the data value.
        String escapedData = data.replace("\\", "\\\\").replace("\"", "\\\"");
        return String.format(
            "{ \"service\": 2, \"operation\": \"%s\", \"filename\": \"test.txt\", \"data\": \"%s\" }",
            operation, escapedData
        );
    }

    /**
     * Opens a TCP connection to the server, sends the JSON payload, and
     * returns the full response as a string.
     *
     * Protocol (matches Pipe.java exactly):
     *   1. Write JSON bytes to server.
     *   2. Call shutdownOutput() — signals EOF so Pipe's readAllBytes() returns.
     *   3. Read the "AVAILABLE_SERVICES:..." line Pipe sends first.
     *   4. Read all remaining bytes — the actual result from the node.
     */
    private static String sendRequest(String json) throws IOException {
        try (Socket socket = new Socket(SERVER_IP, SERVER_PORT)) {
            OutputStream out = socket.getOutputStream();
            InputStream  in  = socket.getInputStream();

            // Send the JSON payload.
            out.write(json.getBytes());
            out.flush();
            socket.shutdownOutput(); // tell Pipe we're done writing

            // Read the AVAILABLE_SERVICES line that Pipe always sends first.
            String serviceList = readLine(in);
            System.out.println("[Test] Available services: " + serviceList);

            // Read the result forwarded from the node.
            byte[] resultBytes = in.readAllBytes();
            return new String(resultBytes).trim();
        }
    }

    /** Reads one '\n'-terminated line from an InputStream. */
    private static String readLine(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        int b;
        while ((b = in.read()) != -1) {
            if (b == '\n') break;
            if (b != '\r') sb.append((char) b);
        }
        return sb.toString();
    }

    /** Asserts the response contains the expected substring and prints PASS/FAIL. */
    private static void assertContains(String response, String expected, String testName) {
        if (response.contains(expected)) {
            System.out.printf("[%s] PASS — response contains \"%s\"%n", testName, expected);
        } else {
            System.out.printf("[%s] FAIL — expected \"%s\" but got: %s%n",
                    testName, expected, response);
        }
    }
}