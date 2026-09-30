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
package org.apache.commons.compress.archivers.destination;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.junit.jupiter.api.Test;

/**
 * Tests {@link ArchiveDestinationChecker} against in-memory manifests; both platform rule sets run on the same host.
 */
class ArchiveDestinationCheckerTest {

    private static final ArchiveDestinationEntry A = ArchiveDestinationEntry.file("a");
    private static final ArchiveDestinationEntry A_B = ArchiveDestinationEntry.file("a/b");
    private static final ArchiveDestinationEntry README = ArchiveDestinationEntry.file("Readme");
    private static final ArchiveDestinationEntry README_UPPER = ArchiveDestinationEntry.file("README");
    private static final ArchiveDestinationEntry OUTSIDE = ArchiveDestinationEntry.file("../outside");

    private ArchiveDestinationChecker checker(final ArchiveDestinationPlatform platform) {
        return ArchiveDestinationChecker.builder().setPlatform(platform).build();
    }

    private ArchiveDestinationIssue issue(final ArchiveDestinationReport report, final ArchiveDestinationIssue.Kind kind,
            final ArchiveDestinationEntry entry) {
        return report.getIssues(kind).stream().filter(i -> i.getEntry() == entry).findFirst().orElseThrow(() -> new AssertionError(
                "Missing issue " + kind + " for " + entry + " in " + report.getIssues()));
    }

    @Test
    void testBothPlatformsAvailableOnSameHost() {
        final List<ArchiveDestinationEntry> entries = Arrays.asList(
                ArchiveDestinationEntry.file("dir\\name"),
                ArchiveDestinationEntry.file("CON"));

        final ArchiveDestinationReport posixReport = checker(ArchiveDestinationPlatform.POSIX).check(entries);
        final ArchiveDestinationReport windowsReport = checker(ArchiveDestinationPlatform.WINDOWS).check(entries);

        assertTrue(posixReport.isClean());
        assertEquals(ArchiveDestinationPlatform.POSIX, posixReport.getPlatform());
        assertEquals(1, windowsReport.getIssues(ArchiveDestinationIssue.Kind.RESERVED_NAME).size());
        assertEquals(ArchiveDestinationPlatform.WINDOWS, windowsReport.getPlatform());
    }

    @Test
    void testCleanManifestNormalizesComponents() {
        final ArchiveDestinationReport report = checker(ArchiveDestinationPlatform.POSIX).check(
                ArchiveDestinationEntry.directory("docs/"),
                ArchiveDestinationEntry.file("./docs/./guide.md"),
                ArchiveDestinationEntry.file("docs/api/../index.html"));

        assertTrue(report.isClean(), report::toString);
    }

    @Test
    void testAdaptsArchiveEntry() {
        final TarArchiveEntry directory = new TarArchiveEntry("x/");
        final TarArchiveEntry file = new TarArchiveEntry("x/y");

        final ArchiveDestinationEntry adaptedDirectory = ArchiveDestinationEntry.from(directory);
        final ArchiveDestinationEntry adaptedFile = ArchiveDestinationEntry.from(file);

        assertEquals(ArchiveDestinationEntry.Type.DIRECTORY, adaptedDirectory.getType());
        assertEquals(ArchiveDestinationEntry.Type.FILE, adaptedFile.getType());
        final ArchiveDestinationReport report = checker(ArchiveDestinationPlatform.POSIX).check(adaptedDirectory, adaptedFile);
        assertTrue(report.isClean(), report::toString);
    }

