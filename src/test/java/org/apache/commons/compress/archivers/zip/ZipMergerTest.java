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
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;

import org.apache.commons.io.IOUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests {@link ZipMerger}.
 */
class ZipMergerTest {

    private static final long FIXED_TIME = 1_577_836_800_000L; // 2020-01-01T00:00:00Z, even seconds for ZIP granularity

    @TempDir
    Path tempDir;

    private ZipArchiveEntry addEntry(final ZipArchiveOutputStream zos, final String name, final int method, final byte[] content,
            final String comment) throws IOException {
        final ZipArchiveEntry entry = new ZipArchiveEntry(name);
        entry.setMethod(method);
        entry.setTime(FIXED_TIME);
        if (comment != null) {
            entry.setComment(comment);
        }
        if (method == ZipEntry.STORED) {
            entry.setSize(content.length);
            entry.setCompressedSize(content.length);
            final CRC32 crc = new CRC32();
            crc.update(content);
            entry.setCrc(crc.getValue());
        }
        zos.putArchiveEntry(entry);
        zos.write(content);
        zos.closeArchiveEntry();
        return entry;
    }

    private void assertEntryMetadata(final ZipArchiveEntry expected, final ZipArchiveEntry actual) {
        assertEquals(expected.getName(), actual.getName());
        assertEquals(expected.getMethod(), actual.getMethod());
        assertEquals(expected.getCrc(), actual.getCrc());
        assertEquals(expected.getSize(), actual.getSize());
        assertEquals(expected.getCompressedSize(), actual.getCompressedSize());
        assertEquals(expected.getTime(), actual.getTime());
        assertEquals(expected.getComment(), actual.getComment());
    }

    private byte[] contentOf(final ZipFile zipFile, final String name) throws IOException {
        final ZipArchiveEntry entry = zipFile.getEntry(name);
        assertNotNull(entry, "entry " + name);
        try (InputStream in = zipFile.getInputStream(entry)) {
            return IOUtils.toByteArray(in);
        }
    }

    private Path createZip(final String fileName, final boolean zip64, final Object... specs) throws IOException {
        final Path path = tempDir.resolve(fileName);
        try (ZipArchiveOutputStream zos = new ZipArchiveOutputStream(path)) {
            if (zip64) {
                zos.setUseZip64(Zip64Mode.Always);
            }
            for (int i = 0; i < specs.length; i += 4) {
                final String name = (String) specs[i];
                if (name.endsWith("/")) {
                    final ZipArchiveEntry dir = new ZipArchiveEntry(name);
                    dir.setMethod(ZipEntry.STORED);
                    dir.setTime(FIXED_TIME);
                    zos.putArchiveEntry(dir);
                    zos.closeArchiveEntry();
                } else {
                    addEntry(zos, name, (Integer) specs[i + 1], ((String) specs[i + 2]).getBytes(StandardCharsets.UTF_8),
                            (String) specs[i + 3]);
                }
            }
        }
        return path;
    }

    private List<String> entryNames(final Path zip) throws IOException {
        final List<String> names = new ArrayList<>();
        try (ZipFile zipFile = ZipFile.builder().setPath(zip).get()) {
            final Enumeration<ZipArchiveEntry> entries = zipFile.getEntries();
            while (entries.hasMoreElements()) {
                names.add(entries.nextElement().getName());
            }
        }
        return names;
    }

    private byte[] rawDataOf(final ZipFile zipFile, final String name) throws IOException {
        try (InputStream raw = zipFile.getRawInputStream(zipFile.getEntry(name))) {
            return IOUtils.toByteArray(raw);
        }
    }

    @Test
    void duplicateDirectoriesAreMerged() throws IOException {
        final Path first = createZip("first.zip", false, "dir/", null, null, null, "dir/a.txt", ZipEntry.DEFLATED, "a", null);
        final Path second = createZip("second.zip", false, "dir/", null, null, null, "dir/b.txt", ZipEntry.DEFLATED, "b", null);
        final Path target = tempDir.resolve("merged.zip");
        try (ZipMerger merger = ZipMerger.builder().addSource(first).addSource(second).build()) {
            merger.merge(target);
        }
        assertEquals(Arrays.asList("dir/", "dir/a.txt", "dir/b.txt"), entryNames(target));
    }

