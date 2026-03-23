package Services.CSVStats;

import java.nio.charset.StandardCharsets;

import Source.Node;
import Source.Service;

public class CSVStatsNode  extends Node{
    private static final int NODE_ID = 4;

    @Override
    protected int getNodeId(){
        return NODE_ID;
    }

    @Override
    protected Service getService(){
        return Service.CSV_STATS;
    }

    @Override
    protected byte[] handleRequest(byte[] payload) {
        String json = new String(payload, StandardCharsets.UTF_8);

        String base64Data = extractJsonString(json, "base64");

        if (base64Data == null) {
            return error("Missing \"base64\" field.");
        }

        byte[] csv_output = CSVStatsService.get_csv_stats(base64Data.getBytes(StandardCharsets.UTF_8));
        String asciiResult = new String(csv_output, StandardCharsets.UTF_8);

        return success(asciiResult);

    }

    private static String extractJsonString(String json, String key) {
        String needle = "\"" + key + "\":\"";
        int pos = json.indexOf(needle);
        if (pos < 0) return null;
        pos += needle.length();
        StringBuilder sb = new StringBuilder();
        while (pos < json.length()) {
            char c = json.charAt(pos++);
            if (c == '"') return sb.toString();
            if (c == '\\' && pos < json.length()) c = json.charAt(pos++);
            sb.append(c);
        }
        return null;
    }

    private static byte[] success(String result) {
        String filename = "CSVStats.csv";
        String escResult   = result.replace("\\", "\\\\").replace("\"", "\\\"");
        String escFilename = filename.replace("\\", "\\\\").replace("\"", "\\\"");
        return ("{\"status\":\"ok\",\"result\":\"" + escResult
                + "\",\"filename\":\"" + escFilename + "\"}")
                .getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] error(String message) {
        String escaped = message.replace("\\", "\\\\").replace("\"", "\\\"");
        return ("{\"status\":\"error\",\"message\":\"" + escaped + "\"}")
                .getBytes(StandardCharsets.UTF_8);
    }

    public static void main(String[] args) {
        System.out.println("[CSV STATS Node] Starting...");
        new CSVStatsNode().run();
    }
    


}
