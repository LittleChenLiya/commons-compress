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

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import org.apache.commons.compress.archivers.ArchiveException;
import org.apache.commons.compress.utils.IOUtils;

/**
 * Repacks a TAR archive into a canonical, reproducible form.
 *
 * <p>
 * The repacker reads an existing TAR archive and writes a new archive in which:
 * </p>
 * <ul>
 * <li>Entries are sorted by their normalized path names.</li>
 * <li>All entries share a single configurable modification time, user id, group id, user name and group name.</li>
 * <li>Regular file contents are copied unchanged.</li>
 * <li>Long names and long link targets are stored using deterministic POSIX PAX extended headers.</li>
 * <li>Unstable extended header fields of the source archive (for example PAX {@code atime} or {@code ctime} values) are
 * dropped.</li>
 * </ul>
 *
 * <p>
 * Two source archives that contain the same entries with the same file contents produce byte-for-byte identical output,
 * even if their entry order, timestamps or ownership metadata differ.
 * </p>
 *
 * <p>
 * An archive is rejected with an {@link ArchiveException} before anything is published when it contains:
 * </p>
 * <ul>
 * <li>two entries whose normalized path names are equal,</li>
 * <li>an absolute entry path,</li>
 * <li>an entry path that traverses above the archive root using {@code ..}, or</li>
 * <li>a link whose target points outside of the archive.</li>
 * <li>an entry whose header checksum does not verify.</li>
 * </ul>
 *
 * <p>
 * When repacking from one {@link Path} to another, the new archive is first written to a temporary file in the target
 * directory and then moved over the target, so a failure leaves a pre-existing target file unchanged.
 * </p>
 *
 * @since 1.29.0
 */
public final class TarRepacker {

    /**
     * Builds {@link TarRepacker} instances.
     */
    public static final class Builder {

        private Instant modTime = DEFAULT_MOD_TIME;
        private long userId;
        private long groupId;
        private String userName = "";
        private String groupName = "";

        private Builder() {
            // Use builder().
        }

        /**
         * Builds a new {@link TarRepacker}.
         *
         * @return a new {@link TarRepacker}.
         */
        public TarRepacker build() {
            return new TarRepacker(this);
        }

        /**
         * Sets the group id written to every output entry, defaults to 0.
         *
         * @param groupId the group id.
         * @return {@code this} instance.
         */
        public Builder setGroupId(final long groupId) {
            this.groupId = groupId;
            return this;
        }

        /**
         * Sets the group name written to every output entry, defaults to the empty string.
         *
         * @param groupName the group name.
         * @return {@code this} instance.
         */
        public Builder setGroupName(final String groupName) {
            this.groupName = Objects.requireNonNull(groupName, "groupName");
            return this;
        }

        /**
         * Sets the modification time written to every output entry, defaults to {@link #DEFAULT_MOD_TIME}.
         *
         * @param modTime the modification time.
         * @return {@code this} instance.
         */
        public Builder setModTime(final Instant modTime) {
            this.modTime = Objects.requireNonNull(modTime, "modTime");
            return this;
        }

        /**
         * Sets the user id written to every output entry, defaults to 0.
         *
         * @param userId the user id.
         * @return {@code this} instance.
         */
        public Builder setUserId(final long userId) {
            this.userId = userId;
            return this;
        }

        /**
         * Sets the user name written to every output entry, defaults to the empty string.
         *
         * @param userName the user name.
         * @return {@code this} instance.
         */
        public Builder setUserName(final String userName) {
            this.userName = Objects.requireNonNull(userName, "userName");
            return this;
        }
    }

    /**
     * A source entry buffered for output.
     */
    private static final class PendingEntry {

        private final TarArchiveEntry entry;
        private final byte[] content;

        private PendingEntry(final TarArchiveEntry entry, final byte[] content) {
            this.entry = entry;
            this.content = content;
        }
    }

    /**
     * The default modification time written to every output entry: the epoch.
     */
    public static final Instant DEFAULT_MOD_TIME = Instant.ofEpochSecond(0);

    /**
     * Constructs a new {@link Builder}.
     *
     * @return a new {@link Builder}.
     */
    public static Builder builder() {
        return new Builder();
    }

    private static boolean isAbsolutePath(final String path) {
        return path.startsWith("/") || path.startsWith("\\") || path.length() >= 2 && Character.isLetter(path.charAt(0)) && path.charAt(1) == ':';
    }

