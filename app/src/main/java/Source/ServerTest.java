package Source;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;

/**
 * ServerTest.java
 *
 * Standalone test harness for the Server — no other project components needed.
 *
 * Contains two nested runnable test utilities:
 *   FakeNode   — sends UDP heartbeats and listens for TCP task forwards
 *   FakeClient — connects to the server, sends a JSON payload, prints result
 *
 * HOW TO RUN:
 *   1. Start Server.java
 *   2. In a second terminal: java Source.ServerTest node
 *   3. In a third terminal:  java Source.ServerTest client
 *
 * The fake node will echo whatever payload it receives back to the server.
 * The fake client will print the echoed result if everything is working.
 */
public class ServerTest {

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.out.println("Usage: java Source.ServerTest [node|client]");
            return;
        }
        switch (args[0].toLowerCase()) {
            case "node"   -> FakeNode.run();
            case "client" -> FakeClient.run();
            default       -> System.out.println("Unknown argument. Use 'node' or 'client'.");
        }
    }

    // ====================================================================
    // FakeNode
    //
    // Simulates a Service Node by:
    //   1. Sending a UDP heartbeat to the server every 10 seconds.
    //   2. Listening on TCP port 5201 for forwarded payloads, printing
    //      them, and echoing them back as the "result".
    // ====================================================================
    static class FakeNode {

        static final String SERVER_IP = "127.0.0.1";
        static final int    UDP_PORT  = 6001;
        static final int    TCP_PORT  = 5201;
        static final int    NODE_ID   = 1;
        // Service number 4 = CSV_STATS — change to match what FakeClient sends
        static final String SERVICE   = Service.CSV_STATS.name();

        static void run() throws Exception {
            System.out.println("[FakeNode] Starting...");

            // Heartbeat sender thread
            Thread heartbeat = new Thread(() -> {
                try (DatagramSocket udpSocket = new DatagramSocket()) {
                    // Format: "<nodeId>,<tcpPort>,<serviceName>"
                    String msg = NODE_ID + "," + TCP_PORT + "," + SERVICE;
                    byte[] data = msg.getBytes();
                    InetAddress serverAddr = InetAddress.getByName(SERVER_IP);

                    while (!Thread.currentThread().isInterrupted()) {
                        DatagramPacket packet = new DatagramPacket(data, data.length, serverAddr, UDP_PORT);
                        udpSocket.send(packet);
                        System.out.println("[FakeNode] Sent heartbeat: " + msg);
                        Thread.sleep(10_000);
                    }
                } catch (Exception e) {
                    System.err.println("[FakeNode] Heartbeat error: " + e.getMessage());
                }
            }, "FakeNode-Heartbeat");
            heartbeat.setDaemon(true);
            heartbeat.start();

            // TCP task listener
            try (ServerSocket tcpServer = new ServerSocket(TCP_PORT)) {
                System.out.println("[FakeNode] Listening for TCP task forwards on port " + TCP_PORT);

                while (true) {
                    Socket conn = tcpServer.accept();
                    System.out.println("[FakeNode] Received connection from server Pipe.");

                    new Thread(() -> {
                        try (Socket s = conn) {
                            byte[] payload = s.getInputStream().readAllBytes();
                            System.out.println("[FakeNode] Received payload (" + payload.length + " bytes): "
                                    + new String(payload));

                            // Echo back as result
                            String response = "ECHO:" + new String(payload);
                            s.getOutputStream().write(response.getBytes());
                            s.getOutputStream().flush();
                            System.out.println("[FakeNode] Sent echo response.");
                        } catch (IOException e) {
                            System.err.println("[FakeNode] Task handler error: " + e.getMessage());
                        }
                    }, "FakeNode-TaskHandler").start();
                }
            }
        }
    }

    // ====================================================================
    // FakeClient
    //
    // Simulates Reed's JavaFX client by sending a JSON blob matching the
    // agreed format: { "service": <1-5>, "filename": "...", "base64": "..." }
    // ====================================================================
    static class FakeClient {

        static final String SERVER_IP   = "127.0.0.1";
        static final int    SERVER_PORT = 5101;

        // Matches the format Reed's client will send.
        // Service 4 = CSV_STATS — must match FakeNode's SERVICE above.
        static final String PAYLOAD = """
                { "service": 4, "filename": "data.csv", "base64": "Y29sMSxjb2wyXG4xLDJcbjMsNFxuNSw2" }
                """;

        static void run() throws Exception {
            System.out.println("[FakeClient] Connecting to server at "
                    + SERVER_IP + ":" + SERVER_PORT);

            try (Socket socket = new Socket(SERVER_IP, SERVER_PORT)) {
                OutputStream out = socket.getOutputStream();
                InputStream  in  = socket.getInputStream();

                // Send the JSON payload and close write side
                out.write(PAYLOAD.getBytes());
                out.flush();
                socket.shutdownOutput();
                System.out.println("[FakeClient] Sent JSON payload: " + PAYLOAD.trim());

                // Read available services line back from server
                String serviceList = readLine(in);
                System.out.println("[FakeClient] Server says: " + serviceList);

                // Read and print the final result
                byte[] result = in.readAllBytes();
                System.out.println("[FakeClient] Result from server: " + new String(result));
            }
        }

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
}
