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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.zip.CRC32;

import org.apache.commons.compress.archivers.zip.ZipEntryConflictIndex.CaseSensitivity;
import org.apache.commons.compress.archivers.zip.ZipEntryConflictIndex.SlashNormalization;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for {@link ZipEntryConflictIndex} and the identity-based read entry points of {@link ZipFile}.
 */
class ZipEntryConflictIndexTest {

    /** The contents of the two duplicate {@code a.txt} entries and the {@code A.TXT} entry. */
    private static final String FIRST = "one";
    private static final String SECOND = "two";
    private static final String THIRD = "three";

    @TempDir
    private Path tempDir;

    /**
     * Builds a minimal, STORED-only ZIP of a few dozen bytes containing {@code a.txt} twice, {@code A.TXT} once and a directory {@code a.txt/}.
     */
    private static byte[] buildFixture() throws IOException {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipArchiveOutputStream zos = new ZipArchiveOutputStream(out)) {
            putStored(zos, "a.txt", FIRST);
            putStored(zos, "a.txt", SECOND);
            putStored(zos, "A.TXT", THIRD);
            final ZipArchiveEntry dir = new ZipArchiveEntry("a.txt/");
            dir.setMethod(ZipArchiveEntry.STORED);
            dir.setSize(0);
            dir.setCrc(0);
            zos.putArchiveEntry(dir);
            zos.closeArchiveEntry();
        }
        return out.toByteArray();
    }

    private static void putStored(final ZipArchiveOutputStream zos, final String name, final String content) throws IOException {
        putStored(zos, name, content, ZipArchiveEntry.PLATFORM_FAT);
    }

    private static void putStored(final ZipArchiveOutputStream zos, final String name, final String content, final int platform) throws IOException {
        final byte[] data = content.getBytes(StandardCharsets.UTF_8);
        // Set the platform before the name: ZipArchiveEntry.setName normalizes '\' to '/' on the FAT platform.
        final ZipArchiveEntry entry = new ZipArchiveEntry();
        entry.setMethod(ZipArchiveEntry.STORED);
        entry.setPlatform(platform);
        entry.setName(name);
        entry.setSize(data.length);
        final CRC32 crc = new CRC32();
        crc.update(data);
        entry.setCrc(crc.getValue());
        zos.putArchiveEntry(entry);
        zos.write(data);
        zos.closeArchiveEntry();
    }

    private static String readAll(final InputStream in) throws IOException {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        final byte[] buf = new byte[64];
        int n;
        while ((n = in.read(buf)) != -1) {
            out.write(buf, 0, n);
        }
        return out.toString(StandardCharsets.UTF_8.name());
    }

    private File writeFixture(final byte[] bytes) throws IOException {
        final File file = tempDir.resolve("fixture.zip").toFile();
        Files.write(file.toPath(), bytes);
        return file;
    }

    private ZipFile openFixture() throws IOException {
        return ZipFile.builder().setFile(writeFixture(buildFixture())).get();
    }

    @Test
    void defaultIndexGroupsByRawNameAndReportsOnlyTrueDuplicates() throws Exception {
        try (ZipFile zf = openFixture()) {
            final ZipEntryConflictIndex index = ZipEntryConflictIndex.builder(zf).build();
            assertEquals(4, index.size());
            assertEquals(CaseSensitivity.CASE_SENSITIVE, index.getCaseSensitivity());
            assertEquals(SlashNormalization.PRESERVE_BACKSLASH, index.getSlashNormalization());

            // Only the raw duplicate "a.txt" is a conflict under the default rules.
            final Collection<ZipEntryConflictIndex.ConflictGroup> conflicts = index.getConflictGroups();
            assertEquals(1, conflicts.size());
            final ZipEntryConflictIndex.ConflictGroup group = conflicts.iterator().next();
            assertEquals("a.txt", group.getNormalizedName());
            assertEquals(2, group.getIdentities().size());
            assertEquals(0, group.getIdentities().get(0).getCentralDirectoryIndex());
            assertEquals(1, group.getIdentities().get(1).getCentralDirectoryIndex());
            assertFalse(group.getIdentities().get(0).isDirectory());

            // A.TXT and the directory a.txt/ are their own non-conflicting groups.
            final ZipEntryConflictIndex.ConflictGroup atxt = index.getGroup("A.TXT");
            assertNotNull(atxt);
            assertEquals(1, atxt.getIdentities().size());
            assertFalse(atxt.isConflict());
            final ZipEntryConflictIndex.ConflictGroup dir = index.getGroup("a.txt/");
            assertNotNull(dir);
            assertEquals(1, dir.getIdentities().size());
            assertTrue(dir.getIdentities().get(0).isDirectory());

            // The legacy by-name API still returns the first entry; the index is the explicit answer.
            assertEquals("a.txt", zf.getEntry("a.txt").getName());
        }
    }

    @Test
    void caseInsensitiveIndexGroupsNameCollisionsAndKeepsFileAndDirectoryApart() throws Exception {
        try (ZipFile zf = openFixture()) {
            final ZipEntryConflictIndex index = ZipEntryConflictIndex.builder(zf)
                    .setCaseSensitivity(CaseSensitivity.CASE_INSENSITIVE)
                    .build();
            assertEquals(4, index.size());

            // a.txt, a.txt and A.TXT now collide; the directory a.txt/ stays separate.
            final Collection<ZipEntryConflictIndex.ConflictGroup> conflicts = index.getConflictGroups();
            assertEquals(1, conflicts.size());
            final ZipEntryConflictIndex.ConflictGroup group = conflicts.iterator().next();
            assertEquals("a.txt", group.getNormalizedName());
            assertEquals(3, group.getIdentities().size());
            assertEquals(0, group.getIdentities().get(0).getCentralDirectoryIndex());
            assertEquals(1, group.getIdentities().get(1).getCentralDirectoryIndex());
            assertEquals(2, group.getIdentities().get(2).getCentralDirectoryIndex());
            // Every identity keeps its raw name and directory flag.
            assertEquals("a.txt", group.getIdentities().get(0).getName());
            assertEquals("a.txt", group.getIdentities().get(1).getName());
            assertEquals("A.TXT", group.getIdentities().get(2).getName());
            assertFalse(group.getIdentities().get(0).isDirectory());

            final ZipEntryConflictIndex.ConflictGroup dir = index.getGroup("a.txt/");
            assertNotNull(dir);
            assertEquals(1, dir.getIdentities().size());
            assertTrue(dir.getIdentities().get(0).isDirectory());
        }
    }

    @Test
    void readByIdentityReadsEachDuplicateIndividually() throws Exception {
        try (ZipFile zf = openFixture()) {
            // The legacy by-name read cannot tell the two a.txt entries apart.
            assertEquals(FIRST, readAll(zf.getInputStream(zf.getEntry("a.txt"))));

            // Reading by central directory identity reads each entry's own content.
            assertEquals(FIRST, readAll(zf.getInputStream(0)));
            assertEquals(SECOND, readAll(zf.getInputStream(1)));
            assertEquals(THIRD, readAll(zf.getInputStream(2)));

            // Reading through the identity's entry works just like reading by index.
            final ZipEntryConflictIndex index = ZipEntryConflictIndex.builder(zf).build();
            final ZipArchiveEntry first = index.getGroup("a.txt").getIdentities().get(0).getEntry();
            final ZipArchiveEntry second = index.getGroup("a.txt").getIdentities().get(1).getEntry();
            assertEquals(FIRST, readAll(zf.getInputStream(first)));
            assertEquals(SECOND, readAll(zf.getInputStream(second)));
        }
    }

    @Test
    void getEntryByIndexRejectsOutOfBounds() throws Exception {
        try (ZipFile zf = openFixture()) {
            assertThrows(IndexOutOfBoundsException.class, () -> zf.getEntry(-1));
            assertThrows(IndexOutOfBoundsException.class, () -> zf.getEntry(4));
        }
    }

    @Test
    void backslashAndForwardSlashCollisionDoesNotLoseEntries() throws Exception {
        final File file = tempDir.resolve("slashes.zip").toFile();
        try (ZipArchiveOutputStream zos = new ZipArchiveOutputStream(Files.newOutputStream(file.toPath()))) {
            // PLATFORM_UNIX keeps the backslash in the name; PLATFORM_FAT would normalize it to '/'.
            putStored(zos, "a\\b.txt", "bs", ZipArchiveEntry.PLATFORM_UNIX);
            putStored(zos, "a/b.txt", "fs", ZipArchiveEntry.PLATFORM_UNIX);
        }
        try (ZipFile zf = ZipFile.builder().setFile(file).get()) {
            // With the default rules the two names are distinct: no conflict, no entry lost.
            final ZipEntryConflictIndex plain = ZipEntryConflictIndex.builder(zf).build();
            assertEquals(2, plain.size());
            assertTrue(plain.getConflictGroups().isEmpty());
            final ZipEntryConflictIndex.ConflictGroup fsGroup = plain.getGroup("a/b.txt");
            assertNotNull(fsGroup);
            assertEquals(1, fsGroup.getIdentities().size());
            final ZipEntryConflictIndex.ConflictGroup bsGroup = plain.getGroup("a\\b.txt");
            assertNotNull(bsGroup);
            assertEquals(1, bsGroup.getIdentities().size());

            // Normalizing backslashes to slashes reveals the collision while keeping both entries.
            final ZipEntryConflictIndex normalized = ZipEntryConflictIndex.builder(zf)
                    .setSlashNormalization(SlashNormalization.NORMALIZE_BACKSLASH_TO_SLASH)
                    .build();
            assertEquals(2, normalized.size());
            assertEquals(1, normalized.getConflictGroups().size());
            final ZipEntryConflictIndex.ConflictGroup group = normalized.getGroup("a/b.txt");
            assertNotNull(group);
            assertEquals(2, group.getIdentities().size());
            assertEquals(0, group.getIdentities().get(0).getCentralDirectoryIndex());
            assertEquals(1, group.getIdentities().get(1).getCentralDirectoryIndex());
            // Both entries remain readable by identity.
            assertEquals("bs", readAll(zf.getInputStream(0)));
            assertEquals("fs", readAll(zf.getInputStream(1)));
        }
    }

    @Test
    void indexCanBeBuiltAndReadWithoutLocalFileHeaders() throws Exception {
        // ignoreLocalFileHeader(true) skips the local file header entirely: the index is built from the
        // central directory only and reading by identity still works, so no extraction is required.
        try (ZipFile zf = ZipFile.builder().setFile(writeFixture(buildFixture())).setIgnoreLocalFileHeader(true).get()) {
            final ZipEntryConflictIndex index = ZipEntryConflictIndex.builder(zf)
                    .setCaseSensitivity(CaseSensitivity.CASE_INSENSITIVE)
                    .build();
            assertEquals(4, index.size());
            assertEquals(1, index.getConflictGroups().size());
            assertEquals(3, index.getConflictGroups().iterator().next().getIdentities().size());
            assertEquals(FIRST, readAll(zf.getInputStream(0)));
            assertEquals(SECOND, readAll(zf.getInputStream(1)));
            assertEquals(THIRD, readAll(zf.getInputStream(2)));
        }
    }

    @Test
    void normalizeFollowsTheDocumentedRules() {
        // Slash normalization first, then case folding.
        assertEquals("a/b.txt",
                ZipEntryConflictIndex.normalize("a\\b.txt", CaseSensitivity.CASE_SENSITIVE, SlashNormalization.NORMALIZE_BACKSLASH_TO_SLASH));
        assertEquals("a/b.txt",
                ZipEntryConflictIndex.normalize("a\\B.TXT", CaseSensitivity.CASE_INSENSITIVE, SlashNormalization.NORMALIZE_BACKSLASH_TO_SLASH));
        // Default rules: nothing changes.
        assertEquals("A\\B.TXT",
                ZipEntryConflictIndex.normalize("A\\B.TXT", CaseSensitivity.CASE_SENSITIVE, SlashNormalization.PRESERVE_BACKSLASH));
        assertEquals("a.txt",
                ZipEntryConflictIndex.normalize("A.TXT", CaseSensitivity.CASE_INSENSITIVE, SlashNormalization.PRESERVE_BACKSLASH));
        // The directory marker (trailing slash) is never stripped.
        assertEquals("a.txt/",
                ZipEntryConflictIndex.normalize("A.TXT/", CaseSensitivity.CASE_INSENSITIVE, SlashNormalization.PRESERVE_BACKSLASH));
    }
}
