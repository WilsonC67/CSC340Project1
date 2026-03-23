package Source;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

public class NodeRegistry {

    private final ConcurrentHashMap<Integer, NodeInfo> nodes = new ConcurrentHashMap<>();

    public void heartbeat(int nodeId, String ip, int tcpPort, Service service) {
        NodeInfo existing = nodes.get(nodeId);
        if (existing != null) {
            existing.updateHeartbeat(service);
            System.out.printf("[Registry] Heartbeat refreshed: node %d (%s)%n", nodeId, service);
        } else {
            NodeInfo info = new NodeInfo(nodeId, ip, tcpPort, service);
            nodes.put(nodeId, info);
            System.out.printf("[Registry] New node registered: %s%n", info);
        }
    }

    public void sweepDeadNodes() {
        for (NodeInfo info : nodes.values()) {
            if (info.isAlive() && info.getSecondsSinceLastContact() * 1000 > NodeInfo.DEAD_THRESHOLD_MS) {
                info.markDead();
                System.out.printf("[Registry] Node %d marked DEAD (silent for %ds)%n",
                        info.getNodeId(), info.getSecondsSinceLastContact());
            }
        }
    }

    public NodeInfo findAliveNode(Service service) {
        for (NodeInfo info : nodes.values()) {
            if (info.isAlive() && info.getService() == service) return info;
        }
        return null;
    }

    public List<Service> getAvailableServices() {
        List<Service> available = new ArrayList<>();
        for (NodeInfo info : nodes.values()) {
            if (info.isAlive() && !available.contains(info.getService()))
                available.add(info.getService());
        }
        return available;
    }

    public List<NodeInfo> getAllNodes() {
        return new ArrayList<>(nodes.values());
    }
}
