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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import org.apache.commons.compress.archivers.PlacementIssue.Type;
import org.junit.jupiter.api.Test;

class ArchivePlacementCheckerTest {

    private static PlacementIssue findIssue(final PlacementReport report, final Type type) {
        return report.getIssues().stream().filter(issue -> issue.getType() == type).findFirst().orElse(null);
    }

    private static List<PlacementIssue> findIssues(final PlacementReport report, final Type type) {
        return report.getIssues().stream().filter(issue -> issue.getType() == type).collect(Collectors.toList());
    }

    @Test
    void testAcceptanceManifestNamesBothEntriesOfEachConflict() {
        final PlacementReport report = ArchivePlacementChecker.check(TargetPlatform.WINDOWS, PlacementEntry.directory("docs/"),
            PlacementEntry.file("docs/index.txt"), PlacementEntry.file("../outside"), PlacementEntry.file("a"), PlacementEntry.file("a/b"),
            PlacementEntry.file("Readme"), PlacementEntry.file("README"));
        assertTrue(report.hasIssues());

        final PlacementIssue outside = findIssue(report, Type.OUTSIDE_TARGET);
        assertEquals("../outside", outside.getEntryName());

        final PlacementIssue parent = findIssue(report, Type.PARENT_OCCUPIED_BY_FILE);
        assertEquals("a/b", parent.getEntryName());
        assertEquals("a", parent.getRelatedName());

        final PlacementIssue casing = findIssue(report, Type.CASE_COLLISION);
        assertEquals("Readme", casing.getEntryName());
        assertEquals("README", casing.getRelatedName());

        final String text = report.toString();
        assertTrue(text.contains("../outside"));
        assertTrue(text.contains("a/b"));
        assertTrue(text.contains("Readme"));
        assertTrue(text.contains("README"));
    }

    @Test
    void testAbsolutePaths() {
        final PlacementReport posix = ArchivePlacementChecker.check(TargetPlatform.POSIX, PlacementEntry.file("/etc/passwd"));
        assertEquals(Type.ABSOLUTE_PATH, findIssue(posix, Type.ABSOLUTE_PATH).getType());

        final PlacementReport windows = ArchivePlacementChecker.check(TargetPlatform.WINDOWS, PlacementEntry.file("C:\\temp\\x"),
            PlacementEntry.file("\\\\server\\share"), PlacementEntry.file("/rooted"));
        assertEquals(3, findIssues(windows, Type.ABSOLUTE_PATH).size());
    }

    @Test
    void testCleanManifestReportsNoIssues() {
        final PlacementReport report = ArchivePlacementChecker.check(TargetPlatform.POSIX, PlacementEntry.directory("docs/"),
            PlacementEntry.file("docs/index.txt"), PlacementEntry.file("README"));
        assertFalse(report.hasIssues());
        assertEquals(0, report.getIssueCount());
    }

    @Test
    void testDuplicateDirectories() {
        final PlacementReport report = ArchivePlacementChecker.check(TargetPlatform.POSIX, PlacementEntry.directory("sub/"),
            PlacementEntry.directory("sub"), PlacementEntry.file("sub/file.txt"));
        final PlacementIssue duplicate = findIssue(report, Type.DUPLICATE_ENTRY);
        assertEquals("sub/", duplicate.getEntryName());
        assertEquals("sub", duplicate.getRelatedName());
        assertEquals(1, report.getIssueCount());
    }

    @Test
    void testEmptyPath() {
        final PlacementReport report = ArchivePlacementChecker.check(TargetPlatform.POSIX, PlacementEntry.file(""),
            PlacementEntry.of("./", PlacementEntry.Kind.DIRECTORY));
        assertEquals(2, findIssues(report, Type.EMPTY_PATH).size());
    }

    @Test
    void testFileDirectoryConflict() {
        final PlacementReport report = ArchivePlacementChecker.check(TargetPlatform.POSIX, PlacementEntry.file("x"),
            PlacementEntry.directory("x/"));
        final PlacementIssue conflict = findIssue(report, Type.FILE_DIRECTORY_CONFLICT);
        assertEquals("x", conflict.getEntryName());
        assertEquals("x/", conflict.getRelatedName());
    }

    @Test
    void testLinkEntries() {
        final PlacementReport report = ArchivePlacementChecker.check(TargetPlatform.POSIX,
            PlacementEntry.symbolicLink("link", "/etc/passwd"), PlacementEntry.symbolicLink("dir/link2", "../../etc"),
            PlacementEntry.symbolicLink("dir/ok", "../docs/index.txt"), PlacementEntry.hardLink("hard", "docs/index.txt"));
        final PlacementIssue absolute = findIssue(report, Type.LINK_TARGET_ABSOLUTE);
        assertEquals("link", absolute.getEntryName());
        final PlacementIssue outside = findIssue(report, Type.LINK_TARGET_OUTSIDE);
        assertEquals("dir/link2", outside.getEntryName());
        assertEquals(2, report.getIssueCount());
    }

    @Test
    void testPosixModeKeepsCaseAndBackslashDistinct() {
        final PlacementReport report = ArchivePlacementChecker.check(TargetPlatform.POSIX, PlacementEntry.file("Readme"),
            PlacementEntry.file("README"), PlacementEntry.file("a\\b"));
        assertFalse(report.hasIssues());
    }

    @Test
    void testSameManifestOnBothPlatforms() {
        final List<PlacementEntry> manifest = Arrays.asList(PlacementEntry.directory("docs/"), PlacementEntry.file("docs/index.txt"),
            PlacementEntry.file("Readme"), PlacementEntry.file("README"));
        assertFalse(ArchivePlacementChecker.check(TargetPlatform.POSIX, manifest).hasIssues());
        final PlacementReport windows = ArchivePlacementChecker.check(TargetPlatform.WINDOWS, manifest);
        assertEquals(1, windows.getIssueCount());
        assertEquals(Type.CASE_COLLISION, windows.getIssues().get(0).getType());
    }

    @Test
    void testWindowsBackslashSeparatesComponents() {
        final PlacementReport report = ArchivePlacementChecker.check(TargetPlatform.WINDOWS, PlacementEntry.file("dir"),
            PlacementEntry.file("dir\\file.txt"));
        final PlacementIssue parent = findIssue(report, Type.PARENT_OCCUPIED_BY_FILE);
        assertEquals("dir\\file.txt", parent.getEntryName());
        assertEquals("dir", parent.getRelatedName());
    }

    @Test
    void testWindowsReservedNames() {
        final PlacementReport report = ArchivePlacementChecker.check(TargetPlatform.WINDOWS, PlacementEntry.file("CON"),
            PlacementEntry.file("dir\\nul.txt"), PlacementEntry.file("LPT1"), PlacementEntry.file("console"));
        final List<PlacementIssue> reserved = findIssues(report, Type.RESERVED_NAME);
        assertEquals(3, reserved.size());
        assertEquals("CON", reserved.get(0).getEntryName());
        assertEquals("dir\\nul.txt", reserved.get(1).getEntryName());
        assertEquals("LPT1", reserved.get(2).getEntryName());

        final PlacementReport posix = ArchivePlacementChecker.check(TargetPlatform.POSIX, PlacementEntry.file("CON"),
            PlacementEntry.file("dir/nul.txt"));
        assertFalse(posix.hasIssues());
    }
}
