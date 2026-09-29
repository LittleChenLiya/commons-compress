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

package org.apache.commons.compress.archivers.tar;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.compress.archivers.ArchiveException;
import org.apache.commons.compress.utils.IOUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests {@link TarRepacker}.
 */
class TarRepackerTest {

    private static final String LONG_UTF8_NAME = "dir/\u957f\u8def\u5f84/" + repeat("\u6570\u636e\u6587\u4ef6\u540d\u79f0", 30) + ".txt";

    private static TarArchiveEntry fileEntry(final String name, final long modTime, final long uid, final long gid, final String userName) {
        final TarArchiveEntry entry = new TarArchiveEntry(name);
        entry.setModTime(modTime);
        entry.setUserId(uid);
        entry.setGroupId(gid);
        entry.setUserName(userName);
        entry.setGroupName(userName + "-group");
        return entry;
    }

    private static Map<String, byte[]> readContents(final byte[] tar) throws IOException {
        final Map<String, byte[]> contents = new LinkedHashMap<>();
        try (TarArchiveInputStream input = new TarArchiveInputStream(new ByteArrayInputStream(tar))) {
            TarArchiveEntry entry;
            while ((entry = input.getNextEntry()) != null) {
                contents.put(entry.getName(), entry.isFile() ? IOUtils.toByteArray(input) : null);
            }
        }
        return contents;
    }

    private static String repeat(final String s, final int times) {
        final StringBuilder builder = new StringBuilder();
        for (int i = 0; i < times; i++) {
            builder.append(s);
        }
        return builder.toString();
    }

    private static byte[] repack(final byte[] source) throws IOException {
        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        TarRepacker.builder().get().repack(new ByteArrayInputStream(source), output);
        return output.toByteArray();
    }