    @Test
    void duplicateNamesWithErrorStrategyFailsAndKeepsExistingTarget() throws IOException {
        final Path first = createZip("first.zip", false, "same.txt", ZipEntry.DEFLATED, "first", null);
        final Path second = createZip("second.zip", false, "same.txt", ZipEntry.DEFLATED, "second", null);
        final Path target = tempDir.resolve("merged.zip");
        Files.write(target, "OLD TARGET".getBytes(StandardCharsets.UTF_8));
        try (ZipMerger merger = ZipMerger.builder().addSource(first).addSource(second).build()) {
            assertThrows(ZipException.class, () -> merger.merge(target));
        }
        // the pre-existing target must not be corrupted by the failed merge
        assertArrayEquals("OLD TARGET".getBytes(StandardCharsets.UTF_8), Files.readAllBytes(target));
    }

    @Test
    void duplicateNamesWithLastWinsStrategy() throws IOException {
        final Path first = createZip("first.zip", false, "same.txt", ZipEntry.DEFLATED, "first", "comment-first");
        final Path second = createZip("second.zip", false, "same.txt", ZipEntry.STORED, "second", "comment-second");
        final Path target = tempDir.resolve("merged.zip");
        try (ZipMerger merger = ZipMerger.builder().duplicateStrategy(ZipMerger.DuplicateStrategy.LAST_WINS).addSource(first).addSource(second)
                .build()) {
            merger.merge(target);
        }
        try (ZipFile source = ZipFile.builder().setPath(second).get(); ZipFile merged = ZipFile.builder().setPath(target).get()) {
            assertArrayEquals("second".getBytes(StandardCharsets.UTF_8), contentOf(merged, "same.txt"));
            assertEntryMetadata(source.getEntry("same.txt"), merged.getEntry("same.txt"));
            // the raw compressed data must be copied from the winning source
            assertArrayEquals(rawDataOf(source, "same.txt"), rawDataOf(merged, "same.txt"));
        }
    }

    @Test
    void encryptedEntriesAreRejected() throws IOException {
        final Path source = createZip("encrypted.zip", false, "secret.txt", ZipEntry.DEFLATED, "secret", null);
        // set the encryption flag (general purpose bit 0) in the local file header and the central directory
        final byte[] bytes = Files.readAllBytes(source);
        assertEquals(0x504b0304, readInt(bytes, 0));
        bytes[6] |= 1;
        final int central = indexOf(bytes, new byte[] { 0x50, 0x4b, 0x01, 0x02 });
        assertTrue(central > 0, "central directory found");
        bytes[central + 8] |= 1;
        Files.write(source, bytes);
        final Path target = tempDir.resolve("merged.zip");
        try (ZipMerger merger = ZipMerger.builder().addSource(source).build()) {
            final UnsupportedZipFeatureException e = assertThrows(UnsupportedZipFeatureException.class, () -> merger.merge(target));
            assertEquals("secret.txt", e.getEntry().getName());
        }
        assertTrue(Files.notExists(target));
    }

    @Test
    void failedMergeLeavesExistingTargetUntouched() throws IOException {
        final Path source = createZip("source.zip", false, "a.txt", ZipEntry.DEFLATED, "a", null);
        final Path target = tempDir.resolve("merged.zip");
        Files.write(target, "OLD TARGET".getBytes(StandardCharsets.UTF_8));
        // a caller-managed source that has been closed before the merge fails while the merged archive is written
        final ZipFile broken = ZipFile.builder().setPath(source).get();
        broken.close();
        try (ZipMerger merger = ZipMerger.builder().addSource(broken).build()) {
            assertThrows(IOException.class, () -> merger.merge(target));
        }
        assertArrayEquals("OLD TARGET".getBytes(StandardCharsets.UTF_8), Files.readAllBytes(target));
        // the temporary file of the failed merge must have been cleaned up
        try (Stream<Path> files = Files.list(tempDir)) {
            assertEquals(2, files.count());
        }
    }

