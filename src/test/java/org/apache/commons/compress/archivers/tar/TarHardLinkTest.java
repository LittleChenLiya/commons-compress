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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.apache.commons.compress.AbstractTest;
import org.apache.commons.compress.archivers.ArchiveException;
import org.apache.commons.io.IOUtils;
import org.junit.jupiter.api.Test;

/**
 * Tests for the hard link index of {@link TarFile}.
 */
class TarHardLinkTest extends AbstractTest {

    private static final byte[] CONTENT = "hello hard link content".getBytes(StandardCharsets.UTF_8);

    private static TarArchiveEntry linkEntry(final String name, final String linkName) {
        final TarArchiveEntry entry = new TarArchiveEntry(name, TarConstants.LF_LINK);
        entry.setLinkName(linkName);
        return entry;
    }

    private static TarArchiveEntry fileEntry(final String name, final byte[] data) throws ArchiveException {
        final TarArchiveEntry entry = new TarArchiveEntry(name);
        entry.setSize(data.length);
        return entry;
    }

    private static byte[] tarWithLinkFirst() throws IOException {
        final ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (TarArchiveOutputStream tos = new TarArchiveOutputStream(bos)) {
            // The link appears before the real file it points to.
            tos.putArchiveEntry(linkEntry("link", "target"));
            tos.closeArchiveEntry();
            tos.putArchiveEntry(fileEntry("target", CONTENT));
            tos.write(CONTENT);
            tos.closeArchiveEntry();
        }
        return bos.toByteArray();
    }

    private static byte[] tarWithCycle() throws IOException {
        final ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (TarArchiveOutputStream tos = new TarArchiveOutputStream(bos)) {
            tos.putArchiveEntry(linkEntry("a", "b"));
            tos.closeArchiveEntry();
            tos.putArchiveEntry(linkEntry("b", "a"));
            tos.closeArchiveEntry();
        }
        return bos.toByteArray();
    }

    private static byte[] tarWithMissingTarget() throws IOException {
        final ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (TarArchiveOutputStream tos = new TarArchiveOutputStream(bos)) {
            tos.putArchiveEntry(linkEntry("link", "missing"));
            tos.closeArchiveEntry();
        }
        return bos.toByteArray();
    }

    private static byte[] tarWithOutOfBoundsLink() throws IOException {
        final ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (TarArchiveOutputStream tos = new TarArchiveOutputStream(bos)) {
            tos.putArchiveEntry(linkEntry("link", "../escape"));
            tos.closeArchiveEntry();
        }
        return bos.toByteArray();
    }

    private static byte[] tarWithAmbiguousTarget() throws IOException {
        final ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (TarArchiveOutputStream tos = new TarArchiveOutputStream(bos)) {
            tos.putArchiveEntry(linkEntry("link", "target"));
            tos.closeArchiveEntry();
            tos.putArchiveEntry(fileEntry("target", CONTENT));
            tos.write(CONTENT);
            tos.closeArchiveEntry();
            tos.putArchiveEntry(fileEntry("target", CONTENT));
            tos.write(CONTENT);
            tos.closeArchiveEntry();
        }
        return bos.toByteArray();
    }

    private static byte[] tarWithMultiHopChain() throws IOException {
        final ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (TarArchiveOutputStream tos = new TarArchiveOutputStream(bos)) {
            // a points to b, b points to c, c points to the real file.
            tos.putArchiveEntry(linkEntry("a", "b"));
            tos.closeArchiveEntry();
            tos.putArchiveEntry(linkEntry("b", "c"));
            tos.closeArchiveEntry();
            tos.putArchiveEntry(linkEntry("c", "real"));
            tos.closeArchiveEntry();
            tos.putArchiveEntry(fileEntry("real", CONTENT));
            tos.write(CONTENT);
            tos.closeArchiveEntry();
        }
        return bos.toByteArray();
    }

    /**
     * link first, real file later: reading the link yields the same content as the real file.
     */
    @Test
    void testLinkBeforeTargetReadsSameContent() throws IOException {
        final byte[] data = tarWithLinkFirst();
        try (TarFile tarFile = TarFile.builder().setByteArray(data).get()) {
            final List<TarArchiveEntry> entries = tarFile.getEntries();
            assertEquals(2, entries.size());
            final TarArchiveEntry link = entries.get(0);
            final TarArchiveEntry target = entries.get(1);
            assertTrue(link.isLink());
            assertTrue(target.isFile());

            // Reading the link directly resolves it to the content entry.
            try (InputStream in = tarFile.getInputStream(link)) {
                assertArrayEquals(CONTENT, IOUtils.toByteArray(in));
            }
            try (InputStream in = tarFile.getInputStream(target)) {
                assertArrayEquals(CONTENT, IOUtils.toByteArray(in));
            }

            // The index exposes the content entry identity and the reference chain.
            final TarHardLinkIndex index = tarFile.buildHardLinkIndex();
            assertEquals(1, index.size());
            assertEquals(target, index.getContentEntry(link));
            final List<TarArchiveEntry> chain = index.getChain(link);
            assertEquals(2, chain.size());
            assertEquals(link, chain.get(0));
            assertEquals(target, chain.get(1));
        }
    }

