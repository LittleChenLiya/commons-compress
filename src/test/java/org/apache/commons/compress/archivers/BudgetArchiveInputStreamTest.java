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
package org.apache.commons.compress.archivers;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;

import org.apache.commons.compress.compressors.CompressorException;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;
import org.junit.jupiter.api.Test;

/**
 * Tests {@link BudgetArchiveInputStream} with small, deterministic ZIP and TAR fixtures.
 */
class BudgetArchiveInputStreamTest {

    private static byte[] content(final int size, final int seed) {
        final byte[] b = new byte[size];
        for (int i = 0; i < size; i++) {
            b[i] = (byte) ('A' + (i + seed) % 26);
        }
        return b;
    }

    private static byte[] createTar(final int... sizes) throws IOException {
        final ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (TarArchiveOutputStream tos = new TarArchiveOutputStream(baos)) {
            for (int i = 0; i < sizes.length; i++) {
                final TarArchiveEntry entry = new TarArchiveEntry("entry" + i);
                entry.setSize(sizes[i]);
                tos.putArchiveEntry(entry);
                tos.write(content(sizes[i], i));
                tos.closeArchiveEntry();
            }
        }
        return baos.toByteArray();
    }

    private static byte[] createZip(final String name, final byte[] data, final int method) throws IOException {
        final ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipArchiveOutputStream zos = new ZipArchiveOutputStream(baos)) {
            final ZipArchiveEntry entry = new ZipArchiveEntry(name);
            entry.setMethod(method);
            if (method == ZipEntry.STORED) {
                entry.setSize(data.length);
                final CRC32 crc = new CRC32();
                crc.update(data);
                entry.setCrc(crc.getValue());
            }
            zos.putArchiveEntry(entry);
            zos.write(data);
            zos.closeArchiveEntry();
        }
        return baos.toByteArray();
    }

    private static int find(final byte[] haystack, final byte[] needle) {
        outer: for (int i = 0; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    private static byte[] gzip(final byte[] data) throws IOException {
        final ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (GzipCompressorOutputStream gz = new GzipCompressorOutputStream(baos)) {
            gz.write(data);
        }
        return baos.toByteArray();
    }

    private static void putIntLE(final byte[] b, final int offset, final int value) {
        b[offset] = (byte) value;
        b[offset + 1] = (byte) (value >> 8);
        b[offset + 2] = (byte) (value >> 16);
        b[offset + 3] = (byte) (value >> 24);
    }

    private static byte[] readAll(final InputStream in) throws IOException {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        final byte[] buf = new byte[512];
        int n;
        while ((n = in.read(buf)) != -1) {
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    private static BudgetArchiveInputStream<TarArchiveEntry> tarBudget(final byte[] tar, final ArchiveBudget budget) throws IOException {
        return new BudgetArchiveInputStream<>(new TarArchiveInputStream(new ByteArrayInputStream(tar)), budget);
    }

    private static BudgetArchiveInputStream<ZipArchiveEntry> zipBudget(final byte[] zip, final ArchiveBudget budget) throws IOException {
        return new BudgetArchiveInputStream<>(new ZipArchiveInputStream(new ByteArrayInputStream(zip)), budget);
    }

    @Test
    void testCloseEarlyDoesNotThrowAndDoesNotDrain() throws IOException {
        final byte[] zip = createZip("a.bin", content(500, 1), ZipEntry.DEFLATED);
        final BudgetArchiveInputStream<ZipArchiveEntry> in = zipBudget(zip, ArchiveBudget.builder().maxTotalBytes(10).build());
        assertNotNull(in.getNextEntry());
        assertEquals(5, in.read(new byte[5]));
        // Closing early must not drain the entry (which would trip the tiny budget) and must be idempotent.
        in.close();
        in.close();
    }

    @Test
    void testCorruptedNestedGzipChecksumPropagatesUnmasked() throws IOException {
        final byte[] gz = gzip(content(2000, 3));
        gz[gz.length - 8] ^= 0xFF; // corrupt the CRC32 trailer of the gzip member
        final byte[] zip = createZip("payload.gz", gz, ZipEntry.STORED);
        final BudgetArchiveInputStream<ZipArchiveEntry> in = zipBudget(zip, ArchiveBudget.unlimited());
        assertNotNull(in.getNextEntry());
        final CompressorException ex = assertThrows(CompressorException.class, () -> {
            try (GzipCompressorInputStream gzin = new GzipCompressorInputStream(in)) {
                readAll(gzin);
            }
        });
        assertFalse(BudgetExceededException.class.isInstance(ex), "checksum failure must not be masked as a budget failure");
        // All nested bytes were pulled through the budget before the checksum failed.
        assertEquals(gz.length, in.getEntryBytesRead());
    }

    @Test
    void testEntryBytesExactBoundary() throws IOException {
        final byte[] tar = createTar(150, 10);
        final BudgetArchiveInputStream<TarArchiveEntry> in = tarBudget(tar, ArchiveBudget.builder().maxEntryBytes(150).build());
        assertNotNull(in.getNextEntry());
        // Exactly at the limit: clean read and clean EOF, no exception.
        assertArrayEquals(content(150, 0), readAll(in));
        assertEquals(150, in.getEntryBytesRead());
        assertNotNull(in.getNextEntry());
        assertArrayEquals(content(10, 1), readAll(in));
        assertNull(in.getNextEntry());
    }

    @Test
    void testEntryBytesLimitTrips() throws IOException {
        final byte[] tar = createTar(100, 200, 300);
        final BudgetArchiveInputStream<TarArchiveEntry> in = tarBudget(tar, ArchiveBudget.builder().maxEntryBytes(150).build());
        assertNotNull(in.getNextEntry());
        assertArrayEquals(content(100, 0), readAll(in));
        assertNotNull(in.getNextEntry());
        final BudgetExceededException ex = assertThrows(BudgetExceededException.class, () -> readAll(in));
        assertEquals(BudgetType.ENTRY_BYTES, ex.getBudgetType());
        assertEquals("entry1", ex.getEntryName());
        assertEquals(150, ex.getBytesRead());
        assertEquals(150, ex.getLimit());
    }

    @Test
    void testEntryCountExactBoundaryAndTrip() throws IOException {
        final byte[] tar = createTar(10, 20, 30);
        // Exactly at the limit: all three entries are returned, then a clean null.
        try (BudgetArchiveInputStream<TarArchiveEntry> in = tarBudget(tar, ArchiveBudget.builder().maxEntries(3).build())) {
            for (int i = 0; i < 3; i++) {
                assertNotNull(in.getNextEntry());
                readAll(in);
            }
            assertNull(in.getNextEntry());
            assertEquals(3, in.getEntryCount());
        }
        // One over the limit: the third entry trips the budget.
        try (BudgetArchiveInputStream<TarArchiveEntry> in = tarBudget(tar, ArchiveBudget.builder().maxEntries(2).build())) {
            assertNotNull(in.getNextEntry());
            assertNotNull(in.getNextEntry());
            final BudgetExceededException ex = assertThrows(BudgetExceededException.class, in::getNextEntry);
            assertEquals(BudgetType.ENTRY_COUNT, ex.getBudgetType());
            assertEquals("entry2", ex.getEntryName());
            assertEquals(3, ex.getBytesRead());
            assertEquals(2, ex.getLimit());
        }
    }

    @Test
    void testForgedDeclaredSizesDoNotBypassTotalBudget() throws IOException {
        final byte[] data = content(4000, 5);
        final byte[] zip = createZip("forged.bin", data, ZipEntry.DEFLATED);
        // Forge all declared sizes down to a single byte: local header, data descriptor and central directory.
        assertEquals(0x50, zip[0] & 0xFF);
        putIntLE(zip, 18, 1); // local header compressed size
        putIntLE(zip, 22, 1); // local header uncompressed size
        final int dd = find(zip, new byte[] { 0x50, 0x4B, 0x07, 0x08 });
        assertTrue(dd > 0, "data descriptor not found");
        putIntLE(zip, dd + 8, 1); // data descriptor compressed size
        putIntLE(zip, dd + 12, 1); // data descriptor uncompressed size
        final int cd = find(zip, new byte[] { 0x50, 0x4B, 0x01, 0x02 });
        assertTrue(cd > 0, "central directory not found");
        putIntLE(zip, cd + 20, 1); // central directory compressed size
        putIntLE(zip, cd + 24, 1); // central directory uncompressed size
        // Unlimited budget: the forged declaration is accepted by the format, all real bytes come out.
        final ZipArchiveEntry forged;
        try (BudgetArchiveInputStream<ZipArchiveEntry> in = zipBudget(zip, ArchiveBudget.unlimited())) {
            forged = in.getNextEntry();
            assertArrayEquals(data, readAll(in));
            assertNull(in.getNextEntry());
            assertEquals(1, forged.getSize(), "fixture must carry the forged declared size");
            assertEquals(4000, in.getBytesRead(), "billing must follow actual reads, not the declared size");
        }
        // A total budget of 1000 bytes trips at exactly 1000 real bytes, ignoring the declared size of 1.
        try (BudgetArchiveInputStream<ZipArchiveEntry> in = zipBudget(zip, ArchiveBudget.builder().maxTotalBytes(1000).build())) {
            assertNotNull(in.getNextEntry());
            final BudgetExceededException ex = assertThrows(BudgetExceededException.class, () -> readAll(in));
            assertEquals(BudgetType.TOTAL_BYTES, ex.getBudgetType());
            assertEquals("forged.bin", ex.getEntryName());
            assertEquals(1000, ex.getBytesRead());
            assertEquals(1000, ex.getLimit());
        }
    }

    @Test
    void testNestedCompressionWrappingIsBilledAtArchiveLevel() throws IOException {
        final byte[] payload = new byte[2000]; // highly compressible zeros
        final byte[] gz = gzip(payload);
        assertTrue(gz.length < 200, "fixture gzip should be tiny, got " + gz.length);
        final byte[] zip = createZip("payload.gz", gz, ZipEntry.STORED);
        // A 100-byte entry budget admits the tiny gzip member while 2000 decompressed bytes flow through the wrapper.
        try (BudgetArchiveInputStream<ZipArchiveEntry> in = zipBudget(zip, ArchiveBudget.builder().maxEntryBytes(100).build())) {
            assertNotNull(in.getNextEntry());
            try (GzipCompressorInputStream gzin = new GzipCompressorInputStream(in)) {
                assertArrayEquals(payload, readAll(gzin));
            }
            assertEquals(gz.length, in.getEntryBytesRead(), "budget bills bytes actually read from the archive");
        }
        // A 10-byte entry budget stops the nested read.
        try (BudgetArchiveInputStream<ZipArchiveEntry> in = zipBudget(zip, ArchiveBudget.builder().maxEntryBytes(10).build())) {
            assertNotNull(in.getNextEntry());
            final BudgetExceededException ex = assertThrows(BudgetExceededException.class, () -> {
                try (GzipCompressorInputStream gzin = new GzipCompressorInputStream(in)) {
                    readAll(gzin);
                }
            });
            assertEquals(BudgetType.ENTRY_BYTES, ex.getBudgetType());
            assertEquals("payload.gz", ex.getEntryName());
            assertEquals(10, ex.getBytesRead());
        }
    }

    @Test
    void testRatioBudgetIsRejectedForTar() throws IOException {
        final byte[] tar = createTar(10);
        final TarArchiveInputStream tarIn = new TarArchiveInputStream(new ByteArrayInputStream(tar));
        // TAR cannot report compressed bytes consumed: the ratio budget is explicitly unsupported.
        final IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> new BudgetArchiveInputStream<>(tarIn, ArchiveBudget.builder().maxCompressionRatio(100).build()));
        assertTrue(ex.getMessage().contains("compression ratio"), ex.getMessage());
        tarIn.close();
    }

    @Test
    void testRatioBudgetTripsOnHighlyCompressibleZipEntry() throws IOException {
        final byte[] zip = createZip("zeros.bin", new byte[1_000_000], ZipEntry.DEFLATED);
        assertTrue(zip.length < 10_000, "fixture zip should be tiny, got " + zip.length);
        try (BudgetArchiveInputStream<ZipArchiveEntry> in = zipBudget(zip, ArchiveBudget.builder().maxCompressionRatio(100).build())) {
            assertNotNull(in.getNextEntry());
            final BudgetExceededException ex = assertThrows(BudgetExceededException.class, () -> readAll(in));
            assertEquals(BudgetType.COMPRESSION_RATIO, ex.getBudgetType());
            assertEquals("zeros.bin", ex.getEntryName());
            assertTrue(ex.getBytesRead() > 0);
            assertTrue(ex.getActualRatio() > 100, "actual ratio " + ex.getActualRatio());
            assertEquals(100, ex.getRatioLimit(), 0.0);
        }
        // A generous ratio limit lets the same archive through completely.
        try (BudgetArchiveInputStream<ZipArchiveEntry> in = zipBudget(zip, ArchiveBudget.builder().maxCompressionRatio(1_000_000).build())) {
            assertNotNull(in.getNextEntry());
            assertEquals(1_000_000, readAll(in).length);
            assertNull(in.getNextEntry());
        }
    }

    @Test
    void testStackedBudgetLayersCompose() throws IOException {
        final byte[] zip = createZip("zeros.bin", new byte[1_000_000], ZipEntry.DEFLATED);
        // An outer ratio budget stacked over an inner byte budget sees the compressed statistics through the inner layer.
        final BudgetArchiveInputStream<ZipArchiveEntry> inner = zipBudget(zip, ArchiveBudget.builder().maxTotalBytes(2_000_000).build());
        try (BudgetArchiveInputStream<ZipArchiveEntry> outer = new BudgetArchiveInputStream<>(inner,
                ArchiveBudget.builder().maxCompressionRatio(100).build())) {
            assertNotNull(outer.getNextEntry());
            final BudgetExceededException ex = assertThrows(BudgetExceededException.class, () -> readAll(outer));
            assertEquals(BudgetType.COMPRESSION_RATIO, ex.getBudgetType());
            assertEquals("zeros.bin", ex.getEntryName());
        }
    }

    @Test
    void testSkipIsBilledToTheBudget() throws IOException {
        final byte[] tar = createTar(300);
        try (BudgetArchiveInputStream<TarArchiveEntry> in = tarBudget(tar, ArchiveBudget.builder().maxTotalBytes(1000).build())) {
            assertNotNull(in.getNextEntry());
            assertEquals(200, in.skip(200));
            assertEquals(200, in.getBytesRead());
            assertArrayEquals(Arrays.copyOfRange(content(300, 0), 200, 300), readAll(in));
        }
        // Skipping past the budget trips it.
        try (BudgetArchiveInputStream<TarArchiveEntry> in = tarBudget(tar, ArchiveBudget.builder().maxTotalBytes(150).build())) {
            assertNotNull(in.getNextEntry());
            final BudgetExceededException ex = assertThrows(BudgetExceededException.class, () -> in.skip(200));
            assertEquals(BudgetType.TOTAL_BYTES, ex.getBudgetType());
            assertEquals(150, ex.getBytesRead());
        }
    }

    @Test
    void testSkippedEntryIsDrainedAndBilled() throws IOException {
        final byte[] tar = createTar(100, 200, 300);
        // Skipping entry0 bills its 100 bytes; entry1 may then only use 150 more of the 250 total.
        try (BudgetArchiveInputStream<TarArchiveEntry> in = tarBudget(tar, ArchiveBudget.builder().maxTotalBytes(250).build())) {
            assertNotNull(in.getNextEntry());
            assertNotNull(in.getNextEntry(), "skipping entry0 drains it through the budget");
            assertEquals(100, in.getBytesRead());
            final BudgetExceededException ex = assertThrows(BudgetExceededException.class, () -> readAll(in));
            assertEquals(BudgetType.TOTAL_BYTES, ex.getBudgetType());
            assertEquals("entry1", ex.getEntryName());
            assertEquals(250, ex.getBytesRead());
        }
        // A budget smaller than the skipped entry trips inside getNextEntry while draining.
        try (BudgetArchiveInputStream<TarArchiveEntry> in = tarBudget(tar, ArchiveBudget.builder().maxTotalBytes(50).build())) {
            assertNotNull(in.getNextEntry());
            final BudgetExceededException ex = assertThrows(BudgetExceededException.class, in::getNextEntry);
            assertEquals(BudgetType.TOTAL_BYTES, ex.getBudgetType());
            assertEquals("entry0", ex.getEntryName());
            assertEquals(50, ex.getBytesRead());
        }
    }

    @Test
    void testTotalBytesExactBoundaryAndTrip() throws IOException {
        final byte[] tar = createTar(100, 200, 300);
        // Exactly 300 = entry0 + entry1: both read cleanly, the third entry trips on its first byte.
        try (BudgetArchiveInputStream<TarArchiveEntry> in = tarBudget(tar, ArchiveBudget.builder().maxTotalBytes(300).build())) {
            assertNotNull(in.getNextEntry());
            assertArrayEquals(content(100, 0), readAll(in));
            assertNotNull(in.getNextEntry());
            assertArrayEquals(content(200, 1), readAll(in));
            assertEquals(300, in.getBytesRead());
            assertNotNull(in.getNextEntry());
            final BudgetExceededException ex = assertThrows(BudgetExceededException.class, () -> readAll(in));
            assertEquals(BudgetType.TOTAL_BYTES, ex.getBudgetType());
            assertEquals("entry2", ex.getEntryName());
            assertEquals(300, ex.getBytesRead());
        }
    }

    @Test
    void testUnlimitedBudgetPassesEverythingThrough() throws IOException {
        final byte[] tar = createTar(100, 200, 300);
        try (BudgetArchiveInputStream<TarArchiveEntry> in = tarBudget(tar, ArchiveBudget.unlimited())) {
            long total = 0;
            while (in.getNextEntry() != null) {
                total += readAll(in).length;
            }
            assertEquals(600, total);
            assertEquals(600, in.getBytesRead());
            assertEquals(3, in.getEntryCount());
        }
    }
}