    @Test
    void testCoreManifestPosix() {
        final ArchiveDestinationReport report = checker(ArchiveDestinationPlatform.POSIX).check(
                ArchiveDestinationEntry.directory("docs/"),
                OUTSIDE,
                A,
                A_B,
                README,
                README_UPPER);

        assertFalse(report.isClean());

        final ArchiveDestinationIssue traversal = issue(report, ArchiveDestinationIssue.Kind.TRAVERSAL_OUTSIDE_TARGET, OUTSIDE);
        assertEquals("outside", traversal.getLandingPath());

        final ArchiveDestinationIssue parent = issue(report, ArchiveDestinationIssue.Kind.PARENT_OCCUPIED_BY_FILE, A_B);
        assertSame(A, parent.getOtherEntry());
        assertEquals("a/b", parent.getLandingPath());

        assertTrue(report.getIssues(ArchiveDestinationIssue.Kind.CASE_COLLISION).isEmpty());
        assertEquals(0, report.getIssues(ArchiveDestinationIssue.Kind.FILE_AND_DIRECTORY_SAME_NAME).size());
    }

    @Test
    void testCoreManifestWindowsCaseCollision() {
        final ArchiveDestinationReport report = checker(ArchiveDestinationPlatform.WINDOWS).check(
                ArchiveDestinationEntry.directory("docs/"),
                OUTSIDE,
                A,
                A_B,
                README,
                README_UPPER);

        final ArchiveDestinationIssue traversal = issue(report, ArchiveDestinationIssue.Kind.TRAVERSAL_OUTSIDE_TARGET, OUTSIDE);
        assertNotNull(traversal);
        final ArchiveDestinationIssue parent = issue(report, ArchiveDestinationIssue.Kind.PARENT_OCCUPIED_BY_FILE, A_B);
        assertSame(A, parent.getOtherEntry());
        final ArchiveDestinationIssue caseCollision = issue(report, ArchiveDestinationIssue.Kind.CASE_COLLISION, README_UPPER);
        assertSame(README, caseCollision.getOtherEntry());
        assertEquals("README", caseCollision.getLandingPath());

        assertEquals(1, report.getIssues(ArchiveDestinationIssue.Kind.CASE_COLLISION).size());
    }

    @Test
    void testCollisionIssuesReferenceBothEntries() {
        final ArchiveDestinationEntry dir = ArchiveDestinationEntry.directory("x");
        final ArchiveDestinationEntry file = ArchiveDestinationEntry.file("x");
        final ArchiveDestinationEntry dirAgain = ArchiveDestinationEntry.directory("x");

        final ArchiveDestinationReport report = checker(ArchiveDestinationPlatform.POSIX).check(dir, file, dirAgain);

        final ArchiveDestinationIssue typeClash = issue(report, ArchiveDestinationIssue.Kind.FILE_AND_DIRECTORY_SAME_NAME, file);
        assertSame(file, typeClash.getEntry());
        assertSame(dir, typeClash.getOtherEntry());

        final ArchiveDestinationIssue duplicate = issue(report, ArchiveDestinationIssue.Kind.DUPLICATE_ENTRY, dirAgain);
        assertSame(dir, duplicate.getOtherEntry());
    }

    @Test
    void testDuplicateDirectory() {
        final ArchiveDestinationEntry first = ArchiveDestinationEntry.directory("dir/");
        final ArchiveDestinationEntry second = ArchiveDestinationEntry.directory("dir");

        final ArchiveDestinationReport report = checker(ArchiveDestinationPlatform.POSIX).check(first, second);

        final ArchiveDestinationIssue duplicate = issue(report, ArchiveDestinationIssue.Kind.DUPLICATE_ENTRY, second);
        assertSame(first, duplicate.getOtherEntry());
    }

    @Test
    void testEmptyNames() {
        final List<ArchiveDestinationEntry> entries = Arrays.asList(
                ArchiveDestinationEntry.file(""),
                ArchiveDestinationEntry.file("/"),
                ArchiveDestinationEntry.file("   "),
                ArchiveDestinationEntry.directory("./"));

        for (final ArchiveDestinationPlatform platform : ArchiveDestinationPlatform.values()) {
            final ArchiveDestinationReport report = checker(platform).check(entries);
            assertEquals(3, report.getIssues(ArchiveDestinationIssue.Kind.EMPTY_NAME).size(), platform::toString);
            assertEquals(1, report.getIssues(ArchiveDestinationIssue.Kind.ABSOLUTE_NAME).size(), platform::toString);
        }
    }