    /**
     * Normalizes an entry name to a canonical relative path, rejecting absolute paths and parent traversal.
     *
     * @param name the raw entry name.
     * @param directory whether the entry is a directory (the result keeps a trailing slash).
     * @return the normalized name.
     * @throws ArchiveException if the name is absolute, escapes the archive root or is empty.
     */
    private static String normalizeName(final String name, final boolean directory) throws ArchiveException {
        final List<String> segments = normalizeSegments(name, "entry name");
        if (segments.isEmpty()) {
            throw new ArchiveException("Entry name normalizes to the empty path: '%s'", name);
        }
        final String normalized = String.join("/", segments);
        return directory ? normalized + "/" : normalized;
    }

    private static List<String> normalizeSegments(final String path, final String what) throws ArchiveException {
        if (isAbsolutePath(path)) {
            throw new ArchiveException("Absolute %s is not allowed: '%s'", what, path);
        }
        final List<String> segments = new ArrayList<>();
        for (final String segment : path.split("[/\\\\]")) {
            if (segment.isEmpty() || ".".equals(segment)) {
                continue;
            }
            if ("..".equals(segment)) {
                if (segments.isEmpty()) {
                    throw new ArchiveException("%s escapes the archive root: '%s'", what, path);
                }
                segments.remove(segments.size() - 1);
            } else {
                segments.add(segment);
            }
        }
        return segments;
    }

    /**
     * Validates that a symbolic link target stays inside the archive. The target is interpreted relative to the
     * directory containing the link entry.
     */
    private static void validateSymbolicLinkTarget(final String entryName, final String target) throws ArchiveException {
        if (target.isEmpty()) {
            throw new ArchiveException("Empty link target for entry '%s'", entryName);
        }
        if (isAbsolutePath(target)) {
            throw new ArchiveException("Link target of entry '%s' points outside of the archive: '%s'", entryName, target);
        }
        final List<String> segments = normalizeSegments(entryName, "entry name");
        if (!segments.isEmpty()) {
            segments.remove(segments.size() - 1);
        }
        for (final String segment : target.split("[/\\\\]")) {
            if (segment.isEmpty() || ".".equals(segment)) {
                continue;
            }
            if ("..".equals(segment)) {
                if (segments.isEmpty()) {
                    throw new ArchiveException("Link target of entry '%s' points outside of the archive: '%s'", entryName, target);
                }
                segments.remove(segments.size() - 1);
            } else {
                segments.add(segment);
            }
        }
    }

    private final Instant modTime;
    private final long userId;
    private final long groupId;
    private final String userName;
    private final String groupName;

    private TarRepacker(final Builder builder) {
        this.modTime = builder.modTime;
        this.userId = builder.userId;
        this.groupId = builder.groupId;
        this.userName = builder.userName;
        this.groupName = builder.groupName;
    }

    private void applyCanonicalMetadata(final TarArchiveEntry entry) {
        entry.setModTime(FileTime.from(modTime));
        entry.setUserId(userId);
        entry.setGroupId(groupId);
        entry.setUserName(userName);
        entry.setGroupName(groupName);
    }

    private TarArchiveEntry newOutputEntry(final TarArchiveEntry source, final String name, final byte linkFlag) {
        final TarArchiveEntry output = new TarArchiveEntry(name, linkFlag);
        output.setMode(source.getMode());
        applyCanonicalMetadata(output);
        return output;
    }

    private List<PendingEntry> readEntries(final InputStream inputStream) throws IOException {
        final List<PendingEntry> pending = new ArrayList<>();
        final Set<String> names = new HashSet<>();
        try (TarArchiveInputStream tarInput = new TarArchiveInputStream(inputStream)) {
            TarArchiveEntry source;
            while ((source = tarInput.getNextEntry()) != null) {
                if (!source.isCheckSumOK()) {
                    throw new ArchiveException("Invalid header checksum for tar entry: '%s'", source.getName());
                }
                pending.add(readEntry(tarInput, source, names));
            }
        }
        validateHardLinks(pending, names);
        pending.sort(Comparator.comparing(pendingEntry -> pendingEntry.entry.getName()));
        return pending;
    }

