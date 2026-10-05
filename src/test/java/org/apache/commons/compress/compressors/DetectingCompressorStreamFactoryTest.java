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
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.commons.compress.compressors;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPOutputStream;

import org.apache.commons.io.IOUtils;
import org.junit.jupiter.api.Test;

class DetectingCompressorStreamFactoryTest {

    /**
     * An input stream that forbids mark/reset and counts close() calls and the number of bytes read from it.
     */
    private static final class CountingInputStream extends InputStream {

        private final InputStream delegate;

        private int closeCount;

        private long readCount;

        CountingInputStream(final byte[] bytes) {
            this.delegate = new ByteArrayInputStream(bytes);
        }

        @Override
        public int available() throws IOException {
            return delegate.available();
        }

        @Override
        public void close() throws IOException {
            closeCount++;
            delegate.close();
        }

        @Override
        public boolean markSupported() {
            return false;
        }

        @Override
        public int read() throws IOException {
            final int read = delegate.read();
            if (read >= 0) {
                readCount++;
            }
            return read;
        }

        @Override
        public int read(final byte[] b, final int off, final int len) throws IOException {
            final int read = delegate.read(b, off, len);
            if (read > 0) {
                readCount += read;
            }
            return read;
        }

    }

    private static final byte[] PAYLOAD = "Hello, markless world!".getBytes(StandardCharsets.UTF_8);

