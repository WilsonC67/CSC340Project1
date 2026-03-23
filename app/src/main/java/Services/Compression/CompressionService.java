package Services.Compression;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

public class CompressionService {
    private static final int BUFFER_SIZE = 8192;

    public void compress(InputStream input, OutputStream output, String entryName) throws IOException {
        if (input == null || output == null) {
            throw new IllegalArgumentException("Input and output streams must not be null");
        }

        try (ZipOutputStream zip = new ZipOutputStream(output)) {
            zip.putNextEntry(new ZipEntry(entryName));
            byte[] buffer = new byte[BUFFER_SIZE];
            int count;
            while ((count = input.read(buffer)) != -1) {
                zip.write(buffer, 0, count);
            }
            zip.closeEntry();
        }
    }

    public void decompress(InputStream compressedInput, OutputStream output) throws IOException {
        if (compressedInput == null || output == null) {
            throw new IllegalArgumentException("Input and output streams must not be null");
        }

        try (ZipInputStream zip = new ZipInputStream(compressedInput)) {
            ZipEntry entry = zip.getNextEntry();
            if (entry == null) {
                throw new IOException("No entries found in ZIP data");
            }
            byte[] buffer = new byte[BUFFER_SIZE];
            int count;
            while ((count = zip.read(buffer)) != -1) {
                output.write(buffer, 0, count);
            }
        }
    }
}