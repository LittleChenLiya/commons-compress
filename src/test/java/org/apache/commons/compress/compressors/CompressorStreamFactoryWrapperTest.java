/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.commons.compress.compressors;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.apache.commons.compress.compressors.CompressorStreamFactoryWrapper.ClosePolicy;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;
import org.apache.commons.io.IOUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Tests for {@link CompressorStreamFactoryWrapper}.
 */
class CompressorStreamFactoryWrapperTest {

    private static final String PLAIN_TEXT = "The quick brown fox jumps over the lazy dog";

    /** A small gzip fixture: the gzip-compressed form of {@link #PLAIN_TEXT}. */
    private static final byte[] GZIP_BYTES = gzip(PLAIN_TEXT);

    private static byte[] gzip(final String text) {
        final ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (GzipCompressorOutputStream gos = new GzipCompressorOutputStream(bos)) {
            gos.write(text.getBytes(StandardCharsets.UTF_8));
        } catch (final IOException e) {
            throw new IllegalStateException(e);
        }
        return bos.toByteArray();
    }

    /**
     * An input stream that forbids mark/reset and tracks how many bytes were read and whether it was closed.
     */
    private static final class NonMarkableInputStream extends InputStream {

        private final byte[] data;
        private int position;
        private int bytesRead;
        private boolean closed;

        NonMarkableInputStream(final byte[] data) {
            this.data = data.clone();
        }

        @Override
        public int available() {
            return data.length - position;
        }

        @Override
        public void close() {
            closed = true;
        }

        int getBytesRead() {
            return bytesRead;
        }

        @Override
        public void mark(final int readlimit) {
            throw new UnsupportedOperationException("mark is not supported");
        }

        @Override
        public boolean markSupported() {
            return false;
        }

        @Override
        public int read() {
            if (position >= data.length) {
                return -1;
            }
            bytesRead++;
            return data[position++] & 0xFF;
        }

        @Override
        public int read(final byte[] b, final int off, final int len) {
            if (off < 0 || len < 0 || off + len > b.length) {
                throw new IndexOutOfBoundsException();
            }
            if (len == 0) {
                return 0;
            }
            if (position >= data.length) {
                return -1;
            }
            final int count = Math.min(len, data.length - position);
            System.arraycopy(data, position, b, off, count);
            position += count;
            bytesRead += count;
            return count;
        }

        @Override
        public void reset() throws IOException {
            throw new IOException("mark is not supported");
        }

        boolean isClosed() {
            return closed;
        }
    }

    private final CompressorStreamFactoryWrapper wrapper = new CompressorStreamFactoryWrapper();

    @Test
    void testBoundedDetectionReadsOnlyPrefix() throws Exception {
        final NonMarkableInputStream in = new NonMarkableInputStream(GZIP_BYTES);
        try (CompressorInputStream cin = wrapper.createCompressorInputStream(in)) {
            // Only the bounded prefix must have been read from the underlying stream for detection.
            assertEquals(CompressorStreamFactoryWrapper.DEFAULT_SIGNATURE_LENGTH, in.getBytesRead(),
                    "only the bounded prefix should be read for detection");
            // Decompression starts at the original beginning.
            final byte[] result = IOUtils.toByteArray(cin);
            assertArrayEquals(PLAIN_TEXT.getBytes(StandardCharsets.UTF_8), result);
        }
    }

    @Test
    void testClosePolicyClosesUnderlying() throws Exception {
        final NonMarkableInputStream in = new NonMarkableInputStream(GZIP_BYTES);
        final CompressorStreamFactoryWrapper closingWrapper = new CompressorStreamFactoryWrapper(new CompressorStreamFactory(),
                ClosePolicy.CLOSE_UNDERLYING);
        try (CompressorInputStream cin = closingWrapper.createCompressorInputStream(in)) {
            assertFalse(in.isClosed());
            IOUtils.toByteArray(cin);
        }
        assertTrue(in.isClosed(), "CLOSE_UNDERLYING must close the borrowed underlying stream");
    }

    @Test
    void testClosePolicyKeepsUnderlyingOpen() throws Exception {
        final NonMarkableInputStream in = new NonMarkableInputStream(GZIP_BYTES);
        final CompressorStreamFactoryWrapper nonClosingWrapper = new CompressorStreamFactoryWrapper(new CompressorStreamFactory(),
                ClosePolicy.KEEP_UNDERLYING_OPEN);
        try (CompressorInputStream cin = nonClosingWrapper.createCompressorInputStream(in)) {
            IOUtils.toByteArray(cin);
        }
        assertFalse(in.isClosed(), "KEEP_UNDERLYING_OPEN must not close the borrowed underlying stream");
        // The caller remains responsible for closing the underlying stream.
        in.close();
        assertTrue(in.isClosed());
    }

    @Test
    void testCorruptGzipHeaderClassifiedAsCorrupt() {
        // Gzip magic followed by a reserved-flag byte: the signature matches gzip but the header is corrupt.
        final byte[] corruptHeader = { (byte) 0x1F, (byte) 0x8B, 0x08, (byte) 0xFF, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00 };
        final NonMarkableInputStream in = new NonMarkableInputStream(corruptHeader);
        final CompressorException e = assertThrows(CompressorException.class, () -> wrapper.createCompressorInputStream(in));
        final String message = e.getMessage();
        assertTrue(message.contains("corrupt"), "corrupt gzip header must be reported as corrupt, got: " + message);
        assertFalse(message.contains("No Compressor found"), "must not be reported as an unknown format");
    }

