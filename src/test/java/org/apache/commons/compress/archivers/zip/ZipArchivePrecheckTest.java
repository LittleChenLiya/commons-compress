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
package org.apache.commons.compress.archivers.zip;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.CRC32;

import org.apache.commons.compress.archivers.ArchiveStreamFactory;
import org.apache.commons.compress.archivers.zip.ZipPrecheckReport.Issue;
import org.apache.commons.compress.archivers.zip.ZipPrecheckReport.Severity;
import org.apache.commons.compress.utils.SeekableInMemoryByteChannel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests {@link ZipArchivePrecheck} against locally generated fixtures: normal ZIP archives, archives with legal data descriptors, ZIP64 archives,
 * archives whose entries share a data region, forged lengths, CRC errors and truncated central directories.
 */
class ZipArchivePrecheckTest {

    @TempDir
    Path tempDir;

    private static byte[] deflatedZip(final Zip64Mode zip64Mode, final String... nameContentPairs) throws IOException {
        final ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipArchiveOutputStream zos = new ZipArchiveOutputStream(baos)) {
            zos.setUseZip64(zip64Mode);
            for (int i = 0; i < nameContentPairs.length; i += 2) {
                final ZipArchiveEntry entry = new ZipArchiveEntry(nameContentPairs[i]);
                entry.setMethod(ZipArchiveEntry.DEFLATED);
                zos.putArchiveEntry(entry);
                zos.write(nameContentPairs[i + 1].getBytes(StandardCharsets.UTF_8));
                zos.closeArchiveEntry();
            }
        }
        return baos.toByteArray();
    }

    private static List<Integer> findAll(final byte[] data, final int b0, final int b1, final int b2, final int b3) {
        final List<Integer> offsets = new ArrayList<>();
        for (int i = 0; i + 4 <= data.length; i++) {
            if ((data[i] & 0xFF) == b0 && (data[i + 1] & 0xFF) == b1 && (data[i + 2] & 0xFF) == b2 && (data[i + 3] & 0xFF) == b3) {
                offsets.add(i);
            }
        }
        return offsets;
    }

    private static List<Integer> findCentralHeaders(final byte[] zip) {
        return findAll(zip, 0x50, 0x4b, 0x01, 0x02);
    }

    private static int findEndOfCentralDirectory(final byte[] zip) {
        final List<Integer> offsets = findAll(zip, 0x50, 0x4b, 0x05, 0x06);
        assertFalse(offsets.isEmpty(), "fixture must contain an end of central directory record");
        return offsets.get(offsets.size() - 1);
    }

    private static int getU16(final byte[] data, final int offset) {
        return (data[offset] & 0xFF) | (data[offset + 1] & 0xFF) << 8;
    }

    private static long getU32(final byte[] data, final int offset) {
        return getU16(data, offset) | (long) getU16(data, offset + 2) << 16;
    }

    private static boolean hasIssueContaining(final ZipPrecheckReport report, final Severity severity, final String text) {
        return report.getIssues(severity).stream().anyMatch(issue -> issue.getMessage().contains(text));
    }

    private static int indexOf(final byte[] haystack, final byte[] needle) {
        outer: for (int i = 0; i + needle.length <= haystack.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    private static void putU16(final byte[] data, final int offset, final int value) {
        data[offset] = (byte) value;
        data[offset + 1] = (byte) (value >> 8);
    }

    private static void putU32(final byte[] data, final int offset, final long value) {
        putU16(data, offset, (int) value);
        putU16(data, offset + 2, (int) (value >> 16));
    }

    private static String repeat(final String s, final int times) {
        final StringBuilder builder = new StringBuilder(s.length() * times);
        for (int i = 0; i < times; i++) {
            builder.append(s);
        }
        return builder.toString();
    }

    private static byte[] storedZip(final String... nameContentPairs) throws IOException {
        final ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipArchiveOutputStream zos = new ZipArchiveOutputStream(baos)) {
            for (int i = 0; i < nameContentPairs.length; i += 2) {
                final byte[] content = nameContentPairs[i + 1].getBytes(StandardCharsets.UTF_8);
                final CRC32 crc = new CRC32();
                crc.update(content);
                final ZipArchiveEntry entry = new ZipArchiveEntry(nameContentPairs[i]);
                entry.setMethod(ZipArchiveEntry.STORED);
                entry.setSize(content.length);
                entry.setCrc(crc.getValue());
                zos.putArchiveEntry(entry);
                zos.write(content);
                zos.closeArchiveEntry();
            }
        }
        return baos.toByteArray();
    }

    @Test
    void archiveStreamFactoryPrechecksZipArchives() throws IOException {
        final Path path = tempDir.resolve("factory.zip");
        Files.write(path, storedZip("a.txt", "alpha", "b.txt", "beta"));
        final ZipPrecheckReport report = ArchiveStreamFactory.DEFAULT.precheckZipArchive(path);
        assertTrue(report.getIssues().isEmpty(), () -> "unexpected issues: " + report);
        assertEquals(2, report.getEntryCount());
    }

    @Test
    void channelPositionIsRestored() throws IOException {
        final byte[] zip = storedZip("a.txt", "alpha", "b.txt", "beta");
        final SeekableInMemoryByteChannel channel = new SeekableInMemoryByteChannel(zip);
        channel.position(7);
        ZipArchivePrecheck.check(channel);
        assertEquals(7, channel.position(), "structural precheck must restore the channel position");
        channel.position(3);
        ZipArchivePrecheck.verifyExtraction(channel, -1, -1);
        assertEquals(3, channel.position(), "extraction validation must restore the channel position");
        assertTrue(channel.isOpen(), "extraction validation must not close the caller's channel");
    }

    @Test
    void crcErrorIsDetectedByExtractionValidationOnly() throws IOException {
        final byte[] zip = storedZip("a.txt", "hello precheck world");
        final int contentOffset = indexOf(zip, "hello precheck world".getBytes(StandardCharsets.UTF_8));
        assertTrue(contentOffset > 0);
        zip[contentOffset + 2] ^= 0xFF; // corrupt the payload, leave every header untouched
        final ZipPrecheckReport structural = ZipArchivePrecheck.check(new SeekableInMemoryByteChannel(zip));
        assertFalse(structural.hasErrors(), () -> "headers are intact, structure must pass: " + structural);
        final ZipPrecheckReport extraction = ZipArchivePrecheck.verifyExtraction(new SeekableInMemoryByteChannel(zip), -1, -1);
        assertTrue(hasIssueContaining(extraction, Severity.ERROR, "CRC mismatch"), () -> "expected CRC mismatch: " + extraction);
        assertEquals(0, extraction.getIssues(Severity.ERROR).get(0).getEntryIndex());
    }

    @Test
    void demoPrecheckReports() throws IOException {
        System.out.println("==== demo: normal report ====");
        final byte[] normal = storedZip("docs/a.txt", "alpha", "docs/b.txt", "beta");
        System.out.println(ZipArchivePrecheck.check(new SeekableInMemoryByteChannel(normal)));

        System.out.println("==== demo: overlapping entry data ranges ====");
        final byte[] overlap = storedZip("first.txt", "first-entry-payload", "second.txt", "second-entry-payload");
        final List<Integer> central = findCentralHeaders(overlap);
        putU32(overlap, central.get(1) + 42, getU32(overlap, central.get(0) + 42)); // point entry #1 at the local header of entry #0
        System.out.println(ZipArchivePrecheck.check(new SeekableInMemoryByteChannel(overlap)));

        System.out.println("==== demo: low extraction budget ====");
        final byte[] big = deflatedZip(Zip64Mode.AsNeeded, "big.txt", repeat("0123456789", 200));
        System.out.println(ZipArchivePrecheck.verifyExtraction(new SeekableInMemoryByteChannel(big), 100, -1));
    }

    @Test
    void duplicateNamesWarnButAreNotReportedAsOverlappingData() throws IOException {
        final byte[] zip = storedZip("dup.txt", "first", "dup.txt", "second");
        final ZipPrecheckReport report = ZipArchivePrecheck.check(new SeekableInMemoryByteChannel(zip));
        assertFalse(report.hasErrors(), () -> "duplicate names are not a structural error: " + report);
        assertTrue(hasIssueContaining(report, Severity.WARNING, "duplicate entry name 'dup.txt'"), () -> "expected duplicate warning: " + report);
        assertFalse(hasIssueContaining(report, Severity.ERROR, "overlap"), "duplicate names must not masquerade as overlapping data");
    }

    @Test
    void emptyArchivePasses() throws IOException {
        final ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipArchiveOutputStream ignored = new ZipArchiveOutputStream(baos)) {
            // no entries
        }
        final byte[] zip = baos.toByteArray();
        final ZipPrecheckReport report = ZipArchivePrecheck.check(new SeekableInMemoryByteChannel(zip));
        assertTrue(report.getIssues().isEmpty(), () -> "unexpected issues: " + report);
        assertEquals(0, report.getEntryCount());
        final ZipPrecheckReport extraction = ZipArchivePrecheck.verifyExtraction(new SeekableInMemoryByteChannel(zip), -1, -1);
        assertTrue(extraction.getIssues().isEmpty(), () -> "unexpected issues: " + extraction);
    }

    @Test
    void encryptedEntryIsReportedAsUnsupported() throws IOException {
        final byte[] zip = storedZip("secret.txt", "payload");
        final int central = findCentralHeaders(zip).get(0);
        final int local = (int) getU32(zip, central + 42);
        putU16(zip, central + 8, getU16(zip, central + 8) | 1); // encryption flag in the central directory
        putU16(zip, local + 6, getU16(zip, local + 6) | 1); // and in the local header
        final ZipPrecheckReport report = ZipArchivePrecheck.check(new SeekableInMemoryByteChannel(zip));
        assertFalse(report.hasErrors(), () -> "no structural error expected: " + report);
        assertTrue(hasIssueContaining(report, Severity.UNSUPPORTED, "encrypted"), () -> "expected UNSUPPORTED: " + report);
    }

    @Test
    void forgedLengthCrossingIntoCentralDirectoryIsDetected() throws IOException {
        final byte[] zip = storedZip("a.txt", "alpha", "b.txt", "beta");
        final List<Integer> central = findCentralHeaders(zip);
        final int local = (int) getU32(zip, central.get(0) + 42);
        final long dataStart = local + 30L + getU16(zip, local + 26) + getU16(zip, local + 28);
        final long forged = central.get(0) - dataStart + 50; // reaches 50 bytes into the central directory
        putU32(zip, central.get(0) + 20, forged);
        putU32(zip, local + 18, forged);
        final ZipPrecheckReport report = ZipArchivePrecheck.check(new SeekableInMemoryByteChannel(zip));
        assertTrue(hasIssueContaining(report, Severity.ERROR, "crosses into the central directory region"),
                () -> "expected crossing error: " + report);
        final Issue issue = report.getIssues(Severity.ERROR).get(0);
        assertEquals(0, issue.getEntryIndex());
        assertEquals(dataStart, issue.getOffset());
    }

    @Test
    void legalDataDescriptorIsNotReported() throws IOException {
        final byte[] zip = deflatedZip(Zip64Mode.AsNeeded, "a.txt", "alpha", "b.txt", "beta");
        final ZipPrecheckReport report = ZipArchivePrecheck.check(new SeekableInMemoryByteChannel(zip));
        assertTrue(report.getIssues().isEmpty(), () -> "legal deferred CRC/lengths must not be reported: " + report);
        assertEquals(2, report.getEntryCount());
        final ZipPrecheckReport extraction = ZipArchivePrecheck.verifyExtraction(new SeekableInMemoryByteChannel(zip), -1, -1);
        assertTrue(extraction.getIssues().isEmpty(), () -> "unexpected issues: " + extraction);
    }

    @Test
    void lowExtractionBudgetAbortsValidation() throws IOException {
        final byte[] zip = deflatedZip(Zip64Mode.AsNeeded, "big.txt", repeat("0123456789", 200));
        final ZipPrecheckReport perEntry = ZipArchivePrecheck.verifyExtraction(new SeekableInMemoryByteChannel(zip), 100, -1);
        assertTrue(hasIssueContaining(perEntry, Severity.ERROR, "per-entry uncompressed byte budget"),
                () -> "expected per-entry budget failure: " + perEntry);
        assertEquals(0, perEntry.getIssues(Severity.ERROR).get(0).getEntryIndex());
        final ZipPrecheckReport global = ZipArchivePrecheck.verifyExtraction(new SeekableInMemoryByteChannel(zip), -1, 50);
        assertTrue(hasIssueContaining(global, Severity.ERROR, "global uncompressed byte budget"),
                () -> "expected global budget failure: " + global);
        final ZipPrecheckReport unlimited = ZipArchivePrecheck.verifyExtraction(new SeekableInMemoryByteChannel(zip), -1, -1);
        assertTrue(unlimited.getIssues().isEmpty(), () -> "unexpected issues: " + unlimited);
    }

    @Test
    void multiVolumeArchiveIsReportedAsUnsupported() throws IOException {
        final byte[] zip = storedZip("a.txt", "alpha");
        final int eocd = findEndOfCentralDirectory(zip);
        putU16(zip, eocd + 4, 1); // number of this disk
        final ZipPrecheckReport report = ZipArchivePrecheck.check(new SeekableInMemoryByteChannel(zip));
        assertFalse(report.hasErrors(), () -> "no structural error expected: " + report);
        assertTrue(hasIssueContaining(report, Severity.UNSUPPORTED, "multi-volume"), () -> "expected UNSUPPORTED: " + report);
    }

    @Test
    void normalZipPasses() throws IOException {
        final byte[] zip = storedZip("a.txt", "alpha", "b.txt", "beta");
        final ZipPrecheckReport report = ZipArchivePrecheck.check(new SeekableInMemoryByteChannel(zip));
        assertTrue(report.getIssues().isEmpty(), () -> "unexpected issues: " + report);
        assertEquals(2, report.getEntryCount());
        final ZipPrecheckReport extraction = ZipArchivePrecheck.verifyExtraction(new SeekableInMemoryByteChannel(zip), -1, -1);
        assertTrue(extraction.getIssues().isEmpty(), () -> "unexpected issues: " + extraction);
    }

    @Test
    void overlappingEntryDataRangesAreDetected() throws IOException {
        final byte[] zip = storedZip("first.txt", "first-entry-payload", "second.txt", "second-entry-payload");
        final List<Integer> central = findCentralHeaders(zip);
        putU32(zip, central.get(1) + 42, getU32(zip, central.get(0) + 42)); // point entry #1 at the local header of entry #0
        final ZipPrecheckReport report = ZipArchivePrecheck.check(new SeekableInMemoryByteChannel(zip));
        assertTrue(hasIssueContaining(report, Severity.ERROR, "overlaps"), () -> "expected overlap error: " + report);
        final Issue overlap = report.getIssues(Severity.ERROR).stream().filter(i -> i.getMessage().contains("overlaps")).findFirst().get();
        assertEquals(1, overlap.getEntryIndex());
        assertTrue(overlap.getOffset() >= 0, "overlap issue must carry the file offset");
    }

    @Test
    void truncatedArchiveIsDetected() throws IOException {
        final byte[] zip = storedZip("a.txt", "alpha", "b.txt", "beta");
        final byte[] truncated = Arrays.copyOf(zip, zip.length - 30); // cut off the end of central directory record
        final ZipPrecheckReport report = ZipArchivePrecheck.check(new SeekableInMemoryByteChannel(truncated));
        assertTrue(hasIssueContaining(report, Severity.ERROR, "end of central directory"), () -> "expected truncation error: " + report);
    }

    @Test
    void truncatedCentralDirectoryIsDetected() throws IOException {
        final byte[] zip = storedZip("a.txt", "alpha", "b.txt", "beta");
        final int eocd = findEndOfCentralDirectory(zip);
        putU16(zip, eocd + 10, 5); // claim five entries although only two central headers exist
        final ZipPrecheckReport report = ZipArchivePrecheck.check(new SeekableInMemoryByteChannel(zip));
        assertTrue(hasIssueContaining(report, Severity.ERROR, "central directory"), () -> "expected central directory error: " + report);
    }

    @Test
    void zip64ArchivePasses() throws IOException {
        final byte[] zip = deflatedZip(Zip64Mode.Always, "a.txt", "alpha", "b.txt", "beta");
        final ZipPrecheckReport report = ZipArchivePrecheck.check(new SeekableInMemoryByteChannel(zip));
        assertTrue(report.getIssues().isEmpty(), () -> "unexpected issues: " + report);
        assertEquals(2, report.getEntryCount());
        final ZipPrecheckReport extraction = ZipArchivePrecheck.verifyExtraction(new SeekableInMemoryByteChannel(zip), -1, -1);
        assertTrue(extraction.getIssues().isEmpty(), () -> "unexpected issues: " + extraction);
    }

    @Test
    void zip64PlaceholderWithoutExtraFieldIsDetected() throws IOException {
        final byte[] zip = storedZip("a.txt", "alpha");
        final int central = findCentralHeaders(zip).get(0);
        putU32(zip, central + 20, 0xFFFFFFFFL); // ZIP64 placeholder compressed size without a ZIP64 extra field
        final ZipPrecheckReport report = ZipArchivePrecheck.check(new SeekableInMemoryByteChannel(zip));
        assertTrue(hasIssueContaining(report, Severity.ERROR, "ZIP64"), () -> "expected ZIP64 length overflow error: " + report);
    }

    @Test
    void zipFilePrecheckDelegates() throws IOException {
        final Path path = tempDir.resolve("normal.zip");
        Files.write(path, storedZip("a.txt", "alpha"));
        try (ZipFile zipFile = ZipFile.builder().setPath(path).get()) {
            final ZipPrecheckReport report = zipFile.precheck();
            assertTrue(report.getIssues().isEmpty(), () -> "unexpected issues: " + report);
            assertEquals(1, report.getEntryCount());
        }
    }
}
