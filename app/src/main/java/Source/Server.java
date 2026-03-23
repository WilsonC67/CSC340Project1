package Source;

/**
 * Server — main entry point for the QU Micro-services Cluster controller.
 *
 * Responsibilities:
 *   - Instantiate the shared NodeRegistry.
 *   - Start the HeartbeatMonitor (UDP port 6001) on a dedicated thread.
 *   - Start the DoormanListener (TCP port 5101) on a dedicated thread.
 *
 * Both threads run as non-daemon threads so the JVM stays alive as long
 * as either is running.
 */
public class Server {

    public static void main(String[] args) {
        System.out.println("=== QU Micro-services Cluster Server starting ===");

        // Shared state: all threads read/write through this registry.
        NodeRegistry registry = new NodeRegistry();

        // --- HeartbeatMonitor -------------------------------------------
        HeartbeatMonitor heartbeatMonitor = new HeartbeatMonitor(registry);
        Thread heartbeatThread = new Thread(heartbeatMonitor, "HeartbeatMonitor");
        heartbeatThread.setDaemon(false);
        heartbeatThread.start();
        System.out.println("[Server] HeartbeatMonitor started.");

        // --- DoormanListener --------------------------------------------
        DoormanListener doormanListener = new DoormanListener(registry);
        Thread doormanThread = new Thread(doormanListener, "DoormanListener");
        doormanThread.setDaemon(false);
        doormanThread.start();
        System.out.println("[Server] DoormanListener started.");

        // --- HTTPGateway (browser-facing REST bridge) --------------------
        HTTPGateway httpGateway = new HTTPGateway();
        Thread gatewayThread = new Thread(httpGateway, "HTTPGateway");
        gatewayThread.setDaemon(true);   // daemon: exits with the JVM
        gatewayThread.start();
        System.out.println("[Server] HTTPGateway started on HTTP port " + HTTPGateway.HTTP_PORT + ".");

        System.out.println("[Server] Ready. Listening on TCP:" + DoormanListener.TCP_PORT
                + "  UDP:" + HeartbeatMonitor.UDP_PORT
                + "  HTTP:" + HTTPGateway.HTTP_PORT);

        // The main thread has nothing left to do — the two worker threads
        // keep the JVM alive until the process is killed.
    }
}
