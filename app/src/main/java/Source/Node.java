package Source;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Node — Abstract base class for all five microservice nodes.
 *
 * Each concrete subclass must provide:
 *   - getNodeId()  — unique integer ID for the NodeRegistry
 *   - getService() — the Service enum value this node handles
 *
 * Per-request processing — subclass chooses one of two patterns:
 *
 *   PATTERN A — In-process (pure Java, recommended for Java services):
 *     Override handleRequest(byte[] payload) and do the work directly.
 *     Return the result as a byte[]. No subprocess is spawned.
 *
 *   PATTERN B — Subprocess (for Python, C, etc.):
 *     Override getExecutorCommand() to return the OS command to launch.
 *     Node pipes the JSON payload to STDIN and reads the result from STDOUT.
 *     The default handleRequest() implements this pattern automatically.
 *
 * Data flow (both patterns):
 *   Client ──► DoormanListener ──► Pipe ──(JSON)──► Node
 *                                                      │
 *                                            handleRequest(payload)
 *                                                      │
 *                                               result bytes
 *                                                      │
 *                                              Pipe ──► Client
 *
 * Infrastructure (handled here — subclasses never touch this):
 *   1. HeartbeatPulse  — daemon thread; sends UDP datagram to HeartbeatMonitor
 *                        every HEARTBEAT_INTERVAL_MS ms.
 *                        Packet: "<nodeId>,<servicePort>,<serviceName>"
 *   2. ServiceListener — accepts TCP connections from Pipe on SERVICE_PORT,
 *                        dispatches each to the worker pool.
 */
public abstract class Node implements Runnable {

    // -----------------------------------------------------------------------
    // Constants
    // -----------------------------------------------------------------------

    /** All nodes bind to this TCP port — each runs on its own host/container.
     *  Override for local multi-node testing: -Dservice.port=5103 */
    public static final int SERVICE_PORT =
            Integer.parseInt(System.getProperty("service.port", "5102"));

    /** How often (ms) this node sends a UDP heartbeat to the server. */
    private static final int HEARTBEAT_INTERVAL_MS = 5_000;

    /**
     * Server host where HeartbeatMonitor is listening.
     * Defaults to "localhost" for single-machine dev.
     * Override at launch: java -Dserver.host=<IP> Services.NBody.NBodyNode
     */
    private static final String SERVER_HOST =
            System.getProperty("server.host", "localhost");

    /** Must match HeartbeatMonitor.UDP_PORT. */
    private static final int HEARTBEAT_UDP_PORT = 6001;

    /** Max seconds to wait for a subprocess before killing it (Pattern B only). */
    private static final int EXECUTOR_TIMEOUT_SEC = 30;

    // -----------------------------------------------------------------------
    // Subclass contract
    // -----------------------------------------------------------------------

    /** Unique integer ID for this node. Used as the NodeRegistry map key. */
    protected abstract int getNodeId();

    /** The Service this node provides. Must match the Service enum exactly. */
    protected abstract Service getService();

    /**
     * PATTERN A — In-process handler.
     *
     * Override this to process the request entirely in Java.
     * Receives the raw JSON payload bytes forwarded by Pipe.
     * Returns the result bytes to be sent back to the client.
     *
     * The default implementation delegates to getExecutorCommand() (Pattern B).
     * Subclasses that override this do NOT need to override getExecutorCommand().
     *
     * @param  payload  raw JSON bytes received from Pipe
     * @return          result bytes to forward back to the client
     * @throws Exception on any processing error
     */
    protected byte[] handleRequest(byte[] payload) throws Exception {
        String[] cmd = getExecutorCommand();
        if (cmd == null || cmd.length == 0) {
            throw new UnsupportedOperationException(
                "Either override handleRequest() or getExecutorCommand() in "
                + getClass().getSimpleName());
        }
        return runSubprocess(cmd, payload);
    }

    /**
     * PATTERN B — Subprocess command.
     *
     * Override this to provide an OS command whose process reads the JSON
     * payload from STDIN and writes the result to STDOUT.
     *
     * Only called if handleRequest() is NOT overridden.
     * Returns null by default (triggers an error if handleRequest is also not overridden).
     *
     * Example: return new String[]{"python3", "/opt/nodes/csv_stats.py"};
     */
    protected String[] getExecutorCommand() {
        return null;
    }

    // -----------------------------------------------------------------------
    // State
    // -----------------------------------------------------------------------

    /** One thread per concurrent inbound request. */
    private final ExecutorService workerPool = Executors.newCachedThreadPool();

    /** Lifecycle flag — set to false by shutdown(). */
    private final AtomicBoolean running = new AtomicBoolean(true);

    // -----------------------------------------------------------------------
    // Entry point
    // -----------------------------------------------------------------------

    @Override
    public final void run() {
        // HeartbeatPulse — daemon so it never blocks JVM exit.
        Thread pulseThread = new Thread(this::heartbeatPulseLoop,
                "HeartbeatPulse-" + getService().name());
        pulseThread.setDaemon(true);
        pulseThread.start();
        System.out.printf("[%s] HeartbeatPulse started → UDP %s:%d every %dms%n",
                getService(), SERVER_HOST, HEARTBEAT_UDP_PORT, HEARTBEAT_INTERVAL_MS);

        // ServiceListener — blocks on this thread.
        serviceListenerLoop();
    }

