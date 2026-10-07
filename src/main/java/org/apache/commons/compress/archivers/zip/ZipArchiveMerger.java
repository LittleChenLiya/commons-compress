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

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
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

import org.apache.commons.compress.archivers.ArchiveException;

/**
 * Merges several ZIP archives into a single ZIP archive.
 *
 * <p>
 * Entries are transferred in input order: all selected entries of the first source archive are written first, followed by the selected entries of the
 * second source archive, and so on. The already compressed data of every selected entry is copied byte-for-byte without decompressing and recompressing
 * it, so the compression method, CRC-32, sizes, timestamps, comments and extra fields of the source entries are all preserved.
 * </p>
 *
 * <p>
 * Duplicate entry names are handled according to the configured {@link DuplicateNameMode}: duplicate directory entries are always merged (only the first
 * occurrence is kept), duplicate regular file entries either cause an exception or are replaced by the entry from the later source archive (last wins).
 * </p>
 *
 * <p>
 * Encrypted entries cannot be merged and cause an {@link UnsupportedZipFeatureException} to be thrown before anything is written to the destination.
 * </p>
 *
 * <p>
 * When merging to a {@link File} or {@link Path}, the complete list of resulting entries is collected and validated before the destination archive is
 * touched, and the result is written to a temporary file in the destination directory which atomically replaces the destination only on success. This
 * means a merge can target one of its own source files safely, and an existing destination archive remains unchanged (and therefore uncorrupted) if the
 * merge fails.
 * </p>
 *
 * @since 1.29.0
 */
public class ZipArchiveMerger {

    /**
     * Strategies for handling multiple selected regular file entries with the same name.
     *
     * @since 1.29.0
     */
    public enum DuplicateNameMode {

        /**
         * Throw an {@link ArchiveException} when two selected regular file entries have the same name.
         */
        ERROR,

        /**
         * Keep the regular file entry from the later source archive when two selected entries have the same name.
         */
        LAST_WINS
    }

    /**
     * Builds {@link ZipArchiveMerger} instances.
     *
     * @since 1.29.0
     */
    public static final class Builder {

        private final List<Path> sources = new ArrayList<>();

        private DuplicateNameMode duplicateNameMode = DuplicateNameMode.ERROR;

        private Zip64Mode zip64Mode = Zip64Mode.AsNeeded;

        private Charset charset = StandardCharsets.UTF_8;

        private String comment;

        private Builder() {
        }

        /**
         * Adds a source ZIP archive to merge.
         *
         * <p>
         * Sources are merged in the order they are added.
         * </p>
         *
         * @param source the source archive to add.
         * @return {@code this} instance.
         */
        public Builder addSource(final File source) {
            Objects.requireNonNull(source, "source");
            return addSource(source.toPath());
        }

        /**
         * Adds a source ZIP archive to merge.
         *
         * <p>
         * Sources are merged in the order they are added.
         * </p>
         *
         * @param source the source archive to add.
         * @return {@code this} instance.
         */
        public Builder addSource(final Path source) {
            sources.add(Objects.requireNonNull(source, "source").toAbsolutePath().normalize());
            return this;
        }

        /**
         * Builds the configured {@link ZipArchiveMerger}.
         *
         * @return a new {@link ZipArchiveMerger}.
         */
        public ZipArchiveMerger build() {
            if (sources.isEmpty()) {
                throw new IllegalStateException("At least one source ZIP archive is required");
            }
            return new ZipArchiveMerger(this);
        }

        /**
         * Sets the charset to use when reading the source archives and writing the destination archive.
         *
         * @param charset the charset, {@code null} resets to UTF-8.
         * @return {@code this} instance.
         */
        public Builder setCharset(final Charset charset) {
            this.charset = charset == null ? StandardCharsets.UTF_8 : charset;
            return this;
        }

        /**
         * Sets the comment of the resulting archive.
         *
         * @param comment the archive comment, may be {@code null}.
         * @return {@code this} instance.
         */
        public Builder setComment(final String comment) {
            this.comment = comment;
            return this;
        }

