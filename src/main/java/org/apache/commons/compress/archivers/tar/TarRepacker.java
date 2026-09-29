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
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import org.apache.commons.compress.archivers.ArchiveException;
import org.apache.commons.compress.utils.IOUtils;

/**
 * Repackages a TAR archive into a deterministic, normalized form.
 *
 * <p>
 * The repacker reads an existing TAR archive and writes a new archive that:
 * </p>
 * <ul>
 * <li>orders entries by their normalized path names;</li>
 * <li>applies a single configurable modification time, user id, group id, user name and group name to every entry;</li>
 * <li>keeps the contents of regular files unchanged;</li>
 * <li>uses deterministic POSIX PAX extended headers for long names and drops unstable extended header fields of the
 * source archive, such as access and status change times;</li>
 * <li>rejects archives containing duplicate normalized paths, absolute paths, paths that escape the archive root
 * through {@code ..} segments, and links whose targets point outside of the archive.</li>
 * </ul>
 *
 * <p>
 * Two archives that contain the same entries with the same file contents produce byte-for-byte identical output,
 * regardless of entry order and entry metadata of the input archives.
 * </p>
 *
 * <p>
 * The {@link #repack(Path, Path)} variant writes to a temporary file first and only moves it over the target when the
 * whole archive has been written successfully, so a pre-existing target file is left unchanged when repacking fails.
 * </p>
 *
 * <p>
 * Only regular files, directories, symbolic links and hard links are supported; entries of any other type are
 * rejected. Entry contents are buffered in memory while repacking.
 * </p>
 *
 * @since 1.29.0
 */
public final class TarRepacker {

    /**
     * Builds {@link TarRepacker} instances.
     */
    public static final class Builder {

        private String groupName = "";
        private long groupId;
        private FileTime modificationTime = FileTime.from(Instant.EPOCH);
        private String userName = "";
        private long userId;

        private Builder() {
            // Use builder().
        }

        /**
         * Constructs a new {@link TarRepacker} with this builder's settings.
         *
         * @return a new {@link TarRepacker}.
         */
        public TarRepacker get() {
            return new TarRepacker(this);
        }

        /**
         * Sets the group id applied to every written entry, defaults to 0.
         *
         * @param groupId the group id.
         * @return {@code this} instance.
         */
        public Builder setGroupId(final long groupId) {
            this.groupId = groupId;
            return this;
        }

        /**
         * Sets the group name applied to every written entry, defaults to the empty string.
         *
         * @param groupName the group name.
         * @return {@code this} instance.
         */
        public Builder setGroupName(final String groupName) {
            this.groupName = Objects.requireNonNull(groupName, "groupName");
            return this;
        }

        /**
         * Sets the modification time applied to every written entry, defaults to the epoch.
         *
         * @param modificationTime the modification time.
         * @return {@code this} instance.
         */
        public Builder setModificationTime(final FileTime modificationTime) {
            this.modificationTime = Objects.requireNonNull(modificationTime, "modificationTime");
            return this;
        }

        /**
         * Sets the modification time applied to every written entry, defaults to the epoch.
         *
         * @param modificationTime the modification time.
         * @return {@code this} instance.
         */
        public Builder setModificationTime(final Instant modificationTime) {
            return setModificationTime(FileTime.from(Objects.requireNonNull(modificationTime, "modificationTime")));
        }

        /**
         * Sets the user id applied to every written entry, defaults to 0.
         *
         * @param userId the user id.
         * @return {@code this} instance.
         */
        public Builder setUserId(final long userId) {
            this.userId = userId;
            return this;
        }

        /**
         * Sets the user name applied to every written entry, defaults to the empty string.
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
     * Holds a single entry read from the source archive before it is written in sorted order.
     */
    private static final class PendingEntry {

        private final byte[] data;
        private final byte linkFlag;
        private final String linkName;
        private final int mode;
        private final String name;

        private PendingEntry(final String name, final byte linkFlag, final int mode, final String linkName, final byte[] data) {
            this.name = name;
            this.linkFlag = linkFlag;
            this.mode = mode;
            this.linkName = linkName;
            this.data = data;
        }
    }

