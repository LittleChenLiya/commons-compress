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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;

import org.apache.commons.io.IOUtils;
import org.junit.jupiter.api.Test;

/**
 * Tests the explicit ZIP entry name conflict index and identity based reading (COMPRESS conflict index feature).
 * <p>
 * The fixture is generated in memory with STORED (uncompressed) entries so no third-party data is needed and the archive is only a few hundred bytes while
 * still containing {@code a.txt} twice, {@code A.TXT} once and a directory {@code a.txt/}.
 * </p>
 */
class ZipNameConflictIndexTest {

    /** Content of the first occurrence of {@code a.txt}. */
    private static final byte[] CONTENT_ONE = { 'o', 'n', 'e' };

    /** Content of the second occurrence of {@code a.txt} - different so the two entries can be told apart when read. */
    private static final byte[] CONTENT_TWO = { 't', 'w', 'o' };

    /**
     * Builds the acceptance fixture: {@code a.txt} twice with different content, {@code A.TXT} once and the directory {@code a.txt/}. Everything is STORED,
     * so the result is tiny and nothing is compressed.
     */
    private byte[] buildFixture() throws Exception {
        final ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipArchiveOutputStream zos = new ZipArchiveOutputStream(bos)) {
            putStoredFile(zos, "a.txt", CONTENT_ONE);
            putStoredFile(zos, "A.TXT", new byte[0]);
            putStoredDirectory(zos, "a.txt/");
            putStoredFile(zos, "a.txt", CONTENT_TWO);
        }
        return bos.toByteArray();
    }

    /**
     * Builds a tiny fixture proving that a backslash name and a forward-slash name are two distinct central directory entries: {@code dir\f} and
     * {@code dir/f}.
     * <p>
     * {@link ZipArchiveEntry} itself converts backslashes to forward slashes, so this archive is assembled at byte level with STORED entries (local file
     * header, central directory and end of central directory record), which keeps the fixture tiny and lets us keep the raw backslash name.
     * </p>
     */
    private byte[] buildSlashFixture() throws Exception {
        return buildRawStoredZip(new RawEntry[] {
                new RawEntry("dir\\f", CONTENT_ONE),
                new RawEntry("dir/f", CONTENT_TWO),
        });
    }

    /** One STORED entry described by its raw (byte-for-byte) name and payload. */
    private static final class RawEntry {
        private final String name;
        private final byte[] data;

        private RawEntry(final String name, final byte[] data) {
            this.name = name;
            this.data = data;
        }
    }

    private static void putDWord(final java.io.ByteArrayOutputStream out, final long value) {
        out.write((int) (value & 0xff));
        out.write((int) (value >>> 8 & 0xff));
        out.write((int) (value >>> 16 & 0xff));
        out.write((int) (value >>> 24 & 0xff));
    }

    private static void putWord(final java.io.ByteArrayOutputStream out, final int value) {
        out.write(value & 0xff);
        out.write(value >>> 8 & 0xff);
    }

    /**
     * Builds a minimal STORED ZIP archive byte stream, preserving the given entry names exactly (no backslash conversion).
     */
    private byte[] buildRawStoredZip(final RawEntry[] rawEntries) throws Exception {
        final ByteArrayOutputStream local = new ByteArrayOutputStream();
        final ByteArrayOutputStream central = new ByteArrayOutputStream();
        final long[] offsets = new long[rawEntries.length];
        for (int i = 0; i < rawEntries.length; i++) {
            final RawEntry re = rawEntries[i];
            final byte[] nameBytes = re.name.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
            final CRC32 crc32 = new CRC32();
            crc32.update(re.data);
            offsets[i] = local.size();

            // Local file header.
            putDWord(local, 0x04034b50L);
            putWord(local, 20); // version needed
            putWord(local, 0); // general purpose flag
            putWord(local, ZipEntry.STORED);
            putWord(local, 0); // mod time
            putWord(local, 0); // mod date
            putDWord(local, crc32.getValue());
            putDWord(local, re.data.length); // compressed size
            putDWord(local, re.data.length); // uncompressed size
            putWord(local, nameBytes.length);
            putWord(local, 0); // extra length
            local.write(nameBytes);
            local.write(re.data);

            // Central directory file header.
            putDWord(central, 0x02014b50L);
            putWord(central, 3 << 8); // version made by, platform 3 (Unix) so backslashes are kept as-is
            putWord(central, 20); // version needed
            putWord(central, 0); // flags
            putWord(central, ZipEntry.STORED);
            putWord(central, 0); // mod time
            putWord(central, 0); // mod date
            putDWord(central, crc32.getValue());
            putDWord(central, re.data.length);
            putDWord(central, re.data.length);
            putWord(central, nameBytes.length);
            putWord(central, 0); // extra
            putWord(central, 0); // comment
            putWord(central, 0); // disk number
            putWord(central, 0); // internal attrs
            putDWord(central, 0); // external attrs
            putDWord(central, offsets[i]);
            central.write(nameBytes);
        }

        final byte[] localBytes = local.toByteArray();
        final byte[] centralBytes = central.toByteArray();
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(localBytes);
        out.write(centralBytes);

        // End of central directory record.
        putDWord(out, 0x06054b50L);
        putWord(out, 0); // disk
        putWord(out, 0); // disk with CD
        putWord(out, rawEntries.length);
        putWord(out, rawEntries.length);
        putDWord(out, centralBytes.length);
        putDWord(out, localBytes.length);
        putWord(out, 0); // comment length
        return out.toByteArray();
    }

    /**
     * Asserts that the returned ids are exactly the given central directory positions.
     */
    private void assertPositions(final List<ZipEntryId> ids, final int... positions) {
        assertEquals(positions.length, ids.size(), "number of ids");
        for (int i = 0; i < positions.length; i++) {
            assertEquals(positions[i], ids.get(i).getCentralDirectoryIndex(), "central directory position");
        }
    }

    private void putStoredDirectory(final ZipArchiveOutputStream zos, final String name) throws Exception {
        final ZipArchiveEntry entry = new ZipArchiveEntry(name);
        entry.setMethod(ZipEntry.STORED);
        entry.setSize(0);
        entry.setCrc(0);
        zos.putArchiveEntry(entry);
        zos.closeArchiveEntry();
    }

    private void putStoredFile(final ZipArchiveOutputStream zos, final String name, final byte[] content) throws Exception {
        final ZipArchiveEntry entry = new ZipArchiveEntry(name);
        entry.setMethod(ZipEntry.STORED);
        entry.setSize(content.length);
        final CRC32 crc32 = new CRC32();
        crc32.update(content);
        entry.setCrc(crc32.getValue());
        zos.putArchiveEntry(entry);
        zos.write(content);
        zos.closeArchiveEntry();
    }

    private byte[] readById(final ZipFile zipFile, final ZipEntryId id) throws Exception {
        try (InputStream in = zipFile.getInputStream(id)) {
            return IOUtils.toByteArray(in);
        }
    }

    /**
     * The generated fixture is tiny: four central directory entries in a few hundred bytes, no compression involved.
     */
    @Test
    void testFixtureIsTiny() throws Exception {
        final byte[] data = buildFixture();
        assertTrue(data.length < 600, "fixture should be only a few hundred bytes, was " + data.length);
        try (ZipFile zipFile = ZipFile.builder().setByteArray(data).get()) {
            assertEquals(4, zipFile.entries().size());
            assertEquals(4, zipFile.getNameConflictIndex().getEntryCount());
        }
    }

    /**
     * Case-sensitive view (EXACT): only the two byte-for-byte identical names {@code a.txt} conflict; {@code A.TXT} and the directory {@code a.txt/} are
     * separate groups.
     */
    @Test
    void testRawAndCaseSensitiveConflictGroups() throws Exception {
        try (ZipFile zipFile = ZipFile.builder().setByteArray(buildFixture()).get()) {
            final ZipNameConflictIndex index = zipFile.getNameConflictIndex(ZipNameNormalization.EXACT);

            // Raw duplicates: "a.txt" exactly twice.
            final Map<String, ZipNameCollision> raw = index.rawNameCollisions();
            assertEquals(1, raw.size(), raw.keySet().toString());
            final ZipNameCollision rawCollision = raw.get("a.txt");
            assertNotNull(rawCollision);
            assertTrue(rawCollision.isRaw());
            assertPositions(rawCollision.getEntryIds(), 0, 3);

            // Case-sensitive normalized view equals the raw view here.
            final Map<String, ZipNameCollision> normalized = index.normalizedNameCollisions();
            assertEquals(1, normalized.size(), normalized.keySet().toString());
            assertNull(index.getNormalizedNameCollision("A.TXT"), "different case does not collide under EXACT");
            assertNull(index.getRawNameCollision("a.txt/"), "directory singleton is not a conflict");
            assertPositions(index.getEntryIds("a.txt"), 0, 3);
            assertPositions(index.getEntryIds("A.TXT"), 1);
            assertPositions(index.getEntryIds("a.txt/"), 2);

            // The file and the directory sharing the same base name stay distinguishable.
            assertFalse(index.getEntryIds("a.txt").get(0).isDirectory());
            assertTrue(index.getEntryIds("a.txt/").get(0).isDirectory());
            assertEquals(1, index.getEntryIds("a.txt/").size());
        }
    }

    /**
     * Case-insensitive view: the two {@code a.txt} files and {@code A.TXT} end up in one normalized conflict group; the directory {@code a.txt/} is still a
     * group of its own because the trailing separator is preserved.
     */
    @Test
    void testCaseInsensitiveConflictGroup() throws Exception {
        try (ZipFile zipFile = ZipFile.builder().setByteArray(buildFixture()).get()) {
            final ZipNameConflictIndex index = zipFile.getNameConflictIndex(ZipNameNormalization.CASE_INSENSITIVE);

            // Raw duplicates are still reported independently of the normalization.
            assertEquals(1, index.rawNameCollisions().size());
            assertPositions(index.getRawNameCollision("a.txt").getEntryIds(), 0, 3);

            // Normalized: a.txt, a.txt and A.TXT collide (positions 0, 1, 3); the directory is separate.
            final Map<String, ZipNameCollision> normalized = index.normalizedNameCollisions();
            assertEquals(1, normalized.size(), normalized.keySet().toString());
            final ZipNameCollision collision = index.getNormalizedNameCollision("A.TXT");
            assertNotNull(collision);
            assertEquals("a.txt", collision.getKey());
            assertPositions(collision.getEntryIds(), 0, 1, 3);
            for (final ZipEntryId id : collision) {
                assertFalse(id.isDirectory());
            }

            // The directory is findable but is a singleton and therefore not reported as a conflict.
            assertPositions(index.getEntryIdsNormalized("A.TXT/"), 2);
            assertEquals(1, index.getEntryIdsNormalized("a.txt/").size());
            assertTrue(index.getEntryIdsNormalized("a.txt/").get(0).isDirectory());
            assertNotNull(index.getNormalizedNameCollision("a.txt"));
        }
    }

    /**
     * Each identity opens the right central directory entry: the two {@code a.txt} entries are read individually and yield their different contents. The
     * legacy name based APIs keep their documented (first-match) behavior.
     */
    @Test
    void testReadEachDuplicateById() throws Exception {
        try (ZipFile zipFile = ZipFile.builder().setByteArray(buildFixture()).get()) {
            final ZipNameConflictIndex index = zipFile.getNameConflictIndex(ZipNameNormalization.EXACT);
            final List<ZipEntryId> ids = index.getEntryIds("a.txt");
            assertEquals(2, ids.size());
            assertArrayEquals(CONTENT_ONE, readById(zipFile, ids.get(0)));
            assertArrayEquals(CONTENT_TWO, readById(zipFile, ids.get(1)));
            // Identity equals central directory position + name + directory flag.
            assertEquals(0, ids.get(0).getCentralDirectoryIndex());
            assertEquals(3, ids.get(1).getCentralDirectoryIndex());
            assertEquals("a.txt", ids.get(0).getName());

            // Reading via getEntry(id) is equivalent and targets the same entry.
            assertArrayEquals(CONTENT_TWO, IOUtils.toByteArray(zipFile.getInputStream(zipFile.getEntry(ids.get(1)))));

            // Legacy API compatibility: still resolves to the first same-named entry.
            assertArrayEquals(CONTENT_ONE, IOUtils.toByteArray(zipFile.getInputStream(zipFile.getEntry("a.txt"))));
            int count = 0;
            for (final ZipArchiveEntry ignored : zipFile.getEntries("a.txt")) {
                count++;
            }
            assertEquals(2, count);
        }
    }

    /**
     * Backslash versus forward slash: with EXACT rules they are two distinct entries (nothing is lost); with the Windows-like rules they are reported as one
     * normalized conflict while both central directory entries remain addressable individually.
     */
    @Test
    void testBackslashAndForwardSlashDoNotLoseEntries() throws Exception {
        try (ZipFile zipFile = ZipFile.builder().setByteArray(buildSlashFixture()).get()) {
            final ZipNameConflictIndex exact = zipFile.getNameConflictIndex(ZipNameNormalization.EXACT);
            assertEquals(2, exact.getEntryCount());
            assertTrue(exact.rawNameCollisions().isEmpty(), "raw names differ");
            assertPositions(exact.getEntryIds("dir\\f"), 0);
            assertPositions(exact.getEntryIds("dir/f"), 1);

            final ZipNameConflictIndex slashAware = zipFile.getNameConflictIndex(
                    ZipNameNormalization.of(true, false));
            final Map<String, ZipNameCollision> normalized = slashAware.normalizedNameCollisions();
            assertEquals(1, normalized.size(), normalized.keySet().toString());
            final ZipNameCollision collision = normalized.get("dir/f");
            assertNotNull(collision);
            assertPositions(collision.getEntryIds(), 0, 1);

            // Both entries are still readable individually with their own content - nothing got merged away.
            final List<ZipEntryId> ids = slashAware.getEntryIdsNormalized("dir/f");
            assertArrayEquals(CONTENT_ONE, readById(zipFile, ids.get(0)));
            assertArrayEquals(CONTENT_TWO, readById(zipFile, ids.get(1)));

            // Default index is Windows-like: same collision visible through the no-arg factory.
            final ZipNameCollision defaultCollision = zipFile.getNameConflictIndex().getNormalizedNameCollision("DIR/F");
            assertNotNull(defaultCollision);
            assertPositions(defaultCollision.getEntryIds(), 0, 1);
        }
    }

    /**
     * Building the index must not require reading entry data: it works against an archive opened with local file headers ignored, and reading through the
     * identity still resolves content offsets lazily.
     */
    @Test
    void testIndexBuiltFromCentralDirectoryOnly() throws Exception {
        final byte[] data = buildFixture();
        try (ZipFile zipFile = ZipFile.builder().setByteArray(data).setIgnoreLocalFileHeader(true).get()) {
            final ZipNameConflictIndex index = assertDoesNotThrow(
                    () -> zipFile.getNameConflictIndex(ZipNameNormalization.CASE_INSENSITIVE));
            assertEquals(4, index.getEntryCount());
            final List<ZipEntryId> ids = index.getEntryIds("a.txt");
            assertArrayEquals(CONTENT_ONE, readById(zipFile, ids.get(0)));
            assertArrayEquals(CONTENT_TWO, readById(zipFile, ids.get(1)));
        }
    }

    /**
     * Identities are validated against the archive so ids from another archive cannot be read silently.
     */
    @Test
    void testInvalidIdentityIsRejected() throws Exception {
        try (ZipFile zipFile = ZipFile.builder().setByteArray(buildFixture()).get()) {
            assertThrows(IllegalArgumentException.class,
                    () -> zipFile.getEntry(new ZipEntryId(99, "a.txt", false)));
            assertThrows(IllegalArgumentException.class,
                    () -> zipFile.getEntry(new ZipEntryId(1, "a.txt", false)));
            assertThrows(IllegalArgumentException.class,
                    () -> zipFile.getInputStream(new ZipEntryId(0, "a.txt/", true)));
        }
    }

    /**
     * Direct tests of the documented normalization rules (rule contract).
     */
    @Test
    void testNormalizationRules() {
        // EXACT changes nothing.
        assertEquals("a.txt", ZipNameNormalization.EXACT.normalize("a.txt"));

        // Backslash replacement preserves the trailing separator and therefore file/directory distinction.
        final ZipNameNormalization slashes = ZipNameNormalization.of(true, false);
        assertEquals("dir/a.txt", slashes.normalize("dir\\a.txt"));
        assertEquals("dir/a.txt/", slashes.normalize("dir\\a.txt\\"));
        assertFalse(slashes.isCaseInsensitive());
        assertTrue(slashes.isConvertingBackslashes());

        // Case folding uses the root locale and leaves separators alone unless requested.
        assertEquals("a.txt", ZipNameNormalization.CASE_INSENSITIVE.normalize("A.TXT"));
        assertEquals("dir\\a.txt", ZipNameNormalization.CASE_INSENSITIVE.normalize("DIR\\A.TXT"));

        // Windows-like combines both.
        assertEquals("dir/a.txt", ZipNameNormalization.WINDOWS_LIKE.normalize("DIR\\A.TXT"));

        // Rules are value objects usable as cache keys.
        assertEquals(ZipNameNormalization.WINDOWS_LIKE, ZipNameNormalization.of(true, true));
        assertEquals(ZipNameNormalization.of(true, false), ZipNameNormalization.of(true, false));
        assertEquals(ZipNameNormalization.of(true, false).hashCode(), ZipNameNormalization.of(true, false).hashCode());
        assertEquals(ZipNameNormalization.EXACT, ZipNameNormalization.of(false, false));
        assertFalse(ZipNameNormalization.EXACT.isCaseInsensitive());
        assertFalse(ZipNameNormalization.EXACT.isConvertingBackslashes());
        assertTrue(ZipNameNormalization.CASE_INSENSITIVE.isCaseInsensitive());
    }
}

