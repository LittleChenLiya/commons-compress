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
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.apache.commons.compress.archivers.ArchiveException;

/**
 * An index of the hard link entries ({@link TarArchiveEntry#isLink()}) of a {@link TarFile} that resolves every hard
 * link to the final content-bearing entry it refers to.
 *
 * <p>
 * In a TAR archive a hard link entry carries no data of its own; it only names another archive member whose content it
 * shares. The target may appear <em>later</em> in the archive than the link, may itself be another hard link (forming a
 * reference chain), may be missing, may be ambiguous because several members share the target name, may escape the
 * archive root, or the chain may form a cycle. This index resolves all of those cases up front, using entry metadata
 * only; nothing is ever extracted to the file system.
 * </p>
 *
 * <p>
 * Link target paths are resolved against the archive root: {@code .} and empty segments are dropped, {@code ..} pops
 * one segment, and a {@code ..} that would escape the archive root is reported as {@link Status#OUT_OF_BOUNDS}. A
 * leading {@code /} anchors the path at the archive root.
 * </p>
 *
 * <p>
 * An index is bound to the archive it was built from through {@link TarFile#getArchiveDigest()}. If the archive is
 * replaced, using the stale index with another {@link TarFile} fails with an {@link ArchiveException}.
 * </p>
 *
 * <p>
 * Normal {@link TarFile} reading is unaffected by this index; it is an opt-in view. In particular
 * {@link TarFile#getInputStream(TarArchiveEntry)} on a hard link entry still streams the entry's own (empty) data,
 * while {@link #getInputStream(TarFile, TarArchiveEntry)} streams the resolved content entry's data so a hard link is
 * never mistaken for an empty file.
 * </p>
 *
 * @since 1.29.0
 */
public final class TarHardLinkIndex {

    /**
     * The outcome of resolving one hard link entry.
     */
    public enum Status {
        /** The reference chain ends at a content-bearing entry. */
        RESOLVED,
        /** The link target does not exist in the archive. */
        MISSING_TARGET,
        /** The link target path escapes the archive root (for example {@code ../...}). */
        OUT_OF_BOUNDS,
        /** More than one archive member has the target name, so the target is ambiguous. */
        AMBIGUOUS_TARGET,
        /** The reference chain forms a cycle (for example {@code a} links to {@code b} and {@code b} to {@code a}). */
        CYCLE
    }

    /**
     * The resolution of a single hard link entry: the final content entry identity (when resolved) and the reference
     * chain that was followed.
     */
    public static final class Resolution {

        private final Status status;

        private final List<TarArchiveEntry> chain;

        private final TarArchiveEntry contentEntry;

        private final String message;

        private Resolution(final Status status, final List<TarArchiveEntry> chain, final TarArchiveEntry contentEntry, final String message) {
            this.status = status;
            this.chain = Collections.unmodifiableList(new ArrayList<>(chain));
            this.contentEntry = contentEntry;
            this.message = message;
        }

        /**
         * Gets the reference chain that was followed, starting with the queried entry. For a {@link Status#CYCLE} the
         * chain contains the full cycle, repeating the entry where the loop closes.
         *
         * @return the reference chain, never empty.
         */
        public List<TarArchiveEntry> getChain() {
            return chain;
        }

        /**
         * Gets the names of the entries in the reference chain, in follow order.
         *
         * @return the chain entry names, never empty.
         */
        public List<String> getChainNames() {
            final List<String> names = new ArrayList<>(chain.size());
            for (final TarArchiveEntry entry : chain) {
                names.add(entry.getName());
            }
            return names;
        }

        /**
         * Gets the final content-bearing entry this link resolves to.
         *
         * @return the content entry, or {@code null} unless the status is {@link Status#RESOLVED}.
         */
        public TarArchiveEntry getContentEntry() {
            return contentEntry;
        }

        /**
         * Gets a human readable description of the outcome.
         *
         * @return the message.
         */
        public String getMessage() {
            return message;
        }

        /**
         * Gets the resolution status.
         *
         * @return the status.
         */
        public Status getStatus() {
            return status;
        }

        /**
         * Tests whether the link resolved to a content-bearing entry.
         *
         * @return {@code true} if the status is {@link Status#RESOLVED}.
         */
        public boolean isResolved() {
            return status == Status.RESOLVED;
        }