    /**
     * a points to b and b points to a: the full cycle is reported.
     */
    @Test
    void testCycleReportsCompleteChain() throws IOException {
        final byte[] data = tarWithCycle();
        try (TarFile tarFile = TarFile.builder().setByteArray(data).get()) {
            final TarHardLinkException ex = assertThrows(TarHardLinkException.class, tarFile::buildHardLinkIndex);
            assertEquals(TarHardLinkException.Reason.CYCLE, ex.getReason());
            final List<String> chain = ex.getChain();
            assertTrue(chain.contains("a"), "chain should contain a: " + chain);
            assertTrue(chain.contains("b"), "chain should contain b: " + chain);
            assertEquals("a", chain.get(0));
            assertEquals("b", chain.get(1));
            assertEquals("a", chain.get(2));
        }
    }

    /**
     * A link whose target does not exist is reported separately.
     */
    @Test
    void testMissingTargetReportedSeparately() throws IOException {
        final byte[] data = tarWithMissingTarget();
        try (TarFile tarFile = TarFile.builder().setByteArray(data).get()) {
            final TarHardLinkException ex = assertThrows(TarHardLinkException.class, tarFile::buildHardLinkIndex);
            assertEquals(TarHardLinkException.Reason.MISSING_TARGET, ex.getReason());
            assertEquals("link", ex.getLinkName());
            assertEquals("missing", ex.getTargetName());
        }
    }

    /**
     * A link path that escapes the archive root is reported as out of bounds.
     */
    @Test
    void testOutOfBoundsReportedSeparately() throws IOException {
        final byte[] data = tarWithOutOfBoundsLink();
        try (TarFile tarFile = TarFile.builder().setByteArray(data).get()) {
            final TarHardLinkException ex = assertThrows(TarHardLinkException.class, tarFile::buildHardLinkIndex);
            assertEquals(TarHardLinkException.Reason.OUT_OF_BOUNDS, ex.getReason());
        }
    }

    /**
     * A link whose target name matches more than one entry is reported as ambiguous.
     */
    @Test
    void testAmbiguousTargetReportedSeparately() throws IOException {
        final byte[] data = tarWithAmbiguousTarget();
        try (TarFile tarFile = TarFile.builder().setByteArray(data).get()) {
            final TarHardLinkException ex = assertThrows(TarHardLinkException.class, tarFile::buildHardLinkIndex);
            assertEquals(TarHardLinkException.Reason.AMBIGUOUS_TARGET, ex.getReason());
            assertEquals("target", ex.getTargetName());
        }
    }

    /**
     * Once the archive is replaced, an index built for the old archive is refused.
     */
    @Test
    void testIndexRejectedAfterArchiveReplaced() throws IOException {
        final Path archive = tempResultDir.toPath().resolve("archive.tar");
        Files.write(archive, tarWithLinkFirst());
        final TarHardLinkIndex index;
        try (TarFile tarFile = TarFile.builder().setPath(archive).get()) {
            index = tarFile.buildHardLinkIndex();
            assertNotNull(index.getArchiveDigest());
        }
        // Replace the archive with a different one.
        Files.write(archive, tarWithCycle());
        try (TarFile tarFile = TarFile.builder().setPath(archive).get()) {
            final TarArchiveEntry link = tarFile.getEntries().get(0);
            final IOException ex = assertThrows(IOException.class, () -> tarFile.getInputStream(link, index));
            assertTrue(ex.getMessage().contains("different archive"), "message should mention the mismatch: " + ex.getMessage());
        }
    }

    /**
     * A chain of hard links is followed to the final content entry and the full reference chain is recorded.
     */
    @Test
    void testMultiHopChainRecordsFullReferenceChain() throws IOException {
        final byte[] data = tarWithMultiHopChain();
        try (TarFile tarFile = TarFile.builder().setByteArray(data).get()) {
            final TarHardLinkIndex index = tarFile.buildHardLinkIndex();
            assertEquals(3, index.size());
            final TarArchiveEntry a = tarFile.getEntries().get(0);
            final TarArchiveEntry real = tarFile.getEntries().get(3);
            assertEquals(real, index.getContentEntry(a));
            final List<TarArchiveEntry> chain = index.getChain(a);
            assertEquals(4, chain.size());
            assertEquals("a", chain.get(0).getName());
            assertEquals("b", chain.get(1).getName());
            assertEquals("c", chain.get(2).getName());
            assertEquals("real", chain.get(3).getName());
            // Reading the first link yields the content of the final real file.
            try (InputStream in = tarFile.getInputStream(a)) {
                assertArrayEquals(CONTENT, IOUtils.toByteArray(in));
            }
        }
    }

    /**
     * Building the index does not extract anything to the file system.
     */
    @Test
    void testIndexBuildDoesNotExtractToFileSystem() throws IOException {
        final Path extractionDir = createTempDirectory("hardlink-extract");
        final byte[] data = tarWithLinkFirst();
        try (TarFile tarFile = TarFile.builder().setByteArray(data).get()) {
            final TarHardLinkIndex index = tarFile.buildHardLinkIndex();
            assertEquals(1, index.size());
        }
        // The extraction directory must remain empty: the index only reads raw archive bytes.
        assertEquals(0, Files.list(extractionDir).count());
    }
}
