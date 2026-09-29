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

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
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

    private static final class Entry {

        private final TarArchiveEntry entry;
        private final byte[] content;

        private Entry(final TarArchiveEntry entry, final byte[] content) {
            this.entry = entry;
            this.content = content;
        }
    }

    /**
     * An output stream that fails once the given number of bytes has been written.
     */
    private static final class FailingOutputStream extends OutputStream {

        private final int failAfter;
        private int written;

        private FailingOutputStream(final int failAfter) {
            this.failAfter = failAfter;
        }

        @Override
        public void write(final int b) throws IOException {
            if (written >= failAfter) {
                throw new IOException("Simulated output failure");
            }
            written++;
        }

        @Override
        public void write(final byte[] b, final int off, final int len) throws IOException {
            for (int i = off; i < off + len; i++) {
                write(b[i]);
            }
        }
    }

    private static final Instant CANONICAL_TIME = Instant.ofEpochSecond(1_600_000_000);

    @TempDir
    Path tempDir;

    private static Entry directory(final String name, final long modTimeSeconds, final int userId) {
        final TarArchiveEntry entry = new TarArchiveEntry(name, TarConstants.LF_DIR);
        scramble(entry, modTimeSeconds, userId);
        return new Entry(entry, null);
    }

    private static Entry file(final String name, final String content, final long modTimeSeconds, final int userId) throws IOException {
        final byte[] bytes = content.getBytes(UTF_8);
        final TarArchiveEntry entry = new TarArchiveEntry(name);
        entry.setSize(bytes.length);
        scramble(entry, modTimeSeconds, userId);
        return new Entry(entry, bytes);
    }

    private static Entry hardLink(final String name, final String target, final long modTimeSeconds, final int userId) {
        final TarArchiveEntry entry = new TarArchiveEntry(name, TarConstants.LF_LINK);
        entry.setLinkName(target);
        scramble(entry, modTimeSeconds, userId);
        return new Entry(entry, null);
    }

    private static Entry symbolicLink(final String name, final String target, final long modTimeSeconds, final int userId) {
        final TarArchiveEntry entry = new TarArchiveEntry(name, TarConstants.LF_SYMLINK);
        entry.setLinkName(target);
        scramble(entry, modTimeSeconds, userId);
        return new Entry(entry, null);
    }

    private static void scramble(final TarArchiveEntry entry, final long modTimeSeconds, final int userId) {
        entry.setModTime(modTimeSeconds * 1000);
        entry.setUserId(userId);
        entry.setGroupId(userId + 1);
        entry.setUserName("user" + userId);
        entry.setGroupName("group" + userId);
    }

    private static byte[] createTar(final int longFileMode, final Entry... entries) throws IOException {
        final ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (TarArchiveOutputStream tarOutput = new TarArchiveOutputStream(baos)) {
            tarOutput.setLongFileMode(longFileMode);
            for (final Entry entry : entries) {
                tarOutput.putArchiveEntry(entry.entry);
                if (entry.content != null) {
                    tarOutput.write(entry.content);
                }
                tarOutput.closeArchiveEntry();
            }
        }
        return baos.toByteArray();
    }

    private static byte[] createTar(final Entry... entries) throws IOException {
        return createTar(TarArchiveOutputStream.LONGFILE_POSIX, entries);
    }

    private static TarRepacker newRepacker() {
        return TarRepacker.builder().setModTime(CANONICAL_TIME).setUserId(1000).setGroupId(100).setUserName("repro").setGroupName("repro")
                .build();
    }

    private static Map<String, byte[]> readFiles(final byte[] tarBytes) throws IOException {
        final Map<String, byte[]> files = new LinkedHashMap<>();
        try (TarArchiveInputStream tarInput = new TarArchiveInputStream(new ByteArrayInputStream(tarBytes))) {
            TarArchiveEntry entry;
            while ((entry = tarInput.getNextEntry()) != null) {
                files.put(entry.getName(), entry.isFile() ? IOUtils.toByteArray(tarInput) : null);
            }
        }
        return files;
    }

    private byte[] repack(final byte[] source) throws IOException {
        final ByteArrayOutputStream baos = new ByteArrayOutputStream();
        newRepacker().repack(new ByteArrayInputStream(source), baos);
        return baos.toByteArray();
    }

    @Test
    void normalizesEquivalentArchivesToIdenticalBytes() throws IOException {
        final byte[] first = createTar(
                file("b.txt", "world", 1_700_000_001, 10),
                directory("dir/", 1_700_000_002, 20),
                file("dir/a.txt", "hello", 1_700_000_003, 30),
                file("c.txt", "!", 1_700_000_004, 40));
        final Entry paxEntry = file("c.txt", "!", 1_600_000_004, 140);
        paxEntry.entry.addPaxHeader("atime", "1700000100.5");
        paxEntry.entry.addPaxHeader("ctime", "1700000101.25");
        final byte[] second = createTar(
                file("dir/a.txt", "hello", 1_600_000_001, 110),
                paxEntry,
                directory("dir/", 1_600_000_002, 120),
                file("b.txt", "world", 1_600_000_003, 130));

        final byte[] repackedFirst = repack(first);
        final byte[] repackedSecond = repack(second);
        assertArrayEquals(repackedFirst, repackedSecond);

        // Re-reading both outputs yields the same per-file contents.
        final Map<String, byte[]> filesFirst = readFiles(repackedFirst);
        final Map<String, byte[]> filesSecond = readFiles(repackedSecond);
        assertEquals(filesFirst.keySet().toString(), filesSecond.keySet().toString());
        assertArrayEquals("hello".getBytes(UTF_8), filesFirst.get("dir/a.txt"));
        assertArrayEquals("world".getBytes(UTF_8), filesFirst.get("b.txt"));
        assertArrayEquals("!".getBytes(UTF_8), filesFirst.get("c.txt"));

        // Entries are sorted by normalized name and carry canonical metadata only.
        assertEquals(Arrays.asList("b.txt", "c.txt", "dir/", "dir/a.txt"), new ArrayList<>(filesFirst.keySet()));
        try (TarArchiveInputStream tarInput = new TarArchiveInputStream(new ByteArrayInputStream(repackedFirst))) {
            TarArchiveEntry entry;
            while ((entry = tarInput.getNextEntry()) != null) {
                assertEquals(Date.from(CANONICAL_TIME), entry.getModTime(), entry.getName());
                assertEquals(1000, entry.getLongUserId(), entry.getName());
                assertEquals(100, entry.getLongGroupId(), entry.getName());
                assertEquals("repro", entry.getUserName(), entry.getName());
                assertEquals("repro", entry.getGroupName(), entry.getName());
                assertTrue(entry.getExtraPaxHeaders().isEmpty(), entry.getName());
            }
        }
    }

    @Test
    void writesLongUtf8PathsWithDeterministicPaxHeaders() throws IOException {
        final String longName = "dir/" + String.join("", Collections.nCopies(30, "\u957f\u8def\u5f84")) + ".txt";
        assertTrue(longName.getBytes(UTF_8).length > 100);
        final byte[] gnuStyle = createTar(TarArchiveOutputStream.LONGFILE_GNU, file(longName, "content", 1_700_000_000, 10));
        final byte[] paxStyle = createTar(TarArchiveOutputStream.LONGFILE_POSIX, file(longName, "content", 1_600_000_000, 20));

        final byte[] repackedGnu = repack(gnuStyle);
        final byte[] repackedPax = repack(paxStyle);
        assertArrayEquals(repackedGnu, repackedPax);
        // Long names are stored using deterministic POSIX PAX extended headers.
        assertTrue(new String(repackedGnu, UTF_8).contains("PaxHeaders"));
        assertArrayEquals("content".getBytes(UTF_8), readFiles(repackedGnu).get(longName));
    }

    @Test
    void supportsHardLinkForwardReference() throws IOException {
        // The hard link refers to a file that only appears later in the source archive.
        final byte[] source = createTar(
                hardLink("links/b.txt", "data/a.txt", 1_700_000_000, 10),
                file("data/a.txt", "payload", 1_700_000_001, 20));
        final byte[] repacked = repack(source);
        assertArrayEquals(repacked, repack(source));

        final Map<String, String> links = new LinkedHashMap<>();
        try (TarArchiveInputStream tarInput = new TarArchiveInputStream(new ByteArrayInputStream(repacked))) {
            TarArchiveEntry entry;
            while ((entry = tarInput.getNextEntry()) != null) {
                if (entry.isLink()) {
                    links.put(entry.getName(), entry.getLinkName());
                }
            }
        }
        assertEquals(Collections.singletonMap("links/b.txt", "data/a.txt"), links);
        assertArrayEquals("payload".getBytes(UTF_8), readFiles(repacked).get("data/a.txt"));
    }

    @Test
    void rejectsDuplicateNormalizedNames() throws IOException {
        final byte[] source = createTar(file("./dup.txt", "one", 1_700_000_000, 10), file("dup.txt", "two", 1_700_000_001, 20));
        assertThrows(ArchiveException.class, () -> repack(source));
        assertTargetUnchangedAfterFailure(source);
    }

    @Test
    void rejectsAbsolutePaths() throws IOException {
        final TarArchiveEntry absolute = new TarArchiveEntry("/abs.txt", true);
        absolute.setSize(1);
        final byte[] source = createTar(new Entry(absolute, new byte[] { 'x' }));
        assertThrows(ArchiveException.class, () -> repack(source));
        assertTargetUnchangedAfterFailure(source);
    }

    @Test
    void rejectsParentTraversal() throws IOException {
        final byte[] source = createTar(file("a/../../evil.txt", "x", 1_700_000_000, 10));
        assertThrows(ArchiveException.class, () -> repack(source));
        assertTargetUnchangedAfterFailure(source);
    }

    @Test
    void rejectsLinksPointingOutsideArchive() throws IOException {
        assertThrows(ArchiveException.class,
                () -> repack(createTar(symbolicLink("link", "../outside.txt", 1_700_000_000, 10), file("ok.txt", "x", 1_700_000_001, 20))));
        assertThrows(ArchiveException.class,
                () -> repack(createTar(symbolicLink("link", "/etc/passwd", 1_700_000_000, 10), file("ok.txt", "x", 1_700_000_001, 20))));
        assertThrows(ArchiveException.class,
                () -> repack(createTar(hardLink("hard", "missing.txt", 1_700_000_000, 10), file("ok.txt", "x", 1_700_000_001, 20))));
    }

    @Test
    void acceptsLinksStayingInsideArchive() throws IOException {
        final byte[] source = createTar(
                symbolicLink("dir/link", "../ok.txt", 1_700_000_000, 10),
                hardLink("hard", "ok.txt", 1_700_000_001, 20),
                file("ok.txt", "x", 1_700_000_002, 30));
        final Map<String, byte[]> files = readFiles(repack(source));
        assertEquals(Arrays.asList("dir/link", "hard", "ok.txt"), new ArrayList<>(files.keySet()));
    }

    @Test
    void leavesTargetUnchangedOnBadChecksum() throws IOException {
        final byte[] source = createTar(file("first.txt", "one", 1_700_000_000, 10), file("second.txt", "two", 1_700_000_001, 20));
        // Corrupt the name field of the second entry's header without fixing the checksum.
        final int secondHeaderOffset = 512 + 512;
        source[secondHeaderOffset] = (byte) (source[secondHeaderOffset] + 1);

        final Path sourcePath = tempDir.resolve("corrupt.tar");
        Files.write(sourcePath, source);
        final Path target = tempDir.resolve("target.tar");
        final byte[] sentinel = "previous content".getBytes(UTF_8);
        Files.write(target, sentinel);

        assertThrows(IOException.class, () -> newRepacker().repack(sourcePath, target));
        assertArrayEquals(sentinel, Files.readAllBytes(target));
        // No temporary files are left behind.
        assertEquals(Arrays.asList("corrupt.tar", "target.tar"), sortedNames(tempDir));
    }

    @Test
    void propagatesMidOutputFailure() throws IOException {
        final byte[] source = createTar(file("a.txt", "one", 1_700_000_000, 10), file("b.txt", "two", 1_700_000_001, 20));
        // Fails once the first 512 byte record has been written.
        assertThrows(IOException.class, () -> newRepacker().repack(new ByteArrayInputStream(source), new FailingOutputStream(600)));
    }

    private void assertTargetUnchangedAfterFailure(final byte[] source) throws IOException {
        final Path sourcePath = tempDir.resolve("source.tar");
        Files.write(sourcePath, source);
        final Path target = tempDir.resolve("target.tar");
        final byte[] sentinel = "previous content".getBytes(UTF_8);
        Files.write(target, sentinel);
        try {
            assertThrows(IOException.class, () -> newRepacker().repack(sourcePath, target));
            assertArrayEquals(sentinel, Files.readAllBytes(target));
        } finally {
            Files.deleteIfExists(sourcePath);
            Files.deleteIfExists(target);
        }
    }

    private List<String> sortedNames(final Path directory) {
        final String[] names = directory.toFile().list();
        final List<String> sorted = new ArrayList<>(Arrays.asList(names));
        sorted.sort(null);
        return sorted;
    }
}