    @Test
    void testLinkEntries() {
        final ArchiveDestinationEntry absoluteLink = ArchiveDestinationEntry.symbolicLink("abs", "/etc/passwd");
        final ArchiveDestinationEntry escapingLink = ArchiveDestinationEntry.symbolicLink("up", "../../outside");
        final ArchiveDestinationEntry safeLink = ArchiveDestinationEntry.symbolicLink("safe", "dir/../file");
        final ArchiveDestinationEntry child = ArchiveDestinationEntry.file("up/child");

        final ArchiveDestinationReport posixReport = checker(ArchiveDestinationPlatform.POSIX)
                .check(absoluteLink, escapingLink, safeLink, child);

        assertEquals(1, posixReport.getIssues(ArchiveDestinationIssue.Kind.UNSAFE_LINK_TARGET).stream()
                .filter(i -> i.getEntry() == absoluteLink).count());
        assertEquals(1, posixReport.getIssues(ArchiveDestinationIssue.Kind.UNSAFE_LINK_TARGET).stream()
                .filter(i -> i.getEntry() == escapingLink).count());
        assertTrue(posixReport.getIssues(ArchiveDestinationIssue.Kind.UNSAFE_LINK_TARGET).stream()
                .noneMatch(i -> i.getEntry() == safeLink));
        final ArchiveDestinationIssue blockedChild = issue(posixReport, ArchiveDestinationIssue.Kind.PARENT_OCCUPIED_BY_FILE, child);
        assertSame(escapingLink, blockedChild.getOtherEntry());

        final ArchiveDestinationReport windowsReport = checker(ArchiveDestinationPlatform.WINDOWS)
                .check(ArchiveDestinationEntry.symbolicLink("drive", "C:\\evil"));
        assertEquals(1, windowsReport.getIssues(ArchiveDestinationIssue.Kind.UNSAFE_LINK_TARGET).size());
    }

    @Test
    void testReservedWindowsNamesAndSlashes() {
        final ArchiveDestinationEntry con = ArchiveDestinationEntry.file("CON");
        final ArchiveDestinationEntry com1Log = ArchiveDestinationEntry.file("data/COM1.log");
        final ArchiveDestinationEntry drive = ArchiveDestinationEntry.file("C:/Windows/system32");
        final ArchiveDestinationEntry unc = ArchiveDestinationEntry.file("\\\\server\\share");
        final ArchiveDestinationEntry driveRelative = ArchiveDestinationEntry.file("C:evil");
        final ArchiveDestinationEntry backslashPath = ArchiveDestinationEntry.file("a\\b");

        final ArchiveDestinationReport windowsReport = checker(ArchiveDestinationPlatform.WINDOWS)
                .check(con, com1Log, drive, unc, driveRelative, backslashPath);

        assertEquals(3, windowsReport.getIssues(ArchiveDestinationIssue.Kind.RESERVED_NAME).size());
        assertEquals(2, windowsReport.getIssues(ArchiveDestinationIssue.Kind.ABSOLUTE_NAME).size());

        final ArchiveDestinationReport posixReport = checker(ArchiveDestinationPlatform.POSIX).check(con, com1Log, drive, unc, driveRelative, backslashPath);
        assertTrue(posixReport.isClean(), posixReport::toString);

        final ArchiveDestinationEntry backslashChild = ArchiveDestinationEntry.file("a\\b\\c");
        final ArchiveDestinationReport windowsSplit = checker(ArchiveDestinationPlatform.WINDOWS).check(backslashPath, backslashChild);
        final ArchiveDestinationIssue parentIssue = issue(windowsSplit, ArchiveDestinationIssue.Kind.PARENT_OCCUPIED_BY_FILE, backslashChild);
        assertSame(backslashPath, parentIssue.getOtherEntry());
        assertEquals("a/b/c", parentIssue.getLandingPath());
        assertTrue(checker(ArchiveDestinationPlatform.POSIX).check(backslashPath, backslashChild).isClean());
    }
}