    private static byte[] gzip(final byte[] data) throws IOException {
        final ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(bos)) {
            gz.write(data);
        }
        return bos.toByteArray();
    }

    @Test
    void testCloseUnderlyingFalseKeepsBorrowedStreamOpen() throws Exception {
        final CountingInputStream in = new CountingInputStream(gzip(PAYLOAD));
        final DetectingCompressorStreamFactory factory = new DetectingCompressorStreamFactory(false);
        final CompressorInputStream cis = factory.createCompressorInputStream(in);
        assertArrayEquals(PAYLOAD, IOUtils.toByteArray(cis));
        cis.close();
        assertEquals(0, in.closeCount, "borrowed stream must not be closed");
    }

    @Test
    void testCloseUnderlyingTrueClosesBorrowedStreamExactlyOnce() throws Exception {
        final CountingInputStream in = new CountingInputStream(gzip(PAYLOAD));
        final DetectingCompressorStreamFactory factory = new DetectingCompressorStreamFactory(true);
        final CompressorInputStream cis = factory.createCompressorInputStream(in);
        assertArrayEquals(PAYLOAD, IOUtils.toByteArray(cis));
        cis.close();
        assertEquals(1, in.closeCount, "borrowed stream must be closed exactly once");
    }

    @Test
    void testCorruptedGzipHeaderIsReportedAsCorruptedNotUnknown() throws Exception {
        // Valid gzip magic, but compression method 9 is not DEFLATE(8): the header is corrupted.
        final byte[] corrupted = { 0x1f, (byte) 0x8b, 0x09, 0, 0, 0, 0, 0, 0, 0, 0, 0 };
        final DetectingCompressorStreamFactory factory = new DetectingCompressorStreamFactory(true);
        final DetectingCompressorStreamFactory.Detection detection = factory.detect(new CountingInputStream(corrupted));
        assertTrue(detection.isDetected());
        assertEquals(CompressorStreamFactory.GZIP, detection.getName());
        final CorruptedCompressorStreamException e = assertThrows(CorruptedCompressorStreamException.class, detection::createCompressorInputStream);
        assertEquals(CompressorStreamFactory.GZIP, e.getCompressorName());
    }

    @Test
    void testDetectionReadsOnlyBoundedPrefix() throws Exception {
        final byte[] bytes = new byte[100];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) i;
        }
        final CountingInputStream in = new CountingInputStream(bytes);
        final DetectingCompressorStreamFactory factory = new DetectingCompressorStreamFactory(true);
        final DetectingCompressorStreamFactory.Detection detection = factory.detect(in);
        assertFalse(detection.isDetected());
        assertEquals(12, in.readCount, "detection must read at most the signature length");
        assertArrayEquals(bytes, IOUtils.toByteArray(detection.getReplayStream()));
        assertEquals(bytes.length, in.readCount, "the replay stream must read the remainder from the borrowed stream");
    }

    @Test
    void testEmptyInputDetectsNothingAndReplaysEof() throws Exception {
        final DetectingCompressorStreamFactory factory = new DetectingCompressorStreamFactory(true);
        final DetectingCompressorStreamFactory.Detection detection = factory.detect(new CountingInputStream(new byte[0]));
        assertFalse(detection.isDetected());
        assertNull(detection.getName());
        assertEquals(-1, detection.getReplayStream().read());
    }

    @Test
    void testGzipWithoutMarkDecompressesFromOriginalStart() throws Exception {
        final byte[] gzipped = gzip(PAYLOAD);
        final CountingInputStream in = new CountingInputStream(gzipped);
        // The plain factory rejects streams without mark support.
        assertThrows(IllegalArgumentException.class, () -> CompressorStreamFactory.getSingleton().createCompressorInputStream(new CountingInputStream(gzipped)));
        final DetectingCompressorStreamFactory factory = new DetectingCompressorStreamFactory(true);
        final DetectingCompressorStreamFactory.Detection detection = factory.detect(in);
        assertTrue(detection.isDetected());
        assertEquals(CompressorStreamFactory.GZIP, detection.getName());
        try (CompressorInputStream cis = detection.createCompressorInputStream()) {
            assertArrayEquals(PAYLOAD, IOUtils.toByteArray(cis));
        }
    }

    @Test
    void testReplayClosePolicyIsIndependentFromDetection() throws Exception {
        final byte[] bytes = "plain bytes".getBytes(StandardCharsets.UTF_8);
        final CountingInputStream in = new CountingInputStream(bytes);
        final PrefixReplayInputStream replay = new DetectingCompressorStreamFactory(false).detect(in).getReplayStream();
        replay.close();
        replay.close();
        assertEquals(0, in.closeCount);
        final CountingInputStream in2 = new CountingInputStream(bytes);
        final PrefixReplayInputStream replay2 = new DetectingCompressorStreamFactory(true).detect(in2).getReplayStream();
        replay2.close();
        replay2.close();
        assertEquals(1, in2.closeCount);
    }

    @Test
    void testShortGzipMagicOnlyIsCorrupted() throws Exception {
        // Only the two magic bytes: detected as gzip, but the truncated header cannot be parsed.
        final byte[] truncated = { 0x1f, (byte) 0x8b };
        final DetectingCompressorStreamFactory factory = new DetectingCompressorStreamFactory(true);
        final DetectingCompressorStreamFactory.Detection detection = factory.detect(new CountingInputStream(truncated));
        assertTrue(detection.isDetected());
        assertEquals(CompressorStreamFactory.GZIP, detection.getName());
        assertThrows(CorruptedCompressorStreamException.class, detection::createCompressorInputStream);
    }

    @Test
    void testSingleByteInputIsNotDetectedAndReplaysThatByte() throws Exception {
        final DetectingCompressorStreamFactory factory = new DetectingCompressorStreamFactory(true);
        final DetectingCompressorStreamFactory.Detection detection = factory.detect(new CountingInputStream(new byte[] { 0x1f }));
        assertFalse(detection.isDetected());
        final PrefixReplayInputStream replay = detection.getReplayStream();
        assertEquals(0x1f, replay.read());
        assertEquals(-1, replay.read());
    }

    @Test
    void testUnknownFormatReplayReturnsAllOriginalBytes() throws Exception {
        final byte[] plain = "this is not a compressed stream at all".getBytes(StandardCharsets.UTF_8);
        final CountingInputStream in = new CountingInputStream(plain);
        final DetectingCompressorStreamFactory factory = new DetectingCompressorStreamFactory(true);
        final DetectingCompressorStreamFactory.Detection detection = factory.detect(in);
        assertFalse(detection.isDetected());
        assertNull(detection.getName());
        // Detection failure must not lose the bytes already read: the replay stream returns everything.
        assertArrayEquals(plain, IOUtils.toByteArray(detection.getReplayStream()));
        // Asking for a compressor stream on an unknown format reports an unknown format, not corruption.
        final DetectingCompressorStreamFactory.Detection again = factory.detect(new CountingInputStream(plain));
        final CompressorException e = assertThrows(CompressorException.class, again::createCompressorInputStream);
        assertFalse(e instanceof CorruptedCompressorStreamException);
    }

}