    private int indexOf(final byte[] haystack, final byte[] needle) {
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

    @Test
    void mergesStoredAndDeflatedEntriesPreservingRawData() throws IOException {
        final Path first = createZip("first.zip", false, "stored-a.txt", ZipEntry.STORED, "stored content a", "comment-a", "deflated-a.txt",
                ZipEntry.DEFLATED, "deflated content a, long enough to be compressed properly ....................", null);
        final Path second = createZip("second.zip", false, "stored-b.txt", ZipEntry.STORED, "stored content b", null, "deflated-b.txt",
                ZipEntry.DEFLATED, "deflated content b, long enough to be compressed properly ....................", "comment-b");
        final Path target = tempDir.resolve("merged.zip");
        try (ZipMerger merger = ZipMerger.builder().addSource(first).addSource(second).build()) {
            merger.merge(target);
        }
        assertEquals(Arrays.asList("stored-a.txt", "deflated-a.txt", "stored-b.txt", "deflated-b.txt"), entryNames(target));
        try (ZipFile firstZip = ZipFile.builder().setPath(first).get();
                ZipFile secondZip = ZipFile.builder().setPath(second).get();
                ZipFile merged = ZipFile.builder().setPath(target).get()) {
            for (final ZipFile source : new ZipFile[] { firstZip, secondZip }) {
                final Enumeration<ZipArchiveEntry> entries = source.getEntries();
                while (entries.hasMoreElements()) {
                    final ZipArchiveEntry sourceEntry = entries.nextElement();
                    final ZipArchiveEntry mergedEntry = merged.getEntry(sourceEntry.getName());
                    assertNotNull(mergedEntry, sourceEntry.getName());
                    // metadata is preserved
                    assertEntryMetadata(sourceEntry, mergedEntry);
                    // decompressed content is correct
                    assertArrayEquals(contentOf(source, sourceEntry.getName()), contentOf(merged, sourceEntry.getName()));
                    // the compressed data has been copied verbatim, not recompressed
                    assertArrayEquals(rawDataOf(source, sourceEntry.getName()), rawDataOf(merged, sourceEntry.getName()));
                }
            }
        }
    }

    private int readInt(final byte[] bytes, final int offset) {
        return (bytes[offset] & 0xff) << 24 | (bytes[offset + 1] & 0xff) << 16 | (bytes[offset + 2] & 0xff) << 8 | bytes[offset + 3] & 0xff;
    }

    @Test
    void sourceAndTargetMayBeTheSameFile() throws IOException {
        final Path first = createZip("first.zip", false, "a.txt", ZipEntry.STORED, "a", "comment-a");
        final Path second = createZip("second.zip", false, "b.txt", ZipEntry.DEFLATED, "b ............................", null);
        final byte[] rawA;
        try (ZipFile zipFile = ZipFile.builder().setPath(first).get()) {
            rawA = rawDataOf(zipFile, "a.txt");
        }
        // merge into one of the sources
        try (ZipMerger merger = ZipMerger.builder().addSource(first).addSource(second).build()) {
            merger.merge(first);
        }
        assertEquals(Arrays.asList("a.txt", "b.txt"), entryNames(first));
        try (ZipFile merged = ZipFile.builder().setPath(first).get()) {
            assertArrayEquals("a".getBytes(StandardCharsets.UTF_8), contentOf(merged, "a.txt"));
            assertArrayEquals("b ............................".getBytes(StandardCharsets.UTF_8), contentOf(merged, "b.txt"));
            assertEquals("comment-a", merged.getEntry("a.txt").getComment());
            assertArrayEquals(rawA, rawDataOf(merged, "a.txt"));
        }
    }

    @Test
    void zip64BoundaryMetadataIsPreserved() throws IOException {
        // fixture whose local headers and central directory carry 0xFFFFFFFF placeholders plus Zip64 extra fields
        final Path source = createZip("zip64.zip", true, "stored.txt", ZipEntry.STORED, "stored zip64 content", "zip64-comment",
                "deflated.txt", ZipEntry.DEFLATED, "deflated zip64 content ............................", null);
        try (ZipFile zipFile = ZipFile.builder().setPath(source).get()) {
            for (final String name : new String[] { "stored.txt", "deflated.txt" }) {
                assertNotNull(zipFile.getEntry(name).getExtraField(Zip64ExtendedInformationExtraField.HEADER_ID),
                        "fixture entry " + name + " carries Zip64 metadata");
            }
        }
        final Path target = tempDir.resolve("merged.zip");
        try (ZipMerger merger = ZipMerger.builder().addSource(source).build()) {
            merger.merge(target);
        }
        try (ZipFile sourceZip = ZipFile.builder().setPath(source).get(); ZipFile merged = ZipFile.builder().setPath(target).get()) {
            for (final String name : new String[] { "stored.txt", "deflated.txt" }) {
                assertEntryMetadata(sourceZip.getEntry(name), merged.getEntry(name));
                assertArrayEquals(contentOf(sourceZip, name), contentOf(merged, name));
                assertArrayEquals(rawDataOf(sourceZip, name), rawDataOf(merged, name));
            }
        }
    }
}
