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

import static org.apache.commons.compress.AbstractTest.getPath;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;

import org.apache.commons.compress.archivers.ArchiveException;
import org.apache.commons.compress.utils.IOUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests {@link ZipArchiveMerger}.
 */
class ZipArchiveMergerTest {

    private static final class Spec {

        private final String name;

        private final byte[] data;

        private final int method;

        private final String comment;

        private final long time;

        private Spec(final String name, final byte[] data, final int method, final String comment, final long time) {
            this.name = name;
            this.data = data;
            this.method = method;
            this.comment = comment;
            this.time = time;
        }
    }

    @TempDir
    Path tempDir;

    private static void addSpec(final ZipArchiveOutputStream out, final Spec spec) throws IOException {
        final ZipArchiveEntry entry = new ZipArchiveEntry(spec.name);
        entry.setMethod(spec.method);
        if (spec.time > 0) {
            entry.setTime(spec.time);
        }
        if (spec.comment != null) {
            entry.setComment(spec.comment);
        }
        if (spec.data == null) {
            // Directory entry.
            entry.setSize(0);
            out.putArchiveEntry(entry);
            out.closeArchiveEntry();
            return;
        }
        if (spec.method == ZipEntry.STORED) {
            entry.setSize(spec.data.length);
            entry.setCompressedSize(spec.data.length);
            entry.setCrc(crc(spec.data));
        } else {
            entry.setSize(spec.data.length);
        }
        out.putArchiveEntry(entry);
        out.write(spec.data);
        out.closeArchiveEntry();
    }

    private static long crc(final byte[] data) {
        final java.util.zip.CRC32 crc = new java.util.zip.CRC32();
        crc.update(data);
        return crc.getValue();
    }

