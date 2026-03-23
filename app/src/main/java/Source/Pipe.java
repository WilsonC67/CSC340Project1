package Source;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pipe (Client Thread)
 *
 * Spawned by DoormanListener for every incoming client connection.
 * Lifecycle:
 *   1. Read the full JSON payload from the client.
 *      Expected format:
 *        { "service": <1-5>, "filename": "...", "base64": "..." }
 *
 *   2. Extract the service number and map it to a Service enum value.
 *
 *   3. Send the client the current list of available services.
 *
 *   4. Look up a live Service Node for the requested service.
 *      If none found, reply with an error message and close.
 *
 *   5. Forward the full JSON payload to the Service Node and stream
 *      the result back to the client.
 *
 *   6. Close both sockets.
 *
 * Service number mapping:
 *   1 -> N_BODY_GRAVITATIONAL_STEPPER
 *   2 -> YARA_LITE_PATTERN_SCANNER
 *   3 -> COMPRESSION_DECOMPRESSION
 *   4 -> CSV_STATS
 *   5 -> TBD
 */
public class Pipe implements Runnable {

    private static final Pattern SERVICE_PATTERN =
            Pattern.compile("\"service\"\\s*:\\s*(\\d+)");

    private final Socket       clientSocket;
    private final int          clientId;
    private final NodeRegistry registry;
    private final Runnable     releaseId;

    public Pipe(Socket clientSocket, int clientId, NodeRegistry registry, Runnable releaseId) {
        this.clientSocket = clientSocket;
        this.clientId     = clientId;
        this.registry     = registry;
        this.releaseId    = releaseId;
    }

    @Override
    public void run() {
        System.out.printf("[Pipe-%d] Handling client %s%n",
                clientId, clientSocket.getRemoteSocketAddress());

        try (Socket cs = clientSocket) {
            InputStream  clientIn  = cs.getInputStream();
            OutputStream clientOut = cs.getOutputStream();

            // ---------------------------------------------------------- //
            // Step 1 — send available services back to the client         //
            // Client can read this list before deciding what to send.     //
            // ---------------------------------------------------------- //
            String availableServices = buildServiceList();
            clientOut.write((availableServices + "\n").getBytes());
            clientOut.flush();

            // ---------------------------------------------------------- //
            // Step 2 — read just enough of the payload to extract the     //
            // service number, without buffering the full (potentially     //
            // large) base64 body into heap.                               //
            // ---------------------------------------------------------- //
            byte[] header = clientIn.readNBytes(4096);
            if (header.length == 0) {
                // Client connected only to retrieve the service list — close cleanly.
                System.out.printf("[Pipe-%d] Status-only connection, closing.%n", clientId);
                return;
            }

            String json = new String(header);

            // ---------------------------------------------------------- //
            // Step 3 — extract service number and map to Service enum     //
            // ---------------------------------------------------------- //
            int serviceNum = extractServiceNumber(json);
            if (serviceNum < 1) {
                sendError(clientOut, "Could not parse service number from JSON.");
                return;
            }

            Service requested = serviceFromNumber(serviceNum);
            if (requested == null) {
                sendError(clientOut, "Unknown service number: " + serviceNum);
                return;
            }

            System.out.printf("[Pipe-%d] Routing service number %d -> %s%n",
                    clientId, serviceNum, requested);

            // ---------------------------------------------------------- //
            // Step 4 — find a live node for the requested service         //
            // ---------------------------------------------------------- //
            NodeInfo targetNode = registry.findAliveNode(requested);
            if (targetNode == null) {
                sendError(clientOut, "SERVICE_UNAVAILABLE: No live node for " + requested);
                return;
            }

            // ---------------------------------------------------------- //
            // Step 5 — forward full JSON payload to SN, stream result back//
            // ---------------------------------------------------------- //
            try {
                forwardToNode(targetNode, header, clientIn, clientOut);
            } catch (IOException e) {
                System.err.printf("[Pipe-%d] forwardToNode error: %s%n", clientId, e.getMessage());
                String msg = e.getMessage() != null ? e.getMessage().replace("\"", "'") : "Node unreachable";
                clientOut.write(("{\"status\":\"error\",\"message\":\"" + msg + "\"}").getBytes());
                clientOut.flush();
            }

        } catch (IOException e) {
            System.err.printf("[Pipe-%d] IO error: %s%n", clientId, e.getMessage());
        } finally {
            System.out.printf("[Pipe-%d] Done.%n", clientId);
            releaseId.run();
        }
    }

    // ------------------------------------------------------------------ //
    //  Service number -> enum mapping                                     //
    //  Must stay in sync with whatever numbering Reed uses on the client  //
    // ------------------------------------------------------------------ //

    private Service serviceFromNumber(int n) {
        return switch (n) {
            case 1 -> Service.N_BODY_GRAVITATIONAL_STEPPER;
            case 2 -> Service.BASE64_ENCODE_DECODE;
            case 3 -> Service.COMPRESSION_DECOMPRESSION;
            case 4 -> Service.CSV_STATS;
            case 5 -> Service.IMAGE_TO_ASCII;
            default -> null;
        };
    }

    // ------------------------------------------------------------------ //
    //  Helpers                                                            //
    // ------------------------------------------------------------------ //

    /**
     * Extracts the integer value of the "service" field from the JSON blob
     * using a simple regex — no external library needed for this one field.
     * Returns -1 if not found or unparseable.
     */
    private int extractServiceNumber(String json) {
        Matcher m = SERVICE_PATTERN.matcher(json);
        if (m.find()) {
            try { return Integer.parseInt(m.group(1)); }
            catch (NumberFormatException e) { return -1; }
        }
        return -1;
    }

    /**
     * Opens a TCP connection to the Service Node, streams the full JSON payload
     * (header bytes already read + remaining client stream), signals EOF, then
     * streams the response back to the client — no full buffering in either direction.
     */
    private void forwardToNode(NodeInfo node, byte[] header, InputStream clientIn,
                               OutputStream clientOut) throws IOException {

        System.out.printf("[Pipe-%d] Forwarding to node %d at %s:%d%n",
                clientId, node.getNodeId(), node.getIp(), node.getTcpPort());

        try (Socket nodeSocket = new Socket(node.getIp(), node.getTcpPort())) {
            OutputStream nodeOut = nodeSocket.getOutputStream();
            InputStream  nodeIn  = nodeSocket.getInputStream();

            // Write the already-buffered header, then stream the rest directly.
            nodeOut.write(header);
            clientIn.transferTo(nodeOut);
            nodeSocket.shutdownOutput(); // signal EOF to SN

            // Stream response back without buffering.
            long bytes = nodeIn.transferTo(clientOut);
            clientOut.flush();

            System.out.printf("[Pipe-%d] Forwarded %d result bytes back to client.%n",
                    clientId, bytes);
        }
    }

    private void sendError(OutputStream out, String message) throws IOException {
        String response = "ERROR: " + message + "\n";
        out.write(response.getBytes());
        out.flush();
        System.err.printf("[Pipe-%d] Sent error to client: %s%n", clientId, message);
    }

    private String buildServiceList() {
        StringBuilder sb = new StringBuilder("AVAILABLE_SERVICES:");
        for (Service s : registry.getAvailableServices()) {
            sb.append(s.name()).append(",");
        }
        if (sb.charAt(sb.length() - 1) == ',') {
            sb.deleteCharAt(sb.length() - 1);
        }
        return sb.toString();
    }
}