        @Override
        public String toString() {
            return status + " " + getChainNames() + (message != null ? " (" + message + ")" : "");
        }
    }

    /**
     * Builds a hard link index for the given TAR file. Only entry metadata is inspected; nothing is extracted to the
     * file system.
     *
     * @param tarFile the TAR file to index.
     * @return the index, bound to the archive digest of {@code tarFile}, see {@link TarFile#getArchiveDigest()}.
     * @throws IOException if the archive digest cannot be computed.
     */
    public static TarHardLinkIndex build(final TarFile tarFile) throws IOException {
        return new TarHardLinkIndex(tarFile);
    }

    private static String joinNames(final List<TarArchiveEntry> chain) {
        final StringBuilder builder = new StringBuilder();
        for (final TarArchiveEntry entry : chain) {
            if (builder.length() > 0) {
                builder.append(" -> ");
            }
            builder.append(entry.getName());
        }
        return builder.toString();
    }

    /**
     * Normalizes a link target path against the archive root.
     *
     * @param rawPath the raw link target path.
     * @return the normalized root-relative path, or {@code null} if the path escapes the archive root.
     */
    private static String normalizeLinkPath(final String rawPath) {
        if (rawPath == null) {
            return null;
        }
        final Deque<String> segments = new ArrayDeque<>();
        for (final String segment : rawPath.split("/")) {
            if (segment.isEmpty() || ".".equals(segment)) {
                continue;
            }
            if ("..".equals(segment)) {
                if (segments.isEmpty()) {
                    // would escape the archive root
                    return null;
                }
                segments.removeLast();
            } else {
                segments.addLast(segment);
            }
        }
        return String.join("/", segments);
    }

    private final String archiveDigest;

    private final Map<String, Resolution> resolutionsByEntryName;

    private TarHardLinkIndex(final TarFile tarFile) throws IOException {
        this.archiveDigest = tarFile.getArchiveDigest();
        final Map<String, List<TarArchiveEntry>> entriesByPath = new LinkedHashMap<>();
        for (final TarArchiveEntry entry : tarFile.getEntries()) {
            String path = normalizeLinkPath(entry.getName());
            if (path == null) {
                path = entry.getName();
            }
            entriesByPath.computeIfAbsent(path, key -> new ArrayList<>()).add(entry);
        }
        final Map<String, Resolution> resolutions = new LinkedHashMap<>();
        for (final TarArchiveEntry entry : tarFile.getEntries()) {
            if (entry.isLink()) {
                resolutions.putIfAbsent(entry.getName(), resolveLink(entry, entriesByPath));
            }
        }
        this.resolutionsByEntryName = Collections.unmodifiableMap(resolutions);
    }

    /**
     * Checks that this index is bound to the given TAR file.
     *
     * @param tarFile the TAR file to check.
     * @throws ArchiveException if the archive was replaced since this index was built (digest mismatch).
     */
    public void checkArchive(final TarFile tarFile) throws ArchiveException {
        Objects.requireNonNull(tarFile, "tarFile");
        final String digest;
        try {
            digest = tarFile.getArchiveDigest();
        } catch (final IOException e) {
            throw new ArchiveException("Cannot compute the archive digest", e);
        }
        if (!archiveDigest.equals(digest)) {
            throw new ArchiveException(
                    "This TarHardLinkIndex was built for a different archive (digest %s) and refuses to serve archive with digest %s",
                    archiveDigest, digest);
        }
    }

    /**
     * Gets the digest of the archive this index is bound to.
     *
     * @return the archive digest, see {@link TarFile#getArchiveDigest()}.
     */
    public String getArchiveDigest() {
        return archiveDigest;
    }