    // -----------------------------------------------------------------------
    // 1. HeartbeatPulse
    // -----------------------------------------------------------------------

    /**
     * Sends a UDP datagram to HeartbeatMonitor on a fixed interval.
     * Packet format matches HeartbeatMonitor.parseAndRegister() exactly:
     *   "<nodeId>,<tcpPort>,<serviceName>"   e.g. "2,5102,BASE64_ENCODE_DECODE"
     */
    private void heartbeatPulseLoop() {
        try (DatagramSocket socket = new DatagramSocket()) {
            InetAddress serverAddr = InetAddress.getByName(SERVER_HOST);
            byte[] data = (getNodeId() + "," + SERVICE_PORT + "," + getService().name()).getBytes();
            System.out.printf("[%s] Heartbeat payload: \"%s\"%n",
                    getService(), new String(data));

            while (running.get()) {
                socket.send(new DatagramPacket(data, data.length, serverAddr, HEARTBEAT_UDP_PORT));
                Thread.sleep(HEARTBEAT_INTERVAL_MS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException e) {
            System.err.printf("[%s] HeartbeatPulse error: %s%n", getService(), e.getMessage());
        }
    }

    // -----------------------------------------------------------------------
    // 2. ServiceListener
    // -----------------------------------------------------------------------

    /**
     * Accepts TCP connections forwarded by Pipe and dispatches each to
     * the worker pool so the accept loop is never blocked.
     */
    private void serviceListenerLoop() {
        try (ServerSocket serverSocket = new ServerSocket(SERVICE_PORT)) {
            System.out.printf("[%s] ServiceListener ready on TCP port %d%n",
                    getService(), SERVICE_PORT);

            while (running.get() && !Thread.currentThread().isInterrupted()) {
                Socket pipeSocket = serverSocket.accept();
                System.out.printf("[%s] Connection from Pipe at %s%n",
                        getService(), pipeSocket.getRemoteSocketAddress());
                workerPool.submit(() -> dispatchRequest(pipeSocket));
            }
        } catch (IOException e) {
            if (running.get()) {
                System.err.printf("[%s] ServiceListener error: %s%n", getService(), e.getMessage());
            }
        }
    }

    // -----------------------------------------------------------------------
    // 3. Request dispatcher
    // -----------------------------------------------------------------------

    /**
     * Handles one Pipe connection end-to-end:
     *   a) Read full JSON payload (Pipe calls shutdownOutput, so readAllBytes terminates).
     *   b) Delegate to handleRequest(payload) — Pattern A or B depending on subclass.
     *   c) Write result bytes back to Pipe.
     */
    private void dispatchRequest(Socket pipeSocket) {
        try (Socket ps = pipeSocket) {
            byte[] payload = ps.getInputStream().readAllBytes();
            System.out.printf("[%s] Received payload (%d bytes)%n", getService(), payload.length);

            if (payload.length == 0) {
                ps.getOutputStream().write(
                    "{\"status\":\"error\",\"message\":\"Empty payload\"}".getBytes());
                ps.getOutputStream().flush();
                return;
            }

            byte[] result;
            try {
                result = handleRequest(payload);
            } catch (Throwable e) {
                System.err.printf("[%s] dispatchRequest error: %s%n", getService(), e.getMessage());
                String msg = e.getMessage() != null ? e.getMessage().replace("\"", "'") : e.getClass().getSimpleName();
                result = ("{\"status\":\"error\",\"message\":\"" + msg + "\"}").getBytes();
            }

            ps.getOutputStream().write(result);
            ps.getOutputStream().flush();
            System.out.printf("[%s] Sent result (%d bytes)%n", getService(), result.length);

        } catch (Exception e) {
            System.err.printf("[%s] dispatchRequest error: %s%n", getService(), e.getMessage());
        }
    }

    // -----------------------------------------------------------------------
    // 4. Subprocess runner (Pattern B only)
    // -----------------------------------------------------------------------

    /**
     * Spawns the given command, writes payload to STDIN, reads STDOUT as result.
     * Called only by the default handleRequest() when a subclass uses Pattern B.
     */
    private byte[] runSubprocess(String[] cmd, byte[] payload) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Process process = pb.start();

        try (OutputStream stdin = process.getOutputStream()) {
            stdin.write(payload);
        }

        byte[] result;
        try (InputStream stdout = process.getInputStream()) {
            result = stdout.readAllBytes();
        }

        boolean finished = process.waitFor(EXECUTOR_TIMEOUT_SEC, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new TimeoutException("Executor timed out after " + EXECUTOR_TIMEOUT_SEC + "s");
        }

        System.out.printf("[%s] Subprocess exited (code %d), %d result bytes%n",
                getService(), process.exitValue(), result.length);
        return result;
    }

    // -----------------------------------------------------------------------
    // Lifecycle
    // -----------------------------------------------------------------------

    /** Stops the heartbeat and service listener, drains the worker pool. */
    public void shutdown() {
        running.set(false);
        workerPool.shutdownNow();
    }
}