package Source;

import java.net.DatagramPacket;
import java.net.DatagramSocket;

/**
 * Listens for UDP heartbeats from Service Nodes on port 6001.
 *
 * Expected packet format (UTF-8, newline-optional):
 *   "<nodeId>,<tcpPort>,<serviceName>"
 * Example: "2,5201,YARA_LITE_PATTERN_SCANNER"
 *
 * Also runs an internal watcher thread that sweeps for dead nodes every 10s.
 */
public class HeartbeatMonitor implements Runnable {

    public static final int UDP_PORT        = 6001;
    private static final int BUFFER_SIZE    = 256;
    private static final int SWEEP_INTERVAL = 10_000;

    private final NodeRegistry registry;

    public HeartbeatMonitor(NodeRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void run() {
        Thread watcher = new Thread(this::watcherLoop, "HeartbeatWatcher");
        watcher.setDaemon(true);
        watcher.start();
        receiverLoop();
    }

    private void receiverLoop() {
        try (DatagramSocket socket = new DatagramSocket(UDP_PORT)) {
            System.out.printf("[HeartbeatMonitor] Listening on UDP port %d%n", UDP_PORT);
            byte[] buffer = new byte[BUFFER_SIZE];
            while (!Thread.currentThread().isInterrupted()) {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                socket.receive(packet);
                String sourceIp = packet.getAddress().getHostAddress();
                String payload  = new String(packet.getData(), 0, packet.getLength()).trim();
                parseAndRegister(sourceIp, payload);
            }
        } catch (Exception e) {
            System.err.println("[HeartbeatMonitor] Receiver error: " + e.getMessage());
        }
    }

    private void parseAndRegister(String sourceIp, String payload) {
        try {
            String[] parts = payload.split(",");
            if (parts.length < 3) {
                System.err.println("[HeartbeatMonitor] Malformed heartbeat: " + payload);
                return;
            }
            int     nodeId  = Integer.parseInt(parts[0].trim());
            int     tcpPort = Integer.parseInt(parts[1].trim());
            Service service = Service.fromString(parts[2].trim());
            if (service == null) {
                System.err.println("[HeartbeatMonitor] Unknown service: " + parts[2]);
                return;
            }
            registry.heartbeat(nodeId, sourceIp, tcpPort, service);
        } catch (NumberFormatException e) {
            System.err.println("[HeartbeatMonitor] Parse error for '" + payload + "': " + e.getMessage());
        }
    }

    private void watcherLoop() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                Thread.sleep(SWEEP_INTERVAL);
                registry.sweepDeadNodes();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }
}