    private static byte[] tar(final Object... entriesAndContents) throws IOException {
        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (TarArchiveOutputStream tarOutput = new TarArchiveOutputStream(output, StandardCharsets.UTF_8.name())) {
            tarOutput.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);
            for (int i = 0; i < entriesAndContents.length; i++) {
                final TarArchiveEntry entry = (TarArchiveEntry) entriesAndContents[i];
                final byte[] data = i + 1 < entriesAndContents.length && entriesAndContents[i + 1] instanceof byte[] ? (byte[]) entriesAndContents[++i] : null;
                if (data != null) {
                    entry.setSize(data.length);
                }
                tarOutput.putArchiveEntry(entry);
                if (data != null) {
                    tarOutput.write(data);
                }
                tarOutput.closeArchiveEntry();
            }
        }
        return output.toByteArray();
    }

    @TempDir
    Path tempDir;

    @Test
    void testBadChecksumFailsAndLeavesOldTargetUnchanged() throws IOException {
        final byte[] source = tar(fileEntry("a.txt", 1_000_000, 1, 2, "alice"), "hello".getBytes(StandardCharsets.UTF_8));
        // Corrupt the checksum field (offset 148, 8 bytes) of the first header block with a valid but wrong octal value.
        final byte[] wrongChecksum = { '0', '0', '0', '0', '0', '0', 0, ' ' };
        System.arraycopy(wrongChecksum, 0, source, 148, wrongChecksum.length);
        final Path sourcePath = tempDir.resolve("broken.tar");
        final Path targetPath = tempDir.resolve("target.tar");
        Files.write(sourcePath, source);
        final byte[] oldTarget = "old target content".getBytes(StandardCharsets.UTF_8);
        Files.write(targetPath, oldTarget);
        assertThrows(IOException.class, () -> TarRepacker.builder().get().repack(sourcePath, targetPath));
        assertArrayEquals(oldTarget, Files.readAllBytes(targetPath));
    }

    @Test
    void testDuplicateNormalizedNamesRejected() throws IOException {
        final byte[] source = tar(fileEntry("a/./b.txt", 0, 0, 0, ""), new byte[0], fileEntry("a/b.txt", 0, 0, 0, ""), new byte[0]);
        assertThrows(ArchiveException.class, () -> repack(source));
    }

    @Test
    void testHardLinkForwardReference() throws IOException {
        final byte[] data = "payload".getBytes(StandardCharsets.UTF_8);
        final TarArchiveEntry target = fileEntry("z-target.txt", 0, 0, 0, "");
        final TarArchiveEntry link = new TarArchiveEntry("a-link.txt", TarConstants.LF_LINK);
        link.setLinkName("z-target.txt");
        final byte[] repacked = repack(tar(target, data, link));
        try (TarArchiveInputStream input = new TarArchiveInputStream(new ByteArrayInputStream(repacked))) {
            // The link sorts before its target, creating a forward reference.
            final TarArchiveEntry first = input.getNextEntry();
            assertEquals("a-link.txt", first.getName());
            assertTrue(first.isLink());
            assertEquals("z-target.txt", first.getLinkName());
            final TarArchiveEntry second = input.getNextEntry();
            assertEquals("z-target.txt", second.getName());
            assertArrayEquals(data, IOUtils.toByteArray(input));
        }
    }

    @Test
    void testHardLinkToMissingTargetRejected() throws IOException {
        final TarArchiveEntry link = new TarArchiveEntry("a-link.txt", TarConstants.LF_LINK);
        link.setLinkName("missing.txt");
        assertThrows(ArchiveException.class, () -> repack(tar(link)));
    }

    @Test
    void testLongUtf8PathRoundTripsDeterministically() throws IOException {
        final byte[] data = "content".getBytes(StandardCharsets.UTF_8);
        assertTrue(LONG_UTF8_NAME.getBytes(StandardCharsets.UTF_8).length > TarConstants.NAMELEN);
        final byte[] source = tar(fileEntry(LONG_UTF8_NAME, 1_700_000_000_000L, 42, 43, "bob"), data);
        final byte[] first = repack(source);
        final byte[] second = repack(source);
        assertArrayEquals(first, second);
        // Long names must be stored in deterministic POSIX PAX extended headers.
        assertTrue(new String(first, StandardCharsets.ISO_8859_1).contains("PaxHeaders"));
        final Map<String, byte[]> contents = readContents(first);
        assertArrayEquals(data, contents.get("dir/\u957f\u8def\u5f84/" + repeat("\u6570\u636e\u6587\u4ef6\u540d\u79f0", 30) + ".txt"));
    }

    @Test
    void testMidOutputFailurePropagates() throws IOException {
        final byte[] data = new byte[10_000];
        final byte[] source = tar(fileEntry("a.txt", 0, 0, 0, ""), data, fileEntry("b.txt", 0, 0, 0, ""), data);
        final OutputStream failingOutput = new OutputStream() {
            private int written;

            @Override
            public void write(final byte[] b, final int off, final int len) throws IOException {
                if (written + len > 1_000) {
                    throw new IOException("simulated output failure");
                }
                written += len;
            }

            @Override
            public void write(final int b) throws IOException {
                write(new byte[] { (byte) b }, 0, 1);
            }
        };
        assertThrows(IOException.class, () -> TarRepacker.builder().get().repack(new ByteArrayInputStream(source), failingOutput));
    }

    @Test
    void testNormalizesOrderAndMetadataToIdenticalBytes() throws IOException {
        final byte[] dataA = "alpha".getBytes(StandardCharsets.UTF_8);
        final byte[] dataB = "beta".getBytes(StandardCharsets.UTF_8);
        final byte[] dataLong = "long".getBytes(StandardCharsets.UTF_8);
        final TarArchiveEntry dir = new TarArchiveEntry("dir/");
        dir.setModTime(111_000L);
        dir.setUserId(7);
        dir.setUserName("carol");
        final byte[] first = tar(fileEntry("z.txt", 1_000_000L, 1, 2, "alice"), dataA, dir, fileEntry("dir/b.txt", 2_000_000L, 3, 4, "bob"), dataB,
                fileEntry(LONG_UTF8_NAME, 3_000_000L, 5, 6, "dave"), dataLong);
        final TarArchiveEntry dir2 = new TarArchiveEntry("dir/");
        dir2.setModTime(999_000L);
        dir2.setUserId(8);
        dir2.setUserName("mallory");
        final byte[] second = tar(fileEntry("dir/b.txt", 9_000_000L, 30, 40, "bob2"), dataB, fileEntry(LONG_UTF8_NAME, 8_000_000L, 50, 60, "dave2"), dataLong,
                dir2, fileEntry("z.txt", 7_000_000L, 10, 20, "alice2"), dataA);
        final byte[] repackedFirst = repack(first);
        final byte[] repackedSecond = repack(second);
        assertArrayEquals(repackedFirst, repackedSecond);
        // Entries come out sorted by normalized path with unified metadata.
        final List<String> names = new ArrayList<>();
        try (TarArchiveInputStream input = new TarArchiveInputStream(new ByteArrayInputStream(repackedFirst))) {
            TarArchiveEntry entry;
            while ((entry = input.getNextEntry()) != null) {
                names.add(entry.getName());
                assertEquals(FileTime.from(Instant.EPOCH), entry.getLastModifiedTime());
                assertEquals(0, entry.getLongUserId());
                assertEquals(0, entry.getLongGroupId());
                assertEquals("", entry.getUserName());
                assertEquals("", entry.getGroupName());
            }
        }
        assertEquals("dir/", names.get(0));
        assertEquals("dir/b.txt", names.get(1));
        assertEquals(LONG_UTF8_NAME, names.get(2));
        assertEquals("z.txt", names.get(3));
        // File contents are unchanged.
        final Map<String, byte[]> contents = readContents(repackedFirst);
        assertArrayEquals(dataA, contents.get("z.txt"));
        assertArrayEquals(dataB, contents.get("dir/b.txt"));
        assertArrayEquals(dataLong, contents.get(LONG_UTF8_NAME));
    }

    @Test
    void testRejectsAbsoluteEntryName() throws IOException {
        final TarArchiveEntry entry = new TarArchiveEntry("/absolute.txt", TarConstants.LF_NORMAL, true);
        entry.setSize(0);
        assertThrows(ArchiveException.class, () -> repack(tar(entry, new byte[0])));
    }

    @Test
    void testRejectsAbsoluteSymbolicLinkTarget() throws IOException {
        final TarArchiveEntry link = new TarArchiveEntry("link", TarConstants.LF_SYMLINK);
        link.setLinkName("/etc/passwd");
        assertThrows(ArchiveException.class, () -> repack(tar(link)));
    }

    @Test
    void testRejectsEscapingSymbolicLinkTarget() throws IOException {
        final TarArchiveEntry link = new TarArchiveEntry("dir/link", TarConstants.LF_SYMLINK);
        link.setLinkName("../../outside");
        assertThrows(ArchiveException.class, () -> repack(tar(link)));
    }

    @Test
    void testRejectsParentTraversal() throws IOException {
        final byte[] source = tar(fileEntry("../evil.txt", 0, 0, 0, ""), new byte[0]);
        assertThrows(ArchiveException.class, () -> repack(source));
    }

    @Test
    void testRejectsTraversalEscapingAfterNormalization() throws IOException {
        final byte[] source = tar(fileEntry("a/../../evil.txt", 0, 0, 0, ""), new byte[0]);
        assertThrows(ArchiveException.class, () -> repack(source));
    }

    @Test
    void testRepackPathToPath() throws IOException {
        final byte[] data = "content".getBytes(StandardCharsets.UTF_8);
        final Path sourcePath = tempDir.resolve("source.tar");
        final Path targetPath = tempDir.resolve("target.tar");
        Files.write(sourcePath, tar(fileEntry("b.txt", 5_000_000L, 9, 9, "user"), data, fileEntry("a.txt", 1_000L, 1, 1, "other"), data));
        TarRepacker.builder().setModificationTime(Instant.ofEpochSecond(1_600_000_000)).setUserId(1000).setGroupId(1000).setUserName("u").setGroupName("g").get()
                .repack(sourcePath, targetPath);
        try (TarArchiveInputStream input = new TarArchiveInputStream(Files.newInputStream(targetPath))) {
            final TarArchiveEntry first = input.getNextEntry();
            assertEquals("a.txt", first.getName());
            assertEquals(FileTime.from(Instant.ofEpochSecond(1_600_000_000)), first.getLastModifiedTime());
            assertEquals(1000, first.getLongUserId());
            assertEquals(1000, first.getLongGroupId());
            assertEquals("u", first.getUserName());
            assertEquals("g", first.getGroupName());
            assertArrayEquals(data, IOUtils.toByteArray(input));
            assertEquals("b.txt", input.getNextEntry().getName());
        }
    }

    @Test
    void testValidRelativeSymbolicLinkAccepted() throws IOException {
        final byte[] data = "x".getBytes(StandardCharsets.UTF_8);
        final TarArchiveEntry link = new TarArchiveEntry("dir/link", TarConstants.LF_SYMLINK);
        link.setLinkName("../target.txt");
        final byte[] repacked = repack(tar(link, fileEntry("target.txt", 0, 0, 0, ""), data));
        final Map<String, byte[]> contents = readContents(repacked);
        assertTrue(contents.containsKey("dir/link"));
        assertArrayEquals(data, contents.get("target.txt"));
    }
}