    /**
     * Gets an input stream for the content the given entry resolves to. For a hard link entry this streams the final
     * content entry's data, so a hard link is never read as an empty file; for any other entry this behaves exactly
     * like {@link TarFile#getInputStream(TarArchiveEntry)}.
     *
     * @param tarFile the TAR file to read from; must be the archive this index is bound to.
     * @param entry   the entry to read.
     * @return an input stream of the resolved content.
     * @throws ArchiveException if the archive was replaced, or the entry is a hard link that does not resolve.
     * @throws IOException      if reading fails.
     */
    public InputStream getInputStream(final TarFile tarFile, final TarArchiveEntry entry) throws IOException {
        final Resolution resolution = resolve(tarFile, entry);
        if (!resolution.isResolved()) {
            throw new ArchiveException("Cannot read hard link '%s': %s", entry.getName(), resolution.getMessage());
        }
        return tarFile.getInputStream(resolution.getContentEntry());
    }

    /**
     * Gets the resolutions of all hard link entries, keyed by entry name.
     *
     * @return the resolutions, in archive order.
     */
    public Map<String, Resolution> getResolutions() {
        return resolutionsByEntryName;
    }

    /**
     * Resolves the given entry. Hard link entries are looked up in this index; any other entry trivially resolves to
     * itself.
     *
     * @param tarFile the TAR file the entry belongs to; must be the archive this index is bound to.
     * @param entry   the entry to resolve.
     * @return the resolution, never {@code null}.
     * @throws ArchiveException if the archive was replaced since this index was built (digest mismatch).
     */
    public Resolution resolve(final TarFile tarFile, final TarArchiveEntry entry) throws ArchiveException {
        Objects.requireNonNull(entry, "entry");
        checkArchive(tarFile);
        if (!entry.isLink()) {
            final List<TarArchiveEntry> chain = new ArrayList<>(1);
            chain.add(entry);
            return new Resolution(Status.RESOLVED, chain, entry, "Not a hard link entry");
        }
        final Resolution resolution = resolutionsByEntryName.get(entry.getName());
        if (resolution == null) {
            throw new ArchiveException("Hard link entry '%s' is not part of the indexed archive", entry.getName());
        }
        return resolution;
    }

    /**
     * Resolves the hard link entry with the given name.
     *
     * @param tarFile   the TAR file to resolve against; must be the archive this index is bound to.
     * @param entryName the entry name.
     * @return the resolution, or {@code null} if no hard link entry with that name exists in the archive.
     * @throws ArchiveException if the archive was replaced since this index was built (digest mismatch).
     */
    public Resolution resolve(final TarFile tarFile, final String entryName) throws ArchiveException {
        checkArchive(tarFile);
        return resolutionsByEntryName.get(entryName);
    }

    private Resolution resolveLink(final TarArchiveEntry linkEntry, final Map<String, List<TarArchiveEntry>> entriesByPath) {
        final List<TarArchiveEntry> chain = new ArrayList<>();
        chain.add(linkEntry);
        TarArchiveEntry current = linkEntry;
        while (true) {
            final String linkName = current.getLinkName();
            final String targetPath = normalizeLinkPath(linkName);
            if (targetPath == null) {
                return new Resolution(Status.OUT_OF_BOUNDS, chain, null,
                        "Hard link target '" + linkName + "' of entry '" + current.getName() + "' escapes the archive root");
            }
            final List<TarArchiveEntry> candidates = entriesByPath.get(targetPath);
            if (candidates == null || candidates.isEmpty()) {
                return new Resolution(Status.MISSING_TARGET, chain, null,
                        "Hard link target '" + linkName + "' of entry '" + current.getName() + "' does not exist in the archive");
            }
            if (candidates.size() > 1) {
                return new Resolution(Status.AMBIGUOUS_TARGET, chain, null, "Hard link target '" + linkName + "' of entry '" + current.getName()
                        + "' is ambiguous: " + candidates.size() + " archive members are named '" + targetPath + "'");
            }
            final TarArchiveEntry next = candidates.get(0);
            // identity scan: TarArchiveEntry overrides equals()
            boolean cycle = false;
            for (final TarArchiveEntry visited : chain) {
                if (visited == next) {
                    cycle = true;
                    break;
                }
            }
            if (cycle) {
                chain.add(next);
                return new Resolution(Status.CYCLE, chain, null, "Hard link cycle detected: " + joinNames(chain));
            }
            chain.add(next);
            if (!next.isLink()) {
                return new Resolution(Status.RESOLVED, chain, next, "Resolved to content entry '" + next.getName() + "'");
            }
            current = next;
        }
    }
}
