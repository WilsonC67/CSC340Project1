package Services.Base64;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

// standard Base64 encoder and decoder using predefined Java libraries
public class Base64Service {

    // instances of the encoder + decoder

    private static final Base64.Encoder encoder = Base64.getEncoder();
    private static final Base64.Decoder decoder = Base64.getDecoder();

    // takes byte array as input for the encoder
    public static byte[] encode(byte[] input) {
        return encoder.encode(input);
    }

    public static byte[] decode(byte[] input) {
        return decoder.decode(input);
    }

    // UTF-8 translates numbers to binary. First transforms the string into bytes using UTF-8
    // then encodes it into Base64
    public static String encodeToString(String input) {
        return encoder.encodeToString(input.getBytes(StandardCharsets.UTF_8));
    }

    // decodes the encoded string input using the UTF-8 encoding standard
    // returns decoded string
    public static String decodeToString(String input) {        
        return new String(decoder.decode(input), StandardCharsets.UTF_8);
    }

    public static void main(String[] args) {
        // tests for strings
        String encodeTest1 = "And";
        System.out.println(encodeToString(encodeTest1));

        String decodeTest1 = "QW5k";
        System.out.println(decodeToString(decodeTest1));

        byte[] input = "Base64 Test".getBytes(StandardCharsets.UTF_8);

        byte[] encoded = encode(input);
        System.out.println(new String(encoded, StandardCharsets.US_ASCII));
        // expected: QmFzZTY0IFRlc3Q=

        byte[] decoded = decode(encoded);
        System.out.println(new String(decoded, StandardCharsets.UTF_8));
        // expected: Base64 Test
    }
}