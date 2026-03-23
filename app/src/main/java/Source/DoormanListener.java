package Source;

import java.net.ServerSocket;
import java.net.Socket;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * DoormanListener
 *
 * The front door of the server. Runs on TCP port 5101.
 * Accepts incoming client connections in a loop and spawns a new
 * Pipe thread for each one, assigning each client a unique integer ID.
 *
 * The main server thread is never blocked — it always returns to listening
 * immediately after handing the connection off to a Pipe.
 */
public class DoormanListener implements Runnable {

    public static final int TCP_PORT = 5101;

    private final NodeRegistry    registry;
    private final Queue<Integer>  idPool   = new ConcurrentLinkedQueue<>();
    private final AtomicInteger   nextId   = new AtomicInteger(1);

    public DoormanListener(NodeRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void run() {
        try (ServerSocket serverSocket = new ServerSocket(TCP_PORT)) {
            System.out.printf("[DoormanListener] Accepting TCP connections on port %d%n", TCP_PORT);

            while (!Thread.currentThread().isInterrupted()) {
                // Blocks here until a client connects.
                Socket clientSocket = serverSocket.accept();

                int clientId = assignId();
                System.out.printf("[DoormanListener] New client connected from %s — assigned id=%d%n",
                        clientSocket.getRemoteSocketAddress(), clientId);

                // Spawn a Pipe thread to service this client.
                // The release callback returns the ID to the pool when the Pipe finishes.
                Pipe pipe = new Pipe(clientSocket, clientId, registry, () -> releaseId(clientId));
                Thread pipeThread = new Thread(pipe, "Pipe-" + clientId);
                pipeThread.setDaemon(true);
                pipeThread.start();
            }
        } catch (Exception e) {
            System.err.println("[DoormanListener] Error: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /** Returns a recycled ID from the pool, or mints a new one if the pool is empty. */
    private int assignId() {
        Integer recycled = idPool.poll();
        return recycled != null ? recycled : nextId.getAndIncrement();
    }

    /** Called by a Pipe when it finishes — returns the ID to the pool for reuse. */
    private void releaseId(int id) {
        idPool.offer(id);
    }
}
