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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.apache.commons.compress.archivers.ArchiveStreamFactory;
import org.apache.commons.compress.utils.SeekableInMemoryByteChannel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ZipPreflightTest {

    @TempDir
    private Path dir;

    private static ZipPreflightReport check(final byte[] zip) throws IOException {
        return ZipPreflight.check(new SeekableInMemoryByteChannel(zip));
    }

    private static ZipPreflightReport check(final byte[] zip, final ZipPreflightOptions options) throws IOException {
        return ZipPreflight.check(new SeekableInMemoryByteChannel(zip), options);
    }

    private static ZipPreflightDiagnostic onlyError(final ZipPreflightReport report) {
        final List<ZipPreflightDiagnostic> errors = report.getDiagnostics(ZipPreflightSeverity.ERROR);
        assertEquals(1, errors.size(), () -> "expected exactly one error but got " + report);
        return errors.get(0);
    }

    @Test
    void normalZipPassesStructuralAndContentValidation() throws IOException {
        final ZipPreflightReport report = check(ZipPreflightFixtures.normalZip(),
                ZipPreflightOptions.builder().setValidateContent(true).get());
        assertEquals(2, report.getEntryCount());
        assertTrue(report.getDiagnostics().isEmpty(), () -> "expected no diagnostics but got " + report);
        assertTrue(report.isPassed());
        assertTrue(report.isFullyVerified());
    }

    @Test
    void legalDataDescriptorsAreNotReported() throws IOException {
        final ZipPreflightReport report = check(ZipPreflightFixtures.descriptorZip(),
                ZipPreflightOptions.builder().setValidateContent(true).get());
        assertTrue(report.getDiagnostics().isEmpty(), () -> "legal deferred CRC/length must not be reported but got " + report);
        assertTrue(report.isFullyVerified());
    }

    @Test
    void zip64ArchivePasses() throws IOException {
        final ZipPreflightReport report = check(ZipPreflightFixtures.zip64Zip(),
                ZipPreflightOptions.builder().setValidateContent(true).get());
        assertTrue(report.getDiagnostics().isEmpty(), () -> "expected no diagnostics but got " + report);
        assertTrue(report.isFullyVerified());
    }

    @Test
    void emptyArchivePasses() throws IOException {
        final ZipPreflightReport report = check(ZipPreflightFixtures.emptyZip());
        assertEquals(0, report.getEntryCount());
        assertTrue(report.getDiagnostics().isEmpty(), () -> "expected no diagnostics but got " + report);
        assertTrue(report.isPassed());
        assertTrue(report.isFullyVerified());
    }

    @Test
    void overlappingDataRegionsAreDetected() throws IOException {
        final ZipPreflightReport report = check(ZipPreflightFixtures.overlapZip());
        assertFalse(report.isPassed());
        final ZipPreflightDiagnostic error = onlyError(report);
        assertEquals(1, error.getEntryIndex());
        assertEquals("second.txt", error.getEntryName());
        assertEquals(89, error.getOffset());
        assertTrue(error.getMessage().contains("overlaps"), error.getMessage());
        assertTrue(error.getMessage().contains("entry #0"), error.getMessage());
    }

    @Test
    void forgedLengthReachingIntoCentralDirectoryIsDetected() throws IOException {
        final ZipPreflightReport report = check(ZipPreflightFixtures.forgedLengthZip());
        assertFalse(report.isPassed());
        final List<ZipPreflightDiagnostic> errors = report.getDiagnostics(ZipPreflightSeverity.ERROR);
        assertFalse(errors.isEmpty());
        final ZipPreflightDiagnostic error = errors.get(0);
        assertEquals(0, error.getEntryIndex());
        assertTrue(error.getMessage().contains("central directory region"), error.getMessage());
        assertTrue(error.getOffset() >= 0);
    }

    @Test
    void zip64PlaceholderWithoutExtraFieldIsDetected() throws IOException {
        final ZipPreflightReport report = check(ZipPreflightFixtures.forgedZip64PlaceholderZip());
        assertFalse(report.isPassed());
        final ZipPreflightDiagnostic error = onlyError(report);
        assertEquals(0, error.getEntryIndex());
        assertTrue(error.getMessage().contains("ZIP64 extra field"), error.getMessage());
    }

    @Test
    void crcErrorIsOnlyCaughtByContentValidation() throws IOException {
        final ZipPreflightReport structuralOnly = check(ZipPreflightFixtures.badCrcZip());
        assertTrue(structuralOnly.isPassed(), () -> "structural check must not fail but got " + structuralOnly);
        final ZipPreflightReport validated = check(ZipPreflightFixtures.badCrcZip(),
                ZipPreflightOptions.builder().setValidateContent(true).get());
        assertFalse(validated.isPassed());
        final ZipPreflightDiagnostic error = onlyError(validated);
        assertEquals(0, error.getEntryIndex());
        assertEquals("alpha.txt", error.getEntryName());
        assertTrue(error.getMessage().contains("CRC-32 mismatch"), error.getMessage());
    }

    @Test
    void truncatedCentralDirectoryIsDetected() throws IOException {
        final ZipPreflightReport report = check(ZipPreflightFixtures.truncatedCentralDirectoryZip());
        assertFalse(report.isPassed());
        final ZipPreflightDiagnostic error = onlyError(report);
        assertEquals(ZipPreflightDiagnostic.ARCHIVE_LEVEL, error.getEntryIndex());
        assertTrue(error.getMessage().contains("end of central directory"), error.getMessage());
    }

    @Test
    void forgedEntryCountIsDetected() throws IOException {
        final ZipPreflightReport report = check(ZipPreflightFixtures.forgedEntryCountZip());
        assertFalse(report.isPassed());
        assertFalse(report.getDiagnostics(ZipPreflightSeverity.ERROR).isEmpty());
    }

    @Test
    void duplicateNamesWarnButAreNotADataOverlap() throws IOException {
        final ZipPreflightReport report = check(ZipPreflightFixtures.duplicateNamesZip());
        assertTrue(report.isPassed(), () -> "duplicate names must not fail the check but got " + report);
        assertEquals(0, report.count(ZipPreflightSeverity.ERROR));
        final List<ZipPreflightDiagnostic> warnings = report.getDiagnostics(ZipPreflightSeverity.WARNING);
        assertEquals(1, warnings.size());
        assertEquals(1, warnings.get(0).getEntryIndex());
        assertTrue(warnings.get(0).getMessage().contains("duplicate entry name"), warnings.get(0).getMessage());
    }

    @Test
    void encryptedEntriesAreMarkedUnsupportedNotPassed() throws IOException {
        final ZipPreflightReport report = check(ZipPreflightFixtures.encryptedZip(),
                ZipPreflightOptions.builder().setValidateContent(true).get());
        assertFalse(report.isPassed());
        assertFalse(report.isFullyVerified());
        assertEquals(0, report.count(ZipPreflightSeverity.ERROR));
        final List<ZipPreflightDiagnostic> unsupported = report.getDiagnostics(ZipPreflightSeverity.UNSUPPORTED);
        assertEquals(1, unsupported.size());
        assertEquals(0, unsupported.get(0).getEntryIndex());
        assertTrue(unsupported.get(0).getMessage().contains("encrypted"), unsupported.get(0).getMessage());
    }

    @Test
    void splitArchivesAreMarkedUnsupportedNotPassed() throws IOException {
        final ZipPreflightReport report = check(ZipPreflightFixtures.splitZip());
        assertFalse(report.isPassed());
        assertEquals(0, report.count(ZipPreflightSeverity.ERROR));
        final List<ZipPreflightDiagnostic> unsupported = report.getDiagnostics(ZipPreflightSeverity.UNSUPPORTED);
        assertEquals(1, unsupported.size());
        assertTrue(unsupported.get(0).getMessage().contains("split"), unsupported.get(0).getMessage());
    }

    @Test
    void supportedButUnverifiedMethodsAreReported() throws IOException {
        final ZipPreflightReport report = check(ZipPreflightFixtures.bzip2MethodZip(),
                ZipPreflightOptions.builder().setValidateContent(true).get());
        assertTrue(report.isPassed(), () -> "unverified entries must not fail the check but got " + report);
        assertFalse(report.isFullyVerified());
        final List<ZipPreflightDiagnostic> unverified = report.getDiagnostics(ZipPreflightSeverity.UNVERIFIED);
        assertEquals(1, unverified.size());
        assertEquals(0, unverified.get(0).getEntryIndex());
    }

    @Test
    void lowPerEntryBudgetStopsValidationImmediately() throws IOException {
        // the first entry inflates to 5000 bytes, the second entry carries a forged CRC;
        // with a budget of 100 bytes validation must stop at the first entry and never check the second
        final ZipPreflightReport report = check(ZipPreflightFixtures.budgetZip(),
                ZipPreflightOptions.builder().setValidateContent(true).setMaxUncompressedBytesPerEntry(100).get());
        assertFalse(report.isPassed());
        final ZipPreflightDiagnostic error = onlyError(report);
        assertEquals(0, error.getEntryIndex());
        assertEquals("big.txt", error.getEntryName());
        assertTrue(error.getMessage().contains("100"), error.getMessage());
        assertTrue(error.getOffset() >= 0);
    }

    @Test
    void lowTotalBudgetStopsValidationImmediately() throws IOException {
        final ZipPreflightReport report = check(ZipPreflightFixtures.normalZip(),
                ZipPreflightOptions.builder().setValidateContent(true).setMaxUncompressedBytesTotal(1000).get());
        assertFalse(report.isPassed());
        final ZipPreflightDiagnostic error = onlyError(report);
        assertTrue(error.getMessage().contains("total"), error.getMessage());
    }

    @Test
    void channelPositionIsRestored() throws IOException {
        final byte[] zip = ZipPreflightFixtures.normalZip();
        final SeekableInMemoryByteChannel channel = new SeekableInMemoryByteChannel(zip);
        channel.position(7);
        final ZipPreflightReport report = ZipPreflight.check(channel,
                ZipPreflightOptions.builder().setValidateContent(true).get());
        assertTrue(report.isPassed());
        assertEquals(7, channel.position());
    }

    @Test
    void zipFileEntryPoint() throws IOException {
        final Path path = dir.resolve("normal.zip");
        Files.write(path, ZipPreflightFixtures.normalZip());
        try (ZipFile zipFile = ZipFile.builder().setPath(path).get()) {
            final ZipPreflightReport report = zipFile.preflight(ZipPreflightOptions.builder().setValidateContent(true).get());
            assertTrue(report.isFullyVerified(), () -> "expected a clean report but got " + report);
            assertEquals(2, report.getEntryCount());
        }
    }

    @Test
    void archiveStreamFactoryEntryPoint() throws IOException {
        final SeekableInMemoryByteChannel channel = new SeekableInMemoryByteChannel(ZipPreflightFixtures.normalZip());
        final ZipPreflightReport report = new ArchiveStreamFactory().preflightZipArchive(channel);
        assertTrue(report.isPassed(), () -> "expected a clean report but got " + report);
        assertEquals(2, report.getEntryCount());
    }

    @Test
    void pathEntryPoint() throws IOException {
        final Path path = dir.resolve("descriptor.zip");
        Files.write(path, ZipPreflightFixtures.descriptorZip());
        final ZipPreflightReport report = ZipPreflight.check(path);
        assertTrue(report.isPassed(), () -> "expected a clean report but got " + report);
    }

    @Test
    void preflightIsRepeatableOnTheSameChannel() throws IOException {
        final SeekableInMemoryByteChannel channel = new SeekableInMemoryByteChannel(ZipPreflightFixtures.overlapZip());
        final ZipPreflightReport first = ZipPreflight.check(channel);
        final ZipPreflightReport second = ZipPreflight.check(channel);
        assertEquals(first.getDiagnostics().toString(), second.getDiagnostics().toString());
        assertFalse(first.isPassed());
    }
}
