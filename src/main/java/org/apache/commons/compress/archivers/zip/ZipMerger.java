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

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.zip.ZipException;

/**
 * Merges the entries of several ZIP archives into a single ZIP archive, preserving the input order of sources and entries.
 *
 * <p>
 * Whenever possible the compressed data of an entry is copied verbatim from its source archive (see
 * {@link ZipArchiveOutputStream#addRawArchiveEntry}), so entries are not decompressed and recompressed. The compression method, CRC, sizes,
 * last modification time, comment and extra fields of each selected entry are preserved.
 * </p>
 *
 * <p>
 * Before anything is written, the final entry list is collected and validated (see {@link #plan()}):
 * </p>
 * <ul>
 * <li>Entries whose data cannot be copied raw (currently only encrypted entries) are rejected.</li>
 * <li>Duplicate names of regular file entries are handled according to the configured {@link DuplicateStrategy}.</li>
 * <li>Duplicate directory entries are merged silently; only the first occurrence of a directory is kept.</li>
 * </ul>
 *
 * <p>
 * {@link #merge(Path)} writes to a temporary file next to the target first and only moves it over the target once the merge has completed
 * successfully, so a pre-existing target file is never corrupted by a failed merge. This also allows the target to be one of the source
 * archives.
 * </p>
 *
 * <p>
 * Typical usage:
 * </p>
 *
 * <pre>
 * try (ZipMerger merger = ZipMerger.builder().duplicateStrategy(ZipMerger.DuplicateStrategy.LAST_WINS).addSource(first).addSource(second)
 *     .build()) {
 *     merger.merge(target);
 * }
 * </pre>
 *
 * @since 1.29
 */
public final class ZipMerger implements Closeable {

    /**
     * Builds {@link ZipMerger} instances.
     */
    public static final class Builder {

        private final List<Source> sources = new ArrayList<>();
        private DuplicateStrategy duplicateStrategy = DuplicateStrategy.ERROR;

        private Builder() {
        }

        /**
         * Adds a source archive. Sources are merged in the order they are added.
         *
         * @param source the path of a ZIP archive to merge.
         * @return this builder.
         */
        public Builder addSource(final Path source) {
            sources.add(new Source(Objects.requireNonNull(source, "source")));
            return this;
        }

        /**
         * Adds a source archive. The supplied {@link ZipFile} is managed by the caller and is not closed by the merger.
         *
         * @param source an open {@link ZipFile} to merge.
         * @return this builder.
         */
        public Builder addSource(final ZipFile source) {
            sources.add(new Source(Objects.requireNonNull(source, "source")));
            return this;
        }

        /**
         * Builds the merger.
         *
         * @return a new {@link ZipMerger}.
         */
        public ZipMerger build() {
            return new ZipMerger(new ArrayList<>(sources), duplicateStrategy);
        }

        /**
         * Sets how duplicate names of regular file entries are handled. Defaults to {@link DuplicateStrategy#ERROR}.
         *
         * @param duplicateStrategy the strategy to use.
         * @return this builder.
         */
        public Builder duplicateStrategy(final DuplicateStrategy duplicateStrategy) {
            this.duplicateStrategy = Objects.requireNonNull(duplicateStrategy, "duplicateStrategy");
            return this;
        }
    }

    /**
     * How to handle several regular file entries that share the same name.
     */
    public enum DuplicateStrategy {

        /**
         * Reject the merge by throwing a {@link ZipException} while planning.
         */
        ERROR,

        /**
         * Keep the entry from the latest source that contains the name; earlier entries with the same name are dropped.
         */
        LAST_WINS
    }

    /**
     * A single entry of the planned merge result: the entry metadata together with the archive its data is read from.
     */
    public static final class PlannedEntry {

        private final ZipFile source;
        private final ZipArchiveEntry entry;

        private PlannedEntry(final ZipFile source, final ZipArchiveEntry entry) {
            this.source = source;
            this.entry = entry;
        }

        /**
         * Gets the entry as it will be written to the merged archive.
         *
         * @return the entry.
         */
        public ZipArchiveEntry getEntry() {
            return entry;
        }

        /**
         * Gets the archive the entry's data is copied from.
         *
         * @return the source archive.
         */
        public ZipFile getSource() {
            return source;
        }
    }

    /**
     * A merge source; either a path opened (and closed) by the merger itself or a caller-managed {@link ZipFile}.
     */
    private static final class Source {

        private final Path path;
        private ZipFile zipFile;
        private final boolean owned;

        Source(final Path path) {
            this.path = path;
            this.owned = true;
        }

        Source(final ZipFile zipFile) {
            this.path = null;
            this.zipFile = zipFile;
            this.owned = false;
        }

        void close() throws IOException {
            if (owned && zipFile != null) {
                zipFile.close();
                zipFile = null;
            }
        }

        ZipFile zipFile() throws IOException {
            if (zipFile == null) {
                zipFile = ZipFile.builder().setPath(path).get();
            }
            return zipFile;
        }
    }

