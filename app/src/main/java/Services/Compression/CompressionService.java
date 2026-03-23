package Services.Compression;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

public class CompressionService {
    private static final int BUFFER_SIZE = 8192;
    private static final int CHUNK_SIZE  = 8 * 1024 * 1024; // 8 MB per chunk entry

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

    /**
     * Compresses {@code input} in fixed-size chunks. Each chunk is stored as a
     * separate ZIP entry named {@code "<entryName>.chunkN"}, so the compressor
     * never needs more than one chunk in memory at a time.
     */
    public void compressChunked(InputStream input, OutputStream output, String entryName) throws IOException {
        if (input == null || output == null) {
            throw new IllegalArgumentException("Input and output streams must not be null");
        }

        try (ZipOutputStream zip = new ZipOutputStream(output)) {
            byte[] buffer = new byte[CHUNK_SIZE];
            int chunkIndex = 0;
            int bytesRead;
            while ((bytesRead = input.read(buffer)) != -1) {
                zip.putNextEntry(new ZipEntry(entryName + ".chunk" + chunkIndex++));
                zip.write(buffer, 0, bytesRead);
                zip.closeEntry();
            }
            if (chunkIndex == 0) {
                // Empty input — write one empty entry so the ZIP is valid
                zip.putNextEntry(new ZipEntry(entryName + ".chunk0"));
                zip.closeEntry();
            }
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

    /**
     * Decompresses a ZIP produced by {@link #compressChunked}. Iterates over
     * every entry in order and writes each entry's bytes directly to {@code output}.
     */
    public void decompressChunked(InputStream compressedInput, OutputStream output) throws IOException {
        if (compressedInput == null || output == null) {
            throw new IllegalArgumentException("Input and output streams must not be null");
        }

        try (ZipInputStream zip = new ZipInputStream(compressedInput)) {
            byte[] buffer = new byte[BUFFER_SIZE];
            boolean foundAny = false;
            while (zip.getNextEntry() != null) {
                foundAny = true;
                int count;
                while ((count = zip.read(buffer)) != -1) {
                    output.write(buffer, 0, count);
                }
                zip.closeEntry();
            }
            if (!foundAny) {
                throw new IOException("No entries found in ZIP data");
            }
        }
    }
}
