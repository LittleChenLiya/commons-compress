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
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * An index of the hard links contained in a {@link TarFile}.
 *
 * <p>
 * A TAR hard link entry (link flag {@link TarConstants#LF_LINK}) stores no data of its own; it references another entry by name. The referenced entry may
 * appear later in the archive. This index resolves every hard link to the entry that actually provides the content, following chains of hard links (a link may
 * point to another link) and recording the full reference chain from the link entry to the content entry.
 * </p>
 *
 * <p>
 * Link paths are resolved relative to the archive root, not relative to the directory of the link entry. A link path that is absolute or that escapes the
 * archive root by means of {@code ..} segments is reported as {@link TarHardLinkException.Reason#OUT_OF_BOUNDS}. A link whose target does not exist is reported
 * as {@link TarHardLinkException.Reason#MISSING_TARGET}. A link whose target name matches more than one entry is reported as
 * {@link TarHardLinkException.Reason#AMBIGUOUS_TARGET}. A chain of links that leads back to one of the link entries is reported as
 * {@link TarHardLinkException.Reason#CYCLE}.
 * </p>
 *
 * <p>
 * The index is bound to a digest of the archive it was built from. If the archive is replaced after the index was built, the index refuses to resolve links for
 * the changed archive. Building the index only reads the raw archive bytes; it does not extract anything to the file system.
 * </p>
 *
 * @since 1.29.0
 */
public final class TarHardLinkIndex {

    /**
     * The resolved content entry and reference chain for a hard link entry.
     */
    public static final class Resolution {

        private final TarArchiveEntry contentEntry;

        private final List<TarArchiveEntry> chain;

        Resolution(final TarArchiveEntry contentEntry, final List<TarArchiveEntry> chain) {
            this.contentEntry = Objects.requireNonNull(contentEntry, "contentEntry");
            this.chain = Collections.unmodifiableList(new ArrayList<>(chain));
        }

        /**
         * Gets the entry that provides the content of the hard link.
         *
         * @return the content entry, never null.
         */
        public TarArchiveEntry getContentEntry() {
            return contentEntry;
        }

        /**
         * Gets the reference chain from the hard link entry to the content entry.
         *
         * @return an unmodifiable list of entries, starting with the hard link entry and ending with the content entry.
         */
        public List<TarArchiveEntry> getChain() {
            return chain;
        }
    }

    private final Map<String, Resolution> resolutions;

    private final byte[] archiveDigest;

    private final String digestAlgorithm;

    private TarHardLinkIndex(final Map<String, Resolution> resolutions, final byte[] archiveDigest, final String digestAlgorithm) {
        this.resolutions = resolutions;
        this.archiveDigest = archiveDigest.clone();
        this.digestAlgorithm = digestAlgorithm;
    }

    /**
     * Builds a hard link index for the provided {@link TarFile}.
     *
     * <p>
     * Building the index reads the raw archive bytes to compute the archive digest; it does not extract anything to the file system.
     * </p>
     *
     * @param tarFile the tar file to build the index for.
     * @return the hard link index.
     * @throws IOException          if reading the archive fails.
     * @throws TarHardLinkException if a hard link cannot be resolved.
     */
    public static TarHardLinkIndex build(final TarFile tarFile) throws IOException {
        Objects.requireNonNull(tarFile, "tarFile");
        final List<TarArchiveEntry> entries = tarFile.getEntries();
        // Index entries by their normalized name. A name that matches more than one entry makes the target ambiguous.
        final Map<String, List<TarArchiveEntry>> entriesByName = new HashMap<>();
        for (final TarArchiveEntry entry : entries) {
            final String normalizedName = normalize(entry.getName());
            if (normalizedName != null) {
                entriesByName.computeIfAbsent(normalizedName, k -> new ArrayList<>()).add(entry);
            }
        }
        // Resolve hard links in archive order so that forward references are supported.
        final Map<String, Resolution> resolutions = new LinkedHashMap<>();
        for (final TarArchiveEntry entry : entries) {
            if (!entry.isLink()) {
                continue;
            }
            final String linkName = entry.getName();
            final String targetName = normalize(entry.getLinkName());
            if (targetName == null) {
                throw new TarHardLinkException(TarHardLinkException.Reason.OUT_OF_BOUNDS, linkName, entry.getLinkName(),
                        Collections.singletonList(linkName));
            }
            final List<TarArchiveEntry> chain = new ArrayList<>();
            chain.add(entry);
            TarArchiveEntry contentEntry = null;
            String currentTarget = targetName;
            while (contentEntry == null) {
                final List<TarArchiveEntry> candidates = entriesByName.get(currentTarget);
                if (candidates == null || candidates.isEmpty()) {
                    throw new TarHardLinkException(TarHardLinkException.Reason.MISSING_TARGET, linkName, currentTarget, toNames(chain));
                }
                if (candidates.size() > 1) {
                    throw new TarHardLinkException(TarHardLinkException.Reason.AMBIGUOUS_TARGET, linkName, currentTarget, toNames(chain));
                }
                final TarArchiveEntry candidate = candidates.get(0);
                if (chain.contains(candidate)) {
                    // The link chain leads back to an entry already visited.
                    final List<String> chainNames = new ArrayList<>();
                    for (final TarArchiveEntry chainEntry : chain) {
                        chainNames.add(chainEntry.getName());
                    }
                    chainNames.add(candidate.getName());
                    throw new TarHardLinkException(TarHardLinkException.Reason.CYCLE, linkName, candidate.getName(), chainNames);
                }
                chain.add(candidate);
                if (candidate.isLink()) {
                    final String nextTarget = normalize(candidate.getLinkName());
                    if (nextTarget == null) {
                        throw new TarHardLinkException(TarHardLinkException.Reason.OUT_OF_BOUNDS, linkName, candidate.getLinkName(),
                                toNames(chain));
                    }
                    currentTarget = nextTarget;
                } else {
                    contentEntry = candidate;
                }
            }
            resolutions.put(linkName, new Resolution(contentEntry, chain));
        }
        final String digestAlgorithm = TarFile.getDigestAlgorithm();
        final byte[] archiveDigest = tarFile.getArchiveDigest();
        return new TarHardLinkIndex(resolutions, archiveDigest, digestAlgorithm);
    }

    private static List<String> toNames(final List<TarArchiveEntry> chain) {
        final List<String> names = new ArrayList<>();
        for (final TarArchiveEntry entry : chain) {
            names.add(entry.getName());
        }
        return names;
    }

    /**
     * Normalizes a tar entry or link name, resolving {@code .} and {@code ..} segments against the archive root.
     *
     * @param name the name to normalize.
     * @return the normalized name, or null if the name is absolute or escapes the archive root.
     */
    static String normalize(final String name) {
        if (name == null || name.isEmpty()) {
            return null;
        }
        // Normalize Windows-style separators to forward slashes so that traversal attempts are caught.
        final String normalized = name.replace('\\', '/');
        // Absolute paths are out of bounds.
        if (normalized.charAt(0) == '/') {
            return null;
        }
        // Windows drive-letter paths such as "C:/foo" are absolute on Windows.
        if (normalized.length() >= 3 && isDriveLetter(normalized.charAt(0)) && normalized.charAt(1) == ':'
                && normalized.charAt(2) == '/') {
            return null;
        }
        final Deque<String> stack = new ArrayDeque<>();
        for (final String segment : normalized.split("/")) {
            if (segment.isEmpty() || ".".equals(segment)) {
                continue;
            }
            if ("..".equals(segment)) {
                if (stack.isEmpty()) {
                    return null;
                }
                stack.pop();
            } else {
                stack.push(segment);
            }
        }
        if (stack.isEmpty()) {
            return null;
        }
        final List<String> reversed = new ArrayList<>(stack);
        Collections.reverse(reversed);
        return String.join("/", reversed);
    }

    private static boolean isDriveLetter(final char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
    }

    /**
     * Gets the digest of the archive this index was built from.
     *
     * @return a copy of the archive digest.
     */
    public byte[] getArchiveDigest() {
        return archiveDigest.clone();
    }

    /**
     * Gets the algorithm used to compute the archive digest.
     *
     * @return the digest algorithm.
     */
    public String getDigestAlgorithm() {
        return digestAlgorithm;
    }

    /**
     * Gets the entry that provides the content of the provided hard link entry.
     *
     * @param linkEntry the hard link entry.
     * @return the content entry.
     * @throws IllegalArgumentException if the provided entry is not a hard link or is not part of this index.
     */
    public TarArchiveEntry getContentEntry(final TarArchiveEntry linkEntry) {
        return getResolution(linkEntry).getContentEntry();
    }

    /**
     * Gets the reference chain from the provided hard link entry to the content entry.
     *
     * @param linkEntry the hard link entry.
     * @return an unmodifiable list of entries, starting with the hard link entry and ending with the content entry.
     * @throws IllegalArgumentException if the provided entry is not a hard link or is not part of this index.
     */
    public List<TarArchiveEntry> getChain(final TarArchiveEntry linkEntry) {
        return getResolution(linkEntry).getChain();
    }

    private Resolution getResolution(final TarArchiveEntry linkEntry) {
        Objects.requireNonNull(linkEntry, "linkEntry");
        if (!linkEntry.isLink()) {
            throw new IllegalArgumentException("Entry '" + linkEntry.getName() + "' is not a hard link");
        }
        final Resolution resolution = resolutions.get(linkEntry.getName());
        if (resolution == null) {
            throw new IllegalArgumentException("Entry '" + linkEntry.getName() + "' is not part of this hard link index");
        }
        return resolution;
    }

    /**
     * Gets the number of hard links in this index.
     *
     * @return the number of hard links.
     */
    public int size() {
        return resolutions.size();
    }

    @Override
    public String toString() {
        return String.format(Locale.ROOT, "TarHardLinkIndex[links=%d, digest=%s]", Integer.valueOf(resolutions.size()), digestAlgorithm);
    }
}