    /**
     * Creates a new builder.
     *
     * @return a new builder.
     */
    public static Builder builder() {
        return new Builder();
    }

    private final List<Source> sources;
    private final DuplicateStrategy duplicateStrategy;
    private List<PlannedEntry> plan;

    private ZipMerger(final List<Source> sources, final DuplicateStrategy duplicateStrategy) {
        this.sources = sources;
        this.duplicateStrategy = duplicateStrategy;
    }

    @Override
    public void close() throws IOException {
        IOException thrown = null;
        for (final Source source : sources) {
            try {
                source.close();
            } catch (final IOException e) {
                if (thrown == null) {
                    thrown = e;
                } else {
                    thrown.addSuppressed(e);
                }
            }
        }
        if (thrown != null) {
            throw thrown;
        }
    }

    /**
     * Merges all sources into the given target path.
     *
     * <p>
     * The merged archive is first written to a temporary file in the same directory and moved over {@code target} only after it has been
     * completed successfully; if anything fails, a pre-existing target file is left untouched. The target may be identical to one of the
     * source paths.
     * </p>
     *
     * @param target the path of the ZIP archive to create or replace.
     * @throws IOException if planning, reading a source or writing the target fails.
     */
    public void merge(final Path target) throws IOException {
        final List<PlannedEntry> entries = plan();
        final Path absoluteTarget = target.toAbsolutePath();
        final Path temp = Files.createTempFile(absoluteTarget.getParent(), absoluteTarget.getFileName().toString(), ".tmp");
        boolean completed = false;
        try {
            try (ZipArchiveOutputStream zos = new ZipArchiveOutputStream(temp)) {
                writeEntries(zos, entries);
            }
            // Sources are no longer needed; close the ones we own so the target may replace a source even on
            // file systems that do not allow moving over an open file.
            close();
            move(temp, absoluteTarget);
            completed = true;
        } finally {
            if (!completed) {
                Files.deleteIfExists(temp);
            }
        }
    }

    /**
     * Merges all sources into the given stream. Unlike {@link #merge(Path)} this writes directly to the given stream, so a failure may leave
     * a partially written archive behind.
     *
     * @param target the stream to write the merged archive to.
     * @throws IOException if planning, reading a source or writing fails.
     */
    public void merge(final ZipArchiveOutputStream target) throws IOException {
        writeEntries(target, plan());
    }

    private void move(final Path temp, final Path target) throws IOException {
        try {
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (final AtomicMoveNotSupportedException e) {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * Collects and validates the final entry list of the merged archive without writing anything.
     *
     * <p>
     * Entries appear in input order: all entries of the first source (in archive order), then all entries of the second source, and so on.
     * The result is cached; repeated calls return the same list.
     * </p>
     *
     * @return the unmodifiable list of entries the merged archive will contain.
     * @throws ZipException if a duplicate regular file name is found and the strategy is {@link DuplicateStrategy#ERROR}.
     * @throws UnsupportedZipFeatureException if an entry uses a feature (like encryption) that cannot be copied raw.
     * @throws IOException if a source archive cannot be read.
     */
    public List<PlannedEntry> plan() throws IOException {
        if (plan == null) {
            final Map<String, PlannedEntry> byName = new LinkedHashMap<>();
            for (final Source source : sources) {
                final ZipFile zipFile = source.zipFile();
                final Enumeration<ZipArchiveEntry> entries = zipFile.getEntriesInPhysicalOrder();
                while (entries.hasMoreElements()) {
                    final ZipArchiveEntry entry = entries.nextElement();
                    validate(entry);
                    final PlannedEntry previous = byName.get(entry.getName());
                    if (previous != null && entry.isDirectory() && previous.getEntry().isDirectory()) {
                        // directory entries are merged: keep the first occurrence
                        continue;
                    }
                    if (previous != null && duplicateStrategy == DuplicateStrategy.ERROR) {
                        throw new ZipException("Duplicate entry name '" + entry.getName() + "' in source archives");
                    }
                    byName.put(entry.getName(), new PlannedEntry(zipFile, entry));
                }
            }
            plan = Collections.unmodifiableList(new ArrayList<>(byName.values()));
        }
        return plan;
    }

    private void validate(final ZipArchiveEntry entry) throws ZipException {
        if (entry.getGeneralPurposeBit().usesEncryption()) {
            throw new UnsupportedZipFeatureException(UnsupportedZipFeatureException.Feature.ENCRYPTION, entry);
        }
    }

    private void writeEntries(final ZipArchiveOutputStream target, final List<PlannedEntry> entries) throws IOException {
        for (final PlannedEntry planned : entries) {
            final ZipArchiveEntry entry = planned.getEntry();
            try (InputStream raw = planned.getSource().getRawInputStream(entry)) {
                target.addRawArchiveEntry(entry, raw);
            }
        }
    }
}