    @Test
    void testDefaultConstructorClosesUnderlying() throws Exception {
        final NonMarkableInputStream in = new NonMarkableInputStream(GZIP_BYTES);
        try (CompressorInputStream cin = wrapper.createCompressorInputStream(in)) {
            IOUtils.toByteArray(cin);
        }
        assertTrue(in.isClosed(), "the default close policy should close the underlying stream");
    }

    @Test
    void testEmptyStreamPlayback() throws Exception {
        final NonMarkableInputStream in = new NonMarkableInputStream(new byte[0]);
        try (CompressorInputStream cin = wrapper.createCompressorInputStream(in)) {
            assertEquals(-1, cin.read());
        }
    }

    @Test
    void testInvalidSignatureLength() {
        assertThrows(IllegalArgumentException.class,
                () -> new CompressorStreamFactoryWrapper(new CompressorStreamFactory(), ClosePolicy.CLOSE_UNDERLYING, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new CompressorStreamFactoryWrapper(new CompressorStreamFactory(), ClosePolicy.CLOSE_UNDERLYING, -1));
    }

    @Test
    void testNonMarkableGzipDecompresses() throws Exception {
        final NonMarkableInputStream in = new NonMarkableInputStream(GZIP_BYTES);
        assertFalse(in.markSupported());
        try (CompressorInputStream cin = wrapper.createCompressorInputStream(in)) {
            final byte[] result = IOUtils.toByteArray(cin);
            assertArrayEquals(PLAIN_TEXT.getBytes(StandardCharsets.UTF_8), result);
        }
    }

    @Test
    void testNullFactoryThrows() {
        assertThrows(NullPointerException.class, () -> new CompressorStreamFactoryWrapper(null, ClosePolicy.CLOSE_UNDERLYING));
    }

    @Test
    void testNullStreamThrows() {
        assertThrows(NullPointerException.class, () -> wrapper.createCompressorInputStream(null));
    }

    @Test
    void testPlaybackReadsFromStart() throws Exception {
        final byte[] plain = "playback reads from the original start".getBytes(StandardCharsets.UTF_8);
        final NonMarkableInputStream in = new NonMarkableInputStream(plain);
        try (CompressorInputStream cin = wrapper.createCompressorInputStream(in)) {
            assertInstanceOf(PlaybackCompressorInputStream.class, cin,
                    "unknown formats must be returned as a playback pass-through stream");
            // Read in small chunks to exercise the prefix/tail boundary.
            final ByteArrayOutputStream bos = new ByteArrayOutputStream();
            final byte[] buffer = new byte[7];
            int n;
            while ((n = cin.read(buffer)) != -1) {
                bos.write(buffer, 0, n);
            }
            assertArrayEquals(plain, bos.toByteArray(), "the playback stream must return every byte from the original start");
        }
    }

    @ParameterizedTest
    @ValueSource(ints = { 0, 1, 2, 3, 11, 12, 13, 20, 100 })
    void testShortInputPlayback(final int length) throws Exception {
        final byte[] plain = new byte[length];
        for (int i = 0; i < length; i++) {
            plain[i] = (byte) (65 + i % 26);
        }
        final NonMarkableInputStream in = new NonMarkableInputStream(plain);
        try (CompressorInputStream cin = wrapper.createCompressorInputStream(in)) {
            final byte[] result = IOUtils.toByteArray(cin);
            assertArrayEquals(plain, result, "a short plain stream of length " + length + " must be played back in full");
        }
    }

    @Test
    void testTruncatedGzipHeaderClassifiedAsCorrupt() {
        // Only the two gzip magic bytes: detection succeeds, but the header is truncated and thus corrupt.
        final byte[] truncated = { (byte) 0x1F, (byte) 0x8B };
        final NonMarkableInputStream in = new NonMarkableInputStream(truncated);
        final CompressorException e = assertThrows(CompressorException.class, () -> wrapper.createCompressorInputStream(in));
        final String message = e.getMessage();
        assertTrue(message.contains("corrupt"), "truncated gzip header must be reported as corrupt, got: " + message);
    }

    @Test
    void testUnderlyingNotReadBeyondPrefixOnFailedDetection() throws Exception {
        final byte[] plain = "not a compressed stream at all".getBytes(StandardCharsets.UTF_8);
        final NonMarkableInputStream in = new NonMarkableInputStream(plain);
        try (CompressorInputStream cin = wrapper.createCompressorInputStream(in)) {
            // The prefix was read for detection; the rest must be available through the playback stream.
            assertEquals(Math.min(CompressorStreamFactoryWrapper.DEFAULT_SIGNATURE_LENGTH, plain.length), in.getBytesRead());
            final byte[] result = IOUtils.toByteArray(cin);
            assertArrayEquals(plain, result);
        }
    }

    @Test
    void testUnknownFormatReturnsPlayback() throws Exception {
        final byte[] plain = "this is plain text, not a compressed stream".getBytes(StandardCharsets.UTF_8);
        final NonMarkableInputStream in = new NonMarkableInputStream(plain);
        try (CompressorInputStream cin = wrapper.createCompressorInputStream(in)) {
            assertInstanceOf(PlaybackCompressorInputStream.class, cin);
            final byte[] result = IOUtils.toByteArray(cin);
            assertArrayEquals(plain, result);
        }
    }
}
