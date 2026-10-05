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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

import org.apache.commons.compress.AbstractTest;
import org.apache.commons.compress.archivers.ArchiveException;
import org.apache.commons.io.IOUtils;
import org.junit.jupiter.api.Test;

/**
 * Tests {@link TarHardLinkIndex} and {@link TarFile#getHardLinkIndex()} against the small hard link fixtures
 * {@code hardlink-*.tar} in {@code src/test/resources}.
 *
 * <p>
 * Run only these tests with:
 * </p>
 *
 * <pre>
 * mvn -Dtest=TarHardLinkIndexTest test
 * </pre>
 *
 * or a single case, for example:
 *
 * <pre>
 * mvn -Dtest=TarHardLinkIndexTest#resolvesLinkThatAppearsBeforeItsTarget test
 * </pre>
 */
class TarHardLinkIndexTest extends AbstractTest {

    private TarArchiveEntry entryByName(final TarFile tarFile, final String name) {
        return tarFile.getEntries().stream().filter(entry -> entry.getName().equals(name)).findFirst()
                .orElseThrow(() -> new AssertionError("no entry named " + name));
    }

    private byte[] read(final InputStream inputStream) throws Exception {
        try (InputStream in = inputStream) {
            return IOUtils.toByteArray(in);
        }
    }

    @Test
    void resolvesLinkThatAppearsBeforeItsTarget() throws Exception {
        try (TarFile tarFile = TarFile.builder().setPath(getPath("hardlink-forward.tar")).get()) {
            final List<TarArchiveEntry> entries = tarFile.getEntries();
            // the link comes first, the real file later
            assertEquals("link.txt", entries.get(0).getName());
            assertTrue(entries.get(0).isLink());
            assertEquals("data.txt", entries.get(1).getName());

            final TarHardLinkIndex index = tarFile.getHardLinkIndex();
            final TarHardLinkIndex.Resolution resolution = index.resolve(tarFile, entries.get(0));
            assertEquals(TarHardLinkIndex.Status.RESOLVED, resolution.getStatus());
            assertTrue(resolution.isResolved());
            // final content entry identity and reference chain
            assertSame(entries.get(1), resolution.getContentEntry());
            assertEquals(Arrays.asList("link.txt", "data.txt"), resolution.getChainNames());

            // the hard link is not an empty file: both entries read the same content
            final byte[] viaLink = read(index.getInputStream(tarFile, entries.get(0)));
            final byte[] viaTarget = read(tarFile.getInputStream(entries.get(1)));
            assertArrayEquals(viaTarget, viaLink);
            assertEquals("hello hard link content\n", new String(viaLink, StandardCharsets.UTF_8));
        }
    }

    @Test
    void resolvesChainOfLinks() throws Exception {
        try (TarFile tarFile = TarFile.builder().setPath(getPath("hardlink-chain.tar")).get()) {
            final TarHardLinkIndex index = tarFile.getHardLinkIndex();
            final TarHardLinkIndex.Resolution resolution = index.resolve(tarFile, "l1.txt");
            assertEquals(TarHardLinkIndex.Status.RESOLVED, resolution.getStatus());
            assertEquals("real.txt", resolution.getContentEntry().getName());
            assertEquals(Arrays.asList("l1.txt", "l2.txt", "real.txt"), resolution.getChainNames());
            assertEquals("chained content\n",
                    new String(read(index.getInputStream(tarFile, entryByName(tarFile, "l1.txt"))), StandardCharsets.UTF_8));
        }
    }

    @Test
    void reportsFullCycle() throws Exception {
        try (TarFile tarFile = TarFile.builder().setPath(getPath("hardlink-cycle.tar")).get()) {
            final TarHardLinkIndex index = tarFile.getHardLinkIndex();

            final TarHardLinkIndex.Resolution fromA = index.resolve(tarFile, "a");
            assertEquals(TarHardLinkIndex.Status.CYCLE, fromA.getStatus());
            assertEquals(Arrays.asList("a", "b", "a"), fromA.getChainNames());
            assertNull(fromA.getContentEntry());
            assertTrue(fromA.getMessage().contains("a -> b -> a"), fromA.getMessage());

            final TarHardLinkIndex.Resolution fromB = index.resolve(tarFile, "b");
            assertEquals(TarHardLinkIndex.Status.CYCLE, fromB.getStatus());
            assertEquals(Arrays.asList("b", "a", "b"), fromB.getChainNames());

            // a cyclic link cannot be read as content
            assertThrows(ArchiveException.class, () -> index.getInputStream(tarFile, entryByName(tarFile, "a")));
        }
    }