    private PendingEntry readEntry(final TarArchiveInputStream tarInput, final TarArchiveEntry source, final Set<String> names) throws IOException {
        if (source.isDirectory()) {
            final String name = normalizeName(source.getName(), true);
            checkDuplicate(names, name);
            return new PendingEntry(newOutputEntry(source, name, TarConstants.LF_DIR), null);
        }
        if (source.isSymbolicLink()) {
            final String name = normalizeName(source.getName(), false);
            checkDuplicate(names, name);
            validateSymbolicLinkTarget(name, source.getLinkName());
            final TarArchiveEntry output = newOutputEntry(source, name, TarConstants.LF_SYMLINK);
            output.setLinkName(source.getLinkName());
            return new PendingEntry(output, null);
        }
        if (source.isLink()) {
            final String name = normalizeName(source.getName(), false);
            checkDuplicate(names, name);
            final String target = normalizeName(source.getLinkName(), false);
            final TarArchiveEntry output = newOutputEntry(source, name, TarConstants.LF_LINK);
            output.setLinkName(target);
            return new PendingEntry(output, null);
        }
        final byte linkFlag = source.getLinkFlag();
        if (linkFlag == TarConstants.LF_CHR || linkFlag == TarConstants.LF_BLK || linkFlag == TarConstants.LF_FIFO) {
            final String name = normalizeName(source.getName(), false);
            checkDuplicate(names, name);
            final TarArchiveEntry output = newOutputEntry(source, name, linkFlag);
            if (linkFlag != TarConstants.LF_FIFO) {
                output.setDevMajor(source.getDevMajor());
                output.setDevMinor(source.getDevMinor());
            }
            return new PendingEntry(output, null);
        }
        // Regular files and any other file-like entries (for example contiguous or sparse files).
        final String name = normalizeName(source.getName(), false);
        checkDuplicate(names, name);
        final byte[] content = IOUtils.toByteArray(tarInput);
        final TarArchiveEntry output = newOutputEntry(source, name, TarConstants.LF_NORMAL);
        output.setSize(content.length);
        return new PendingEntry(output, content);
    }

    private void checkDuplicate(final Set<String> names, final String name) throws ArchiveException {
        if (!names.add(name)) {
            throw new ArchiveException("Duplicate entry name after normalization: '%s'", name);
        }
    }

    private void validateHardLinks(final List<PendingEntry> pending, final Set<String> names) throws ArchiveException {
        for (final PendingEntry pendingEntry : pending) {
            final TarArchiveEntry entry = pendingEntry.entry;
            if (entry.isLink() && !names.contains(entry.getLinkName())) {
                throw new ArchiveException("Hard link target of entry '%s' points outside of the archive: '%s'", entry.getName(), entry.getLinkName());
            }
        }
    }

    private void writeEntries(final List<PendingEntry> pending, final OutputStream outputStream) throws IOException {
        final TarArchiveOutputStream tarOutput = new TarArchiveOutputStream(outputStream);
        tarOutput.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);
        for (final PendingEntry pendingEntry : pending) {
            tarOutput.putArchiveEntry(pendingEntry.entry);
            if (pendingEntry.content != null) {
                tarOutput.write(pendingEntry.content);
            }
            tarOutput.closeArchiveEntry();
        }
        tarOutput.finish();
        tarOutput.flush();
    }

    /**
     * Repacks the TAR archive read from the given input stream to the given output stream.
     *
     * <p>
     * The whole source archive is read and validated before the first output byte is written, so a rejected archive
     * produces no output at all. Neither stream is closed by this method.
     * </p>
     *
     * @param inputStream the source TAR archive.
     * @param outputStream the destination for the canonical TAR archive.
     * @throws IOException if reading or writing fails, or the source archive is rejected.
     */
    public void repack(final InputStream inputStream, final OutputStream outputStream) throws IOException {
        writeEntries(readEntries(inputStream), outputStream);
    }

    /**
     * Repacks the TAR archive at {@code source} to {@code target}.
     *
     * <p>
     * The canonical archive is written to a temporary file in the target directory first and then moved over
     * {@code target}, so if anything fails a pre-existing target file is left unchanged.
     * </p>
     *
     * @param source the source TAR archive.
     * @param target the destination path, replaced if it exists.
     * @throws IOException if reading or writing fails, or the source archive is rejected.
     */
    public void repack(final Path source, final Path target) throws IOException {
        final Path absoluteTarget = target.toAbsolutePath();
        final Path directory = absoluteTarget.getParent();
        final Path tempFile = Files.createTempFile(directory, "tar-repack-", ".tmp");
        boolean published = false;
        try {
            try (InputStream inputStream = Files.newInputStream(source);
                    OutputStream outputStream = Files.newOutputStream(tempFile)) {
                repack(inputStream, outputStream);
            }
            move(tempFile, absoluteTarget);
            published = true;
        } finally {
            if (!published) {
                Files.deleteIfExists(tempFile);
            }
        }
    }

    private void move(final Path tempFile, final Path target) throws IOException {
        try {
            Files.move(tempFile, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (final AtomicMoveNotSupportedException ignored) {
            Files.move(tempFile, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