        /**
         * Sets how duplicate regular file entry names are handled, the default is {@link DuplicateNameMode#ERROR}.
         *
         * @param duplicateNameMode the duplicate name handling strategy, must not be {@code null}.
         * @return {@code this} instance.
         */
        public Builder setDuplicateNameMode(final DuplicateNameMode duplicateNameMode) {
            this.duplicateNameMode = Objects.requireNonNull(duplicateNameMode, "duplicateNameMode");
            return this;
        }

        /**
         * Sets the ZIP64 mode of the resulting archive, the default is {@link Zip64Mode#AsNeeded}.
         *
         * @param zip64Mode the ZIP64 mode, must not be {@code null}.
         * @return {@code this} instance.
         */
        public Builder setZip64Mode(final Zip64Mode zip64Mode) {
            this.zip64Mode = Objects.requireNonNull(zip64Mode, "zip64Mode");
            return this;
        }
    }

    /**
     * A selected entry together with the ZIP file it has to be copied from.
     */
    private static final class SelectedEntry {

        private final ZipFile source;

        private final ZipArchiveEntry entry;

        private SelectedEntry(final ZipFile source, final ZipArchiveEntry entry) {
            this.source = source;
            this.entry = entry;
        }
    }

    /**
     * Creates a new {@link Builder}.
     *
     * @return a new {@link Builder}.
     */
    public static Builder builder() {
        return new Builder();
    }

    private final List<Path> sources;

    private final DuplicateNameMode duplicateNameMode;

    private final Zip64Mode zip64Mode;

    private final Charset charset;

    private final String comment;

    ZipArchiveMerger(final Builder builder) {
        this.sources = new ArrayList<>(builder.sources);
        this.duplicateNameMode = builder.duplicateNameMode;
        this.zip64Mode = builder.zip64Mode;
        this.charset = builder.charset;
        this.comment = builder.comment;
    }

    private static void closeQuietly(final List<? extends AutoCloseable> closeables) {
        for (final AutoCloseable closeable : closeables) {
            try {
                closeable.close();
            } catch (final Exception ex) {
                // Best effort.
            }
        }
    }

    /**
     * Opens the configured source archives and collects the resulting, deduplicated list of entries in output order.
     *
     * <p>
     * Nothing is written to the destination during this phase, so all validation failures happen before the destination archive is touched.
     * </p>
     *
     * @param zipFiles receives the opened source ZIP files; the caller is responsible for closing them.
     * @return the selected entries in output order.
     * @throws IOException if a source archive cannot be read.
     * @throws ArchiveException if duplicate entry names or encrypted entries are encountered according to the configuration.
     */
    private List<SelectedEntry> collectEntries(final List<ZipFile> zipFiles) throws IOException, ArchiveException {
        final Map<String, SelectedEntry> selectedByName = new LinkedHashMap<>();
        final List<SelectedEntry> ordered = new ArrayList<>();
        for (final Path source : sources) {
            final ZipFile zipFile = ZipFile.builder().setPath(source).setCharset(charset).get();
            zipFiles.add(zipFile);
            final Enumeration<ZipArchiveEntry> entries = zipFile.getEntriesInPhysicalOrder();
            while (entries.hasMoreElements()) {
                final ZipArchiveEntry entry = entries.nextElement();
                final GeneralPurposeBit gpb = entry.getGeneralPurposeBit();
                if (gpb != null && gpb.usesEncryption()) {
                    throw new UnsupportedZipFeatureException(UnsupportedZipFeatureException.Feature.ENCRYPTION, entry);
                }
                final String name = entry.getName();
                final SelectedEntry previous = selectedByName.get(name);
                if (previous == null) {
                    final SelectedEntry selected = new SelectedEntry(zipFile, entry);
                    selectedByName.put(name, selected);
                    ordered.add(selected);
                } else if (entry.isDirectory()) {
                    if (!previous.entry.isDirectory()) {
                        throw new ArchiveException("Entry %s already exists as a file and cannot be merged with a directory entry", name);
                    }
                    // Directories are merged: keep the first occurrence.
                } else if (previous.entry.isDirectory()) {
                    throw new ArchiveException("Entry %s already exists as a directory and cannot be merged with a file entry", name);
                } else if (duplicateNameMode == DuplicateNameMode.ERROR) {
                    throw new ArchiveException("Duplicate ZIP entry name: %s", name);
                } else {
                    // Last wins: keep the output position of the first occurrence, replace payload and metadata.
                    final SelectedEntry selected = new SelectedEntry(zipFile, entry);
                    selectedByName.put(name, selected);
                    ordered.set(ordered.indexOf(previous), selected);
                }
            }
        }
        return Collections.unmodifiableList(ordered);
    }

