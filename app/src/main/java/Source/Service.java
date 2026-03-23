package Source;

public enum Service {
    N_BODY_GRAVITATIONAL_STEPPER,   // 1
    BASE64_ENCODE_DECODE,           // 2
    COMPRESSION_DECOMPRESSION,      // 3
    CSV_STATS,                      // 4
    IMAGE_TO_ASCII;                 // 5

    public static Service fromString(String s) {
        if (s == null) return null;
        try { return Service.valueOf(s.trim().toUpperCase()); }
        catch (IllegalArgumentException e) { return null; }
    }
}