    /**
     * Constructs a new {@link Builder}.
     *
     * @return a new {@link Builder}.
     */
    public static Builder builder() {
        return new Builder();
    }

    private static boolean isAbsolutePath(final String path) {
        return path.startsWith("/") || path.startsWith("\\") || path.length() > 1 && path.charAt(1) == ':' && Character.isLetter(path.charAt(0));
    }

    /**
     * Normalizes an entry name read from the source archive, stripping trailing slashes from directory names.
     */
    private static String normalizeEntryName(final String name) throws ArchiveException {
        String stripped = name;
        while (stripped.endsWith("/")) {
            stripped = stripped.substring(0, stripped.length() - 1);
        }
        return normalizePath(stripped, "entry name");
    }

    /**
     * Normalizes a relative path by resolving {@code .} and {@code ..} segments lexically.
     *
     * @param path the path to normalize.
     * @param description describes the path for error messages.
     * @return the normalized relative path.
     * @throws ArchiveException if the path is empty, absolute, escapes the archive root or resolves to the root itself.
     */
    private static String normalizePath(final String path, final String description) throws ArchiveException {
        if (path.isEmpty()) {
            throw new ArchiveException("Empty %s", description);
        }
        if (isAbsolutePath(path)) {
            throw new ArchiveException("Absolute %s is not allowed: %s", description, path);
        }
        final Deque<String> segments = new ArrayDeque<>();
        for (final String segment : path.split("/")) {
            if (segment.isEmpty() || ".".equals(segment)) {
                continue;
            }
            if ("..".equals(segment)) {
                if (segments.isEmpty()) {
                    throw new ArchiveException("%s escapes the archive root: %s", description, path);
                }
                segments.removeLast();
            } else {
                segments.addLast(segment);
            }
        }
        if (segments.isEmpty()) {
            throw new ArchiveException("%s resolves to the archive root: %s", description, path);
        }
        return String.join("/", segments);
    }

    private final long groupId;
    private final String groupName;
    private final FileTime modificationTime;
    private final long userId;
    private final String userName;

    private TarRepacker(final Builder builder) {
        this.groupId = builder.groupId;
        this.groupName = builder.groupName;
        this.modificationTime = builder.modificationTime;
        this.userId = builder.userId;
        this.userName = builder.userName;
    }

    /**
     * Validates a symbolic link target, which is interpreted relative to the directory containing the link.
     */
    private void checkSymbolicLinkTarget(final String entryName, final String linkName) throws ArchiveException {
        if (linkName.isEmpty()) {
            throw new ArchiveException("Empty link target for entry: %s", entryName);
        }
        if (isAbsolutePath(linkName)) {
            throw new ArchiveException("Link target of %s is absolute: %s", entryName, linkName);
        }
        final int slash = entryName.lastIndexOf('/');
        final String parent = slash < 0 ? "" : entryName.substring(0, slash + 1);
        normalizePath(parent + linkName, "link target of " + entryName);
    }

    private List<PendingEntry> readEntries(final InputStream inputStream) throws IOException {
        final List<PendingEntry> entries = new ArrayList<>();
        final Set<String> names = new HashSet<>();
        final Set<String> fileNames = new HashSet<>();
        final TarArchiveInputStream tarInput = new TarArchiveInputStream(inputStream, StandardCharsets.UTF_8.name());
        TarArchiveEntry entry;
        while ((entry = tarInput.getNextEntry()) != null) {
            if (!entry.isCheckSumOK()) {
                throw new ArchiveException("Bad checksum for entry: %s", entry.getName());
            }
            final String name = normalizeEntryName(entry.getName());
            if (!names.add(name)) {
                throw new ArchiveException("Duplicate entry name: %s", name);
            }
            final byte linkFlag = entry.getLinkFlag();
            if (entry.isDirectory()) {
                entries.add(new PendingEntry(name, TarConstants.LF_DIR, entry.getMode(), null, null));
            } else if (linkFlag == TarConstants.LF_SYMLINK) {
                final String linkName = entry.getLinkName();
                checkSymbolicLinkTarget(name, linkName);
                entries.add(new PendingEntry(name, TarConstants.LF_SYMLINK, entry.getMode(), linkName, null));
            } else if (linkFlag == TarConstants.LF_LINK) {
                final String linkName = normalizePath(entry.getLinkName(), "link target of " + name);
                entries.add(new PendingEntry(name, TarConstants.LF_LINK, entry.getMode(), linkName, null));
            } else if (linkFlag == TarConstants.LF_NORMAL || linkFlag == TarConstants.LF_OLDNORM || linkFlag == TarConstants.LF_CONTIG) {
                entries.add(new PendingEntry(name, TarConstants.LF_NORMAL, entry.getMode(), null, IOUtils.toByteArray(tarInput)));
                fileNames.add(name);
            } else {
                throw new ArchiveException("Unsupported entry type %s for entry: %s", (int) linkFlag, name);
            }
        }
        for (final PendingEntry pending : entries) {
            if (pending.linkFlag == TarConstants.LF_LINK && !fileNames.contains(pending.linkName)) {
                throw new ArchiveException("Hard link target of %s is not a file in the archive: %s", pending.name, pending.linkName);
            }
        }
        entries.sort(Comparator.comparing(pending -> pending.name));
        return entries;
    }