    /**
     * Merges the configured source archives into the given output stream.
     *
     * <p>
     * The resulting entries are (in order) collected, validated and then written, so a validation failure (duplicate names in
     * {@link DuplicateNameMode#ERROR} mode, encrypted entries, ...) never produces a partial archive. The output stream is not closed by this method, only
     * the ZIP archive itself is finished.
     * </p>
     *
     * @param target the output stream to write the merged archive to.
     * @throws IOException if reading a source or writing the destination fails.
     * @throws ArchiveException if the resulting entry list is invalid according to the configuration.
     */
    public void merge(final OutputStream target) throws IOException, ArchiveException {
        Objects.requireNonNull(target, "target");
        final List<ZipFile> zipFiles = new ArrayList<>(sources.size());
        try {
            final List<SelectedEntry> entries = collectEntries(zipFiles);
            writeMerged(entries, target);
        } finally {
            closeQuietly(zipFiles);
        }
    }

    /**
     * Merges the configured source archives into the given file.
     *
     * <p>
     * The destination file is replaced atomically on success and is left untouched if the merge fails, so it remains a valid (possibly pre-existing) ZIP
     * archive. The destination file may be one of the source archives.
     * </p>
     *
     * @param target the destination file.
     * @throws IOException if reading a source or writing the destination fails.
     * @throws ArchiveException if the resulting entry list is invalid according to the configuration.
     */
    public void merge(final Path target) throws IOException, ArchiveException {
        Objects.requireNonNull(target, "target");
        final Path normalizedTarget = target.toAbsolutePath().normalize();
        final List<ZipFile> zipFiles = new ArrayList<>(sources.size());
        Path tempFile = null;
        try {
            final List<SelectedEntry> entries = collectEntries(zipFiles);
            final Path directory = normalizedTarget.getParent();
            tempFile = Files.createTempFile(directory, "zip-merge-", ".zip.tmp");
            try (OutputStream out = new BufferedOutputStream(newOutputStream(tempFile))) {
                writeMerged(entries, out);
            }
            Files.move(tempFile, normalizedTarget, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            closeQuietly(zipFiles);
            if (tempFile != null) {
                Files.deleteIfExists(tempFile);
            }
        }
    }

    /**
     * Merges the configured source archives into the given file.
     *
     * @param target the destination file.
     * @throws IOException if reading a source or writing the destination fails.
     * @throws ArchiveException if the resulting entry list is invalid according to the configuration.
     */
    public void merge(final File target) throws IOException, ArchiveException {
        Objects.requireNonNull(target, "target");
        merge(target.toPath());
    }

    /**
     * Opens an output stream for the temporary merge result written by {@link #merge(Path)}.
     *
     * <p>
     * This method is package private and intended to be overridden in tests to simulate write failures.
     * </p>
     *
     * @param tempFile the temporary file to write to.
     * @return a new output stream.
     * @throws IOException if the file cannot be opened.
     */
    OutputStream newOutputStream(final Path tempFile) throws IOException {
        return Files.newOutputStream(tempFile);
    }

    private void writeMerged(final List<SelectedEntry> entries, final OutputStream target) throws IOException {
        try (ZipArchiveOutputStream out = new ZipArchiveOutputStream(target)) {
            out.setEncoding(charset.name());
            out.setUseZip64(zip64Mode);
            if (comment != null) {
                out.setComment(comment);
            }
            for (final SelectedEntry selected : entries) {
                try (InputStream raw = selected.source.getRawInputStream(selected.entry)) {
                    out.addRawArchiveEntry(selected.entry, raw);
                }
            }
            out.finish();
        }
    }
}
