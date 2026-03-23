package Services.ImageToAscii;

import java.nio.charset.StandardCharsets;

import Source.Node;
import Source.Service;


public class ImageToAsciiNode  extends Node{
    private static final int NODE_ID = 5;

    @Override
    protected int getNodeId(){
        return NODE_ID;
    }

    @Override
    protected Service getService(){
        return Service.IMAGE_TO_ASCII;
    }

    @Override
    protected byte[] handleRequest(byte[] payload) {
        String json = new String(payload, StandardCharsets.UTF_8);
        String base64Data = extractJsonString(json, "base64");
        if (base64Data == null) {
            return error("Missing \"base64\" field.");
        }

        // Pass the raw Base64 bytes directly — let ImageToAsciiService do the decoding
        byte[] asciiBytes = ImageToAsciiService.convert_to_ascii(base64Data.getBytes(StandardCharsets.UTF_8));
        String asciiResult = new String(asciiBytes, StandardCharsets.UTF_8);
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
        String filename = "ascii.txt";
        String escResult   = result.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
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
        System.out.println("[ImageToAsciiNode] Starting...");
        new ImageToAsciiNode().run();
    }
    


}
