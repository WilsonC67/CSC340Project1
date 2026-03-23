package Services.Compression;

import java.io.*;
import java.util.zip.*;

public class CompressionService {
    private static final int BUFFER_SIZE = 8192;
    private static final int CHUNK_SIZE  = 64 * 1024; // 64 KB per chunk / ZIP entry

    public void compress(InputStream input, OutputStream output, String entryName) throws IOException {
        if (input == null || output == null) {
            throw new IllegalArgumentException("Input and output streams must not be null");
        }

        try (ZipOutputStream zip = new ZipOutputStream(output)) {
            byte[] chunk = new byte[CHUNK_SIZE];
            int chunkIndex = 0;
            int totalRead;
            while ((totalRead = readFully(input, chunk)) > 0) {
                zip.putNextEntry(new ZipEntry(entryName + ".part" + chunkIndex++));
                zip.write(chunk, 0, totalRead);
                zip.closeEntry();
            }
            // Preserve a valid ZIP for empty input
            if (chunkIndex == 0) {
                zip.putNextEntry(new ZipEntry(entryName + ".part0"));
                zip.closeEntry();
            }
        }
    }

    public void decompress(InputStream compressedInput, OutputStream output) throws IOException {
        if (compressedInput == null || output == null) {
            throw new IllegalArgumentException("Input and output streams must not be null");
        }

        try (ZipInputStream zip = new ZipInputStream(compressedInput)) {
            byte[] buffer = new byte[BUFFER_SIZE];
            int count;
            boolean hasEntry = false;
            while (zip.getNextEntry() != null) {
                hasEntry = true;
                while ((count = zip.read(buffer)) != -1) {
                    output.write(buffer, 0, count);
                }
            }
            if (!hasEntry) {
                throw new IOException("No entries found in ZIP data");
            }
        }
    }

    /** Reads up to buf.length bytes, filling the buffer as much as the stream allows. */
    private static int readFully(InputStream in, byte[] buf) throws IOException {
        int total = 0;
        while (total < buf.length) {
            int n = in.read(buf, total, buf.length - total);
            if (n == -1) break;
            total += n;
        }
        return total;
    }
}