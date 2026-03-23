package Source;

import java.time.Duration;
import java.time.Instant;

public class NodeInfo {
    public static final long DEAD_THRESHOLD_MS = 60_000;

    private final int nodeId;
    private final String ip;
    private final int tcpPort;
    private Service service;
    private volatile Instant lastHeartbeat;
    private volatile boolean alive;

    public NodeInfo(int nodeId, String ip, int tcpPort, Service service) {
        this.nodeId = nodeId; this.ip = ip; this.tcpPort = tcpPort;
        this.service = service; this.lastHeartbeat = Instant.now(); this.alive = true;
    }

    public synchronized void updateHeartbeat(Service service) {
        this.service = service; this.lastHeartbeat = Instant.now(); this.alive = true;
    }

    public long getSecondsSinceLastContact() {
        return Duration.between(lastHeartbeat, Instant.now()).getSeconds();
    }

    public synchronized void markDead() { this.alive = false; }

    public int     getNodeId()  { return nodeId; }
    public String  getIp()      { return ip; }
    public int     getTcpPort() { return tcpPort; }
    public Service getService() { return service; }
    public boolean isAlive()    { return alive; }

    @Override
    public String toString() {
        return String.format("NodeInfo[id=%d, ip=%s, port=%d, service=%s, alive=%b]",
                nodeId, ip, tcpPort, service, alive);
    }
}