    private static byte[] data(final String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private Path createZip(final String fileName, final List<Spec> specs) throws IOException {
        return createZip(fileName, specs, Zip64Mode.AsNeeded);
    }

    private Path createZip(final String fileName, final List<Spec> specs, final Zip64Mode zip64Mode) throws IOException {
        final Path file = tempDir.resolve(fileName);
        try (ZipArchiveOutputStream out = new ZipArchiveOutputStream(file)) {
            out.setUseZip64(zip64Mode);
            for (final Spec spec : specs) {
                addSpec(out, spec);
            }
        }
        return file;
    }

    /**
     * Reads every entry of the given archive into a name -> content map.
     */
    private Map<String, byte[]> readContents(final Path zip) throws IOException {
        final Map<String, byte[]> result = new LinkedHashMap<>();
        try (ZipFile zf = ZipFile.builder().setPath(zip).get()) {
            final java.util.Enumeration<ZipArchiveEntry> entries = zf.getEntriesInPhysicalOrder();
            while (entries.hasMoreElements()) {
                final ZipArchiveEntry entry = entries.nextElement();
                final byte[] bytes;
                if (entry.isDirectory()) {
                    bytes = new byte[0];
                } else {
                    try (java.io.InputStream in = zf.getInputStream(entry)) {
                        bytes = IOUtils.toByteArray(in);
                    }
                }
                result.put(entry.getName(), bytes);
            }
        }
        return result;
    }

    /**
     * Returns the raw compressed bytes and metadata of every entry in the archive.
     */
    private Map<String, RawEntry> readRaw(final Path zip) throws IOException {
        final Map<String, RawEntry> result = new LinkedHashMap<>();
        try (ZipFile zf = ZipFile.builder().setPath(zip).get()) {
            final java.util.Enumeration<ZipArchiveEntry> entries = zf.getEntriesInPhysicalOrder();
            while (entries.hasMoreElements()) {
                final ZipArchiveEntry entry = entries.nextElement();
                final byte[] raw;
                try (java.io.InputStream in = zf.getRawInputStream(entry)) {
                    raw = IOUtils.toByteArray(in);
                }
                result.put(entry.getName(), new RawEntry(entry, raw));
            }
        }
        return result;
    }

    private static final class RawEntry {

        private final ZipArchiveEntry entry;

        private final byte[] raw;

        private RawEntry(final ZipArchiveEntry entry, final byte[] raw) {
            this.entry = entry;
            this.raw = raw;
        }
    }

    private static Spec fileSpec(final String name, final String content, final int method, final String comment, final long time) {
        return new Spec(name, data(content), method, comment, time);
    }

    private static Spec dirSpec(final String name) {
        return new Spec(name, null, ZipEntry.STORED, null, 0);
    }

    /**
     * Creates an archive through a non-seekable stream so deflated entries use data descriptors.
     */
    private Path createZipWithDataDescriptors(final String fileName, final List<Spec> specs) throws IOException {
        final Path file = tempDir.resolve(fileName);
        try (OutputStream sink = Files.newOutputStream(file);
                ZipArchiveOutputStream out = new ZipArchiveOutputStream(sink)) {
            for (final Spec spec : specs) {
                final ZipArchiveEntry entry = new ZipArchiveEntry(spec.name);
                entry.setMethod(spec.method);
                if (spec.time > 0) {
                    entry.setTime(spec.time);
                }
                if (spec.comment != null) {
                    entry.setComment(spec.comment);
                }
                if (spec.data == null) {
                    out.putArchiveEntry(entry);
                    out.closeArchiveEntry();
                } else if (spec.method == ZipEntry.STORED) {
                    entry.setSize(spec.data.length);
                    entry.setCompressedSize(spec.data.length);
                    entry.setCrc(crc(spec.data));
                    out.putArchiveEntry(entry);
                    out.write(spec.data);
                    out.closeArchiveEntry();
                } else {
                    // Deflated: sizes are deliberately not provided so CRC and sizes go into a data descriptor.
                    out.putArchiveEntry(entry);
                    out.write(spec.data);
                    out.closeArchiveEntry();
                }
            }
        }
        return file;
    }

    @Test
    void testMergeStoredAndDeflatedPreservesContentAndRawBytes() throws Exception {
        final long t1 = 1_600_000_000_000L;
        final long t2 = 1_700_000_000_000L;
        final Path zip1 = createZip("a.zip", Arrays.asList(
                dirSpec("dir/"),
                fileSpec("dir/stored.txt", "stored content one", ZipEntry.STORED, "stored comment", t1),
                fileSpec("deflated.txt", repeat("deflated content ", 50), ZipEntry.DEFLATED, "deflated comment", t2)));
        final Path zip2 = createZip("b.zip", Arrays.asList(
                dirSpec("dir/"),
                fileSpec("dir/deflated-two.txt", repeat("second archive payload ", 40), ZipEntry.DEFLATED, "two comment", t2),
                fileSpec("stored-two.txt", "second stored payload", ZipEntry.STORED, null, t1)));

        final Path merged = tempDir.resolve("merged.zip");
        ZipArchiveMerger.builder().addSource(zip1).addSource(zip2).setComment("merged archive comment").build().merge(merged);

        // Order: all selected entries of zip1 then zip2; directory duplicates are merged.
        final Map<String, byte[]> contents = readContents(merged);
        assertEquals(Arrays.asList("dir/", "dir/stored.txt", "deflated.txt", "dir/deflated-two.txt", "stored-two.txt"),
                new ArrayList<>(contents.keySet()));
        assertArrayEquals(data("stored content one"), contents.get("dir/stored.txt"));
        assertArrayEquals(data(repeat("deflated content ", 50)), contents.get("deflated.txt"));
        assertArrayEquals(data(repeat("second archive payload ", 40)), contents.get("dir/deflated-two.txt"));
        assertArrayEquals(data("second stored payload"), contents.get("stored-two.txt"));

        // Raw compressed bytes and the relevant metadata must be byte-for-byte identical to the sources.
        assertRawEquals(Arrays.asList(zip1, zip2), readRaw(merged),
                Arrays.asList("dir/", "dir/stored.txt", "deflated.txt", "dir/deflated-two.txt", "stored-two.txt"));

        // The merged archive must be readable with the standard JDK ZIP implementation as well.
        assertReadableByJdk(merged, 5);
    }

    private void assertReadableByJdk(final Path zip, final int expectedCount) throws IOException {
        try (java.util.zip.ZipFile jdkZip = new java.util.zip.ZipFile(zip.toFile())) {
            final java.util.Enumeration<? extends ZipEntry> entries = jdkZip.entries();
            int count = 0;
            while (entries.hasMoreElements()) {
                final ZipEntry entry = entries.nextElement();
                if (!entry.isDirectory()) {
                    try (java.io.InputStream in = jdkZip.getInputStream(entry)) {
                        assertNotNull(in);
                        while (in.read() != -1) {
                            // Drain.
                        }
                    }
                }
                count++;
            }
            assertEquals(expectedCount, count);
        }
    }

    private void assertRawEquals(final List<Path> sources, final Map<String, RawEntry> merged, final List<String> names) throws IOException {
        final Map<String, RawEntry> sourceEntries = new LinkedHashMap<>();
        for (final Path source : sources) {
            sourceEntries.putAll(readRaw(source));
        }
        for (final String name : names) {
            final RawEntry expected = sourceEntries.get(name);
            final RawEntry actual = merged.get(name);
            assertNotNull(expected, "missing source entry " + name);
            assertNotNull(actual, "missing merged entry " + name);
            assertArrayEquals(expected.raw, actual.raw, "compressed bytes differ for " + name);
            assertEquals(expected.entry.getMethod(), actual.entry.getMethod(), "method for " + name);
            assertEquals(expected.entry.getCrc(), actual.entry.getCrc(), "crc for " + name);
            assertEquals(expected.entry.getSize(), actual.entry.getSize(), "size for " + name);
            assertEquals(expected.entry.getCompressedSize(), actual.entry.getCompressedSize(), "compressed size for " + name);
            assertEquals(expected.entry.getTime(), actual.entry.getTime(), "time for " + name);
            assertEquals(expected.entry.getComment(), actual.entry.getComment(), "comment for " + name);
        }
    }

    private static String repeat(final String s, final int count) {
        final StringBuilder sb = new StringBuilder(s.length() * count);
        for (int i = 0; i < count; i++) {
            sb.append(s);
        }
        return sb.toString();
    }

    @Test
    void testMergeEntriesWithDataDescriptors() throws Exception {
        final Path zip1 = createZipWithDataDescriptors("dd-a.zip", Arrays.asList(
                fileSpec("dd-deflated.txt", repeat("streamed payload ", 50), ZipEntry.DEFLATED, "dd comment", 1_600_000_000_000L),
                fileSpec("dd-stored.txt", "streamed stored", ZipEntry.STORED, null, 1_700_000_000_000L)));
        final Path zip2 = createZip("b.zip", Collections.singletonList(
                fileSpec("regular.txt", "regular", ZipEntry.DEFLATED, null, 1_600_000_000_000L)));

        // Confirm the fixture really uses data descriptors.
        try (ZipFile zf = ZipFile.builder().setPath(zip1).get()) {
            final ZipArchiveEntry deflated = zf.getEntry("dd-deflated.txt");
            assertTrue(deflated.getGeneralPurposeBit().usesDataDescriptor());
        }

        final Path merged = tempDir.resolve("merged-dd.zip");
        ZipArchiveMerger.builder().addSource(zip1).addSource(zip2).build().merge(merged);

        final Map<String, byte[]> contents = readContents(merged);
        assertArrayEquals(data(repeat("streamed payload ", 50)), contents.get("dd-deflated.txt"));
        assertArrayEquals(data("streamed stored"), contents.get("dd-stored.txt"));
        assertRawEquals(Arrays.asList(zip1, zip2), readRaw(merged),
                Arrays.asList("dd-deflated.txt", "dd-stored.txt", "regular.txt"));
        assertReadableByJdk(merged, 3);
    }

    @Test
    void testDuplicateNameError() throws Exception {
        final Path zip1 = createZip("a.zip",
                Collections.singletonList(fileSpec("same.txt", "first", ZipEntry.DEFLATED, null, 1_600_000_000_000L)));
        final Path zip2 = createZip("b.zip", Arrays.asList(
                fileSpec("same.txt", "second", ZipEntry.STORED, null, 1_700_000_000_000L),
                fileSpec("other.txt", "other", ZipEntry.STORED, null, 1_600_000_000_000L)));

        final Path merged = tempDir.resolve("merged.zip");
        assertThrows(ArchiveException.class,
                () -> ZipArchiveMerger.builder().addSource(zip1).addSource(zip2).build().merge(merged));
        assertFalse(Files.exists(merged), "no destination must be created on validation failure");
    }

    @Test
    void testDuplicateNameLastWins() throws Exception {
        final Path zip1 = createZip("a.zip", Arrays.asList(
                fileSpec("same.txt", "first", ZipEntry.DEFLATED, "first comment", 1_600_000_000_000L),
                fileSpec("only-a.txt", "A", ZipEntry.STORED, null, 1_600_000_000_000L)));
        final Path zip2 = createZip("b.zip", Arrays.asList(
                fileSpec("same.txt", "second", ZipEntry.STORED, "second comment", 1_700_000_000_000L),
                fileSpec("only-b.txt", "B", ZipEntry.DEFLATED, null, 1_700_000_000_000L)));
        final Path zip3 = createZip("c.zip", Collections.singletonList(
                fileSpec("same.txt", "third", ZipEntry.DEFLATED, "third comment", 1_500_000_000_000L)));

        final Path merged = tempDir.resolve("merged.zip");
        ZipArchiveMerger.builder().addSource(zip1).addSource(zip2).addSource(zip3)
                .setDuplicateNameMode(ZipArchiveMerger.DuplicateNameMode.LAST_WINS)
                .build().merge(merged);

        final Map<String, byte[]> contents = readContents(merged);
        // Output position of the first occurrence is kept, payload and metadata come from the last source.
        assertEquals(Arrays.asList("same.txt", "only-a.txt", "only-b.txt"), new ArrayList<>(contents.keySet()));
        assertArrayEquals(data("third"), contents.get("same.txt"));

        final Map<String, RawEntry> raw = readRaw(merged);
        final RawEntry same = raw.get("same.txt");
        assertEquals(ZipEntry.DEFLATED, same.entry.getMethod());
        assertEquals("third comment", same.entry.getComment());
        assertEquals(1_500_000_000_000L, same.entry.getTime());
        final Map<String, RawEntry> rawZip3 = readRaw(zip3);
        assertArrayEquals(rawZip3.get("same.txt").raw, same.raw);
    }

    @Test
    void testFileAndDirectorySameNameRejected() throws Exception {
        final Path zip1 = createZip("a.zip", Collections.singletonList(fileSpec("name", "file", ZipEntry.STORED, null, 1_600_000_000_000L)));
        final Path zip2 = createZip("b.zip", Collections.singletonList(dirSpec("name")));
        final Path merged = tempDir.resolve("merged.zip");
        assertThrows(ArchiveException.class,
                () -> ZipArchiveMerger.builder().addSource(zip1).addSource(zip2).build().merge(merged));
        assertFalse(Files.exists(merged));
    }

    @Test
    void testMergeSourceIsTarget() throws Exception {
        final Path target = createZip("self.zip", Arrays.asList(
                fileSpec("same.txt", "first", ZipEntry.DEFLATED, null, 1_600_000_000_000L),
                fileSpec("a.txt", "A", ZipEntry.STORED, null, 1_600_000_000_000L)));
        final Path other = createZip("other.zip", Arrays.asList(
                fileSpec("same.txt", "second", ZipEntry.STORED, null, 1_700_000_000_000L),
                fileSpec("b.txt", "B", ZipEntry.DEFLATED, null, 1_700_000_000_000L)));
        final byte[] before = Files.readAllBytes(target);

        ZipArchiveMerger.builder().addSource(target).addSource(other)
                .setDuplicateNameMode(ZipArchiveMerger.DuplicateNameMode.LAST_WINS)
                .build().merge(target);

        final Map<String, byte[]> contents = readContents(target);
        assertEquals(Arrays.asList("same.txt", "a.txt", "b.txt"), new ArrayList<>(contents.keySet()));
        assertArrayEquals(data("second"), contents.get("same.txt"));
        assertFalse(Arrays.equals(before, Files.readAllBytes(target)), "archive should have been replaced");
    }

    @Test
    void testMergeValidationFailureLeavesExistingTargetUnchanged() throws Exception {
        final Path target = createZip("target.zip", Arrays.asList(
                fileSpec("same.txt", "first", ZipEntry.DEFLATED, null, 1_600_000_000_000L),
                fileSpec("a.txt", "A", ZipEntry.STORED, null, 1_600_000_000_000L)));
        final Path other = createZip("other.zip",
                Collections.singletonList(fileSpec("same.txt", "second", ZipEntry.STORED, null, 1_700_000_000_000L)));
        final byte[] before = Files.readAllBytes(target);

        assertThrows(ArchiveException.class,
                () -> ZipArchiveMerger.builder().addSource(target).addSource(other).build().merge(target));

        final byte[] after = Files.readAllBytes(target);
        assertArrayEquals(before, after, "existing target must not be modified on validation failure");
        final Map<String, byte[]> contents = readContents(target);
        assertEquals(2, contents.size());
        assertArrayEquals(data("first"), contents.get("same.txt"));
    }

    @Test
    void testWriteFailureToPathLeavesExistingTargetUnchanged() throws Exception {
        final Path zip1 = createZip("a.zip", Arrays.asList(
                fileSpec("a.txt", repeat("A", 100), ZipEntry.DEFLATED, null, 1_600_000_000_000L),
                fileSpec("b.txt", repeat("B", 100), ZipEntry.STORED, null, 1_600_000_000_000L)));
        final Path target = createZip("target.zip",
                Collections.singletonList(fileSpec("old.txt", "old", ZipEntry.STORED, null, 1_600_000_000_000L)));
        final byte[] before = Files.readAllBytes(target);

        final ZipArchiveMerger merger = new ZipArchiveMerger(ZipArchiveMerger.builder().addSource(zip1)) {
            @Override
            OutputStream newOutputStream(final Path tempFile) throws IOException {
                final OutputStream delegate = super.newOutputStream(tempFile);
                return new OutputStream() {
                    private int written;

                    @Override
                    public void write(final int b) throws IOException {
                        if (written++ > 16) {
                            throw new IOException("simulated disk failure");
                        }
                        delegate.write(b);
                    }

                    @Override
                    public void write(final byte[] b, final int off, final int len) throws IOException {
                        for (int i = 0; i < len; i++) {
                            write(b[off + i]);
                        }
                    }
                };
            }
        };
        assertThrows(IOException.class, () -> merger.merge(target));

        assertArrayEquals(before, Files.readAllBytes(target), "existing target must survive a write failure");
        final Map<String, byte[]> contents = readContents(target);
        assertArrayEquals(data("old"), contents.get("old.txt"));
        // Temporary files must have been cleaned up.
        try (java.util.stream.Stream<Path> leftovers = Files.list(tempDir)
                .filter(p -> p.getFileName().toString().startsWith("zip-merge-"))) {
            assertFalse(leftovers.findAny().isPresent(), "temporary merge file must be deleted");
        }
    }

    @Test
    void testWriteFailureToStreamPropagates() throws Exception {
        final Path zip1 = createZip("a.zip", Arrays.asList(
                fileSpec("a.txt", repeat("A", 100), ZipEntry.DEFLATED, null, 1_600_000_000_000L),
                fileSpec("b.txt", repeat("B", 100), ZipEntry.STORED, null, 1_600_000_000_000L)));

        final OutputStream failing = new OutputStream() {
            private int written;

            @Override
            public void write(final int b) throws IOException {
                if (written++ > 8) {
                    throw new IOException("simulated stream failure");
                }
            }
        };
        assertThrows(IOException.class, () -> ZipArchiveMerger.builder().addSource(zip1).build().merge(failing));
    }

    @Test
    void testEncryptedEntryRejected() throws Exception {
        final Path plain = createZip("plain.zip", Collections.singletonList(
                fileSpec("plain.txt", "plain", ZipEntry.STORED, null, 1_600_000_000_000L)));
        final Path encrypted = getPath("password-encrypted.zip");

        // Encrypted archive first: the offending entry must be identified in the exception.
        final ZipArchiveMerger first = ZipArchiveMerger.builder().addSource(encrypted).addSource(plain).build();
        final UnsupportedZipFeatureException exFirst = assertThrows(UnsupportedZipFeatureException.class,
                () -> first.merge(new ByteArrayOutputStream()));
        assertEquals(UnsupportedZipFeatureException.Feature.ENCRYPTION, exFirst.getFeature());
        assertNotNull(exFirst.getEntry());

        // Encrypted archive second, merging to a path: no output file must be created.
        final Path merged = tempDir.resolve("merged.zip");
        final ZipArchiveMerger merger = ZipArchiveMerger.builder().addSource(plain).addSource(encrypted).build();
        final UnsupportedZipFeatureException ex = assertThrows(UnsupportedZipFeatureException.class, () -> merger.merge(merged));
        assertEquals(UnsupportedZipFeatureException.Feature.ENCRYPTION, ex.getFeature());
        assertFalse(Files.exists(merged), "nothing must be written when an encrypted entry is rejected");
    }

    @Test
    void testMergeToOutputStream() throws Exception {
        final Path zip1 = createZip("a.zip", Collections.singletonList(
                fileSpec("a.txt", "alpha", ZipEntry.STORED, null, 1_600_000_000_000L)));
        final Path zip2 = createZip("b.zip", Collections.singletonList(
                fileSpec("b.txt", repeat("bravo ", 30), ZipEntry.DEFLATED, null, 1_700_000_000_000L)));

        final ByteArrayOutputStream bos = new ByteArrayOutputStream();
        ZipArchiveMerger.builder().addSource(zip1).addSource(zip2).build().merge(bos);

        final Path merged = tempDir.resolve("from-stream.zip");
        Files.write(merged, bos.toByteArray());
        final Map<String, byte[]> contents = readContents(merged);
        assertArrayEquals(data("alpha"), contents.get("a.txt"));
        assertArrayEquals(data(repeat("bravo ", 30)), contents.get("b.txt"));
        assertRawEquals(Arrays.asList(zip1, zip2), readRaw(merged), Arrays.asList("a.txt", "b.txt"));
    }

    @Test
    void testZip64ExtraEntriesAreMerged() throws Exception {
        // Fixture created with ZIP64 extra fields on every entry.
        final Path zip1 = createZip("z64-a.zip", Arrays.asList(
                dirSpec("dir/"),
                fileSpec("dir/s.txt", "stored", ZipEntry.STORED, "c1", 1_600_000_000_000L),
                fileSpec("d.txt", repeat("deflated ", 60), ZipEntry.DEFLATED, "c2", 1_700_000_000_000L)),
                Zip64Mode.Always);
        final Path zip2 = createZip("z64-b.zip", Collections.singletonList(
                fileSpec("e.txt", "other", ZipEntry.STORED, null, 1_600_000_000_000L)),
                Zip64Mode.Always);

        final Path mergedAlways = tempDir.resolve("merged-always.zip");
        ZipArchiveMerger.builder().addSource(zip1).addSource(zip2).setZip64Mode(Zip64Mode.Always).build()
                .merge(mergedAlways);
        assertRawEquals(Arrays.asList(zip1, zip2), readRaw(mergedAlways),
                Arrays.asList("dir/", "dir/s.txt", "d.txt", "e.txt"));
        for (final RawEntry rawEntry : readRaw(mergedAlways).values()) {
            assertNotNull(rawEntry.entry.getExtraField(Zip64ExtendedInformationExtraField.HEADER_ID),
                    "ZIP64 extra must be retained in Always mode");
        }

        final Path mergedAsNeeded = tempDir.resolve("merged-asneeded.zip");
        ZipArchiveMerger.builder().addSource(zip1).addSource(zip2).setZip64Mode(Zip64Mode.AsNeeded).build()
                .merge(mergedAsNeeded);
        final Map<String, byte[]> contents = readContents(mergedAsNeeded);
        assertArrayEquals(data("stored"), contents.get("dir/s.txt"));
        assertArrayEquals(data("other"), contents.get("e.txt"));
        assertRawEquals(Arrays.asList(zip1, zip2), readRaw(mergedAsNeeded),
                Arrays.asList("dir/", "dir/s.txt", "d.txt", "e.txt"));
    }

    @Test
    void testZip64EntryCountBoundary() throws Exception {
        final int count = 65535 + 1;
        final List<Spec> specs = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            specs.add(fileSpec(String.format("entry-%05d.txt", i), "v" + i,
                    i % 2 == 0 ? ZipEntry.STORED : ZipEntry.DEFLATED, null, 1_600_000_000_000L));
        }
        final Path zip1 = createZip("many-1.zip", specs, Zip64Mode.Always);
        final Path zip2 = createZip("many-2.zip", Collections.singletonList(
                fileSpec("final.txt", "done", ZipEntry.DEFLATED, null, 1_700_000_000_000L)), Zip64Mode.Always);

        final Path merged = tempDir.resolve("many-merged.zip");
        ZipArchiveMerger.builder().addSource(zip1).addSource(zip2).setZip64Mode(Zip64Mode.AsNeeded).build()
                .merge(merged);

        final Map<String, byte[]> contents = readContents(merged);
        assertEquals(count + 1, contents.size());
        // Spot check entries at and beyond the 16-bit boundary.
        for (final int index : new int[] {0, 65533, 65534, 65535 }) {
            final String name = String.format("entry-%05d.txt", index);
            assertArrayEquals(data("v" + index), contents.get(name), name);
        }
        assertArrayEquals(data("done"), contents.get("final.txt"));

        // A proper ZIP64 archive must also be readable by the standard JDK implementation.
        try (java.util.zip.ZipFile jdkZip = new java.util.zip.ZipFile(merged.toFile())) {
            assertEquals(count + 1, jdkZip.size());
            final ZipEntry entry = jdkZip.getEntry("entry-65535.txt");
            assertNotNull(entry);
            try (java.io.InputStream in = jdkZip.getInputStream(entry)) {
                assertArrayEquals(data("v65535"), IOUtils.toByteArray(in));
            }
        }

        // Merge must refuse to downgrade such an archive to classic ZIP.
        final Path mergedNever = tempDir.resolve("many-never.zip");
        assertThrows(IOException.class, () -> ZipArchiveMerger.builder().addSource(zip1).addSource(zip2)
                .setZip64Mode(Zip64Mode.Never).build().merge(mergedNever));
    }
}