    /**
     * Repacks the TAR archive read from {@code source} and writes the normalized archive to {@code target}.
     *
     * <p>
     * The normalized archive is first written to a temporary file in the target directory which is then moved over
     * {@code target}. If repacking fails for any reason, a pre-existing target file is left unchanged.
     * </p>
     *
     * @param source the path of the TAR archive to read.
     * @param target the path of the normalized TAR archive to write.
     * @throws IOException if reading or writing fails or the source archive is rejected.
     */
    public void repack(final Path source, final Path target) throws IOException {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(target, "target");
        final Path absoluteTarget = target.toAbsolutePath();
        final Path parent = absoluteTarget.getParent();
        final Path temp = Files.createTempFile(parent != null ? parent : Paths.get("."), getClass().getSimpleName(), ".tmp");
        boolean success = false;
        try {
            try (InputStream inputStream = Files.newInputStream(source);
                    OutputStream outputStream = Files.newOutputStream(temp)) {
                repack(inputStream, outputStream);
            }
            try {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (final AtomicMoveNotSupportedException e) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
            success = true;
        } finally {
            if (!success) {
                Files.deleteIfExists(temp);
            }
        }
    }

    /**
     * Repacks the TAR archive read from {@code inputStream} and writes the normalized archive to
     * {@code outputStream}. Neither stream is closed by this method.
     *
     * @param inputStream the TAR archive to read.
     * @param outputStream the destination of the normalized TAR archive.
     * @throws IOException if reading or writing fails or the source archive is rejected.
     */
    public void repack(final InputStream inputStream, final OutputStream outputStream) throws IOException {
        Objects.requireNonNull(inputStream, "inputStream");
        Objects.requireNonNull(outputStream, "outputStream");
        writeEntries(readEntries(inputStream), outputStream);
    }

    private void writeEntries(final List<PendingEntry> entries, final OutputStream outputStream) throws IOException {
        final TarArchiveOutputStream tarOutput = new TarArchiveOutputStream(outputStream, StandardCharsets.UTF_8.name());
        tarOutput.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);
        tarOutput.setBigNumberMode(TarArchiveOutputStream.BIGNUMBER_POSIX);
        for (final PendingEntry pending : entries) {
            final String entryName = pending.linkFlag == TarConstants.LF_DIR ? pending.name + "/" : pending.name;
            final TarArchiveEntry entry = new TarArchiveEntry(entryName, pending.linkFlag);
            entry.setMode(pending.mode);
            entry.setLastModifiedTime(modificationTime);
            entry.setUserId(userId);
            entry.setGroupId(groupId);
            entry.setUserName(userName);
            entry.setGroupName(groupName);
            if (pending.linkName != null) {
                entry.setLinkName(pending.linkName);
            }
            if (pending.data != null) {
                entry.setSize(pending.data.length);
            }
            tarOutput.putArchiveEntry(entry);
            if (pending.data != null) {
                tarOutput.write(pending.data);
            }
            tarOutput.closeArchiveEntry();
        }
        tarOutput.finish();
        tarOutput.flush();
    }
}