    @Test
    void reportsMissingTarget() throws Exception {
        try (TarFile tarFile = TarFile.builder().setPath(getPath("hardlink-missing.tar")).get()) {
            final TarHardLinkIndex index = tarFile.getHardLinkIndex();
            final TarHardLinkIndex.Resolution resolution = index.resolve(tarFile, "broken.txt");
            assertEquals(TarHardLinkIndex.Status.MISSING_TARGET, resolution.getStatus());
            assertEquals(Arrays.asList("broken.txt"), resolution.getChainNames());
            assertNull(resolution.getContentEntry());
            assertTrue(resolution.getMessage().contains("no/such/target.txt"), resolution.getMessage());
            assertThrows(ArchiveException.class, () -> index.getInputStream(tarFile, entryByName(tarFile, "broken.txt")));
        }
    }

    @Test
    void reportsOutOfBoundsTarget() throws Exception {
        try (TarFile tarFile = TarFile.builder().setPath(getPath("hardlink-escape.tar")).get()) {
            final TarHardLinkIndex index = tarFile.getHardLinkIndex();
            final TarHardLinkIndex.Resolution resolution = index.resolve(tarFile, "evil.txt");
            assertEquals(TarHardLinkIndex.Status.OUT_OF_BOUNDS, resolution.getStatus());
            assertEquals(Arrays.asList("evil.txt"), resolution.getChainNames());
            assertNull(resolution.getContentEntry());
            assertTrue(resolution.getMessage().contains("../outside.txt"), resolution.getMessage());
        }
    }

    @Test
    void reportsAmbiguousTarget() throws Exception {
        try (TarFile tarFile = TarFile.builder().setPath(getPath("hardlink-ambiguous.tar")).get()) {
            final TarHardLinkIndex index = tarFile.getHardLinkIndex();
            final TarHardLinkIndex.Resolution resolution = index.resolve(tarFile, "lnk.txt");
            assertEquals(TarHardLinkIndex.Status.AMBIGUOUS_TARGET, resolution.getStatus());
            assertEquals(Arrays.asList("lnk.txt"), resolution.getChainNames());
            assertNull(resolution.getContentEntry());
            assertTrue(resolution.getMessage().contains("dup.txt"), resolution.getMessage());
        }
    }

    @Test
    void refusesStaleIndexAfterArchiveIsReplaced() throws Exception {
        final TarHardLinkIndex index;
        final String originalDigest;
        try (TarFile tarFile = TarFile.builder().setPath(getPath("hardlink-forward.tar")).get()) {
            index = tarFile.getHardLinkIndex();
            originalDigest = tarFile.getArchiveDigest();
            assertEquals(originalDigest, index.getArchiveDigest());
        }
        // reopening the same archive yields the same digest and the index still serves it
        try (TarFile sameArchive = TarFile.builder().setPath(getPath("hardlink-forward.tar")).get()) {
            assertEquals(originalDigest, sameArchive.getArchiveDigest());
            assertEquals(TarHardLinkIndex.Status.RESOLVED, index.resolve(sameArchive, "link.txt").getStatus());
        }
        // a replaced archive has a different digest and the old index refuses to serve it
        try (TarFile replaced = TarFile.builder().setPath(getPath("hardlink-missing.tar")).get()) {
            assertNotEquals(originalDigest, replaced.getArchiveDigest());
            assertThrows(ArchiveException.class, () -> index.checkArchive(replaced));
            assertThrows(ArchiveException.class, () -> index.resolve(replaced, "broken.txt"));
            assertThrows(ArchiveException.class, () -> index.getInputStream(replaced, entryByName(replaced, "broken.txt")));
            // the replaced archive gets its own fresh index
            assertNotNull(replaced.getHardLinkIndex());
        }
    }

    @Test
    void nonLinkEntriesResolveToThemselvesAndNormalReadingIsUnchanged() throws Exception {
        try (TarFile tarFile = TarFile.builder().setPath(getPath("hardlink-forward.tar")).get()) {
            final TarHardLinkIndex index = tarFile.getHardLinkIndex();
            assertSame(index, tarFile.getHardLinkIndex(), "index is cached per TarFile");

            final TarArchiveEntry data = entryByName(tarFile, "data.txt");
            final TarHardLinkIndex.Resolution resolution = index.resolve(tarFile, data);
            assertTrue(resolution.isResolved());
            assertSame(data, resolution.getContentEntry());
            assertEquals(Arrays.asList("data.txt"), resolution.getChainNames());

            // plain TarFile behavior is untouched: a hard link entry itself still carries no data
            final TarArchiveEntry link = entryByName(tarFile, "link.txt");
            assertEquals(0, link.getSize());
            assertEquals(0, read(tarFile.getInputStream(link)).length);
            assertFalse(data.isLink());
            assertEquals(2, tarFile.getEntries().size());
        }
    }
}
