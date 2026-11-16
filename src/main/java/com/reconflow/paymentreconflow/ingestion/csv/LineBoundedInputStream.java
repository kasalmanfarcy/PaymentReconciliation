package com.reconflow.paymentreconflow.ingestion.csv;

import java.io.IOException;
import java.io.InputStream;

/**
 * Hands bytes to the UTF-8 decoder at most one line ({@code \n}) per read, and never claims more
 * are "available". Why: {@code InputStreamReader} decodes in chunks of about 8 KB, so with a strict
 * decoder an invalid byte on line 900 would fail while the parser is still on line 10, and the
 * error would name the wrong row; at upload, a bad byte anywhere in the first 8 KB would be blamed
 * on the header. Decoding a line at a time makes the error surface exactly when the parser reaches
 * the record that contains it. Splitting at {@code \n} is safe: in UTF-8 that byte never occurs
 * inside a multi-byte character.
 *
 * <p>Buffered internally, so the per-line rule costs no extra system calls.
 */
final class LineBoundedInputStream extends InputStream {

    private static final int BUFFER = 64 * 1024;

    private final InputStream in;
    private final byte[] buffer = new byte[BUFFER];
    private int position;
    private int limit;

    LineBoundedInputStream(InputStream in) {
        this.in = in;
    }

    @Override
    public int read() throws IOException {
        if (!fill()) {
            return -1;
        }
        return buffer[position++] & 0xFF;
    }

    @Override
    public int read(byte[] target, int offset, int length) throws IOException {
        if (length == 0) {
            return 0;
        }
        if (!fill()) {
            return -1;
        }
        int n = 0;
        while (n < length && position < limit) {
            byte b = buffer[position++];
            target[offset + n++] = b;
            if (b == '\n') {
                break; // one line per read
            }
        }
        return n;
    }

    /** Always 0, so the decoder returns what it has instead of reading ahead. */
    @Override
    public int available() {
        return 0;
    }

    @Override
    public void close() throws IOException {
        in.close();
    }

    private boolean fill() throws IOException {
        if (position < limit) {
            return true;
        }
        limit = in.read(buffer, 0, buffer.length);
        position = 0;
        if (limit <= 0) {
            limit = 0;
            return false;
        }
        return true;
    }
}
