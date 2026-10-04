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

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Index of the entries of a {@link ZipFile} grouped by name, explicitly reporting name conflicts instead of silently treating the first entry with a given
 * name as the only answer.
 * <p>
 * A ZIP central directory may contain several entries whose raw names are identical (for example two files called {@code a.txt}). Depending on the tools
 * that created the archive it may also contain entries whose names only collide after normalization, for example {@code a.txt} and {@code A.TXT} on a
 * case-insensitive file system, or {@code a\b.txt} and {@code a/b.txt} when a Windows tool used a backslash as the separator. This index makes all such
 * collisions visible: every entry is reported with its identity (its position inside the central directory, the raw name and whether it is a directory), so
 * callers can tell conflicting entries apart instead of losing all but the first one.
 * </p>
 * <h2>Normalization rules</h2>
 * <p>
 * The rules that turn a raw entry name into the key used for grouping are chosen by the caller through the {@link Builder}:
 * </p>
 * <ol>
 * <li><b>Case rule</b> ({@link CaseSensitivity}): {@link CaseSensitivity#CASE_SENSITIVE} (the default) keeps {@code a.txt} and {@code A.TXT} distinct;
 * {@link CaseSensitivity#CASE_INSENSITIVE} folds case using a locale-independent mapping ({@link Locale#ROOT}), so {@code a.txt} and {@code A.TXT} collide.
 * Case folding is applied after slash normalization and only affects ASCII letters.</li>
 * <li><b>Slash rule</b> ({@link SlashNormalization}): {@link SlashNormalization#PRESERVE_BACKSLASH} (the default) treats {@code \} as an ordinary name
 * character, so {@code a\b.txt} and {@code a/b.txt} are distinct; {@link SlashNormalization#NORMALIZE_BACKSLASH_TO_SLASH} treats both characters as
 * equivalent separators, so {@code a\b.txt} and {@code a/b.txt} collide. The forward slash {@code /} is always the canonical separator.</li>
 * </ol>
 * <p>
 * The trailing slash that marks a directory entry (for example {@code a.txt/}) is part of the raw name and is never stripped, so a file {@code a.txt} and a
 * directory {@code a.txt/} keep distinct keys. Every reported identity nevertheless carries its {@link EntryIdentity#isDirectory() directory flag}, which
 * allows telling a same-named file and directory apart when they do end up in the same group.
 * </p>
 * <p>
 * With the default rules ({@link CaseSensitivity#CASE_SENSITIVE} and {@link SlashNormalization#PRESERVE_BACKSLASH}) the index groups entries by their raw
 * names, which reproduces the classic duplicate-name behavior. Choosing {@link CaseSensitivity#CASE_INSENSITIVE} and/or
 * {@link SlashNormalization#NORMALIZE_BACKSLASH_TO_SLASH} additionally reveals collisions introduced by case or separator normalization.
 * </p>
 * <p>
 * The index is built from the central directory only; it never reads entry payloads, so building it does not require extracting the archive. Entries are
 * always reported in central directory order.
 * </p>
 *
 * @since 1.29.0
 */
public final class ZipEntryConflictIndex {

    /**
     * Controls whether entry names are compared case-sensitively when grouping.
     *
     * @since 1.29.0
     */
    public enum CaseSensitivity {

        /**
         * Names are compared exactly as stored; {@code a.txt} and {@code A.TXT} do not collide. This is the default.
         */
        CASE_SENSITIVE,

        /**
         * Names are compared case-insensitively using a locale-independent case fold; {@code a.txt} and {@code A.TXT} collide.
         */
        CASE_INSENSITIVE
    }

    /**
     * Controls whether backslashes in entry names are normalized to forward slashes when grouping.
     *
     * @since 1.29.0
     */
    public enum SlashNormalization {

        /**
         * Backslashes are kept as ordinary name characters; {@code a\b.txt} and {@code a/b.txt} do not collide. This is the default.
         */
        PRESERVE_BACKSLASH,

        /**
         * Backslashes are treated as equivalent to the canonical separator {@code /}; {@code a\b.txt} and {@code a/b.txt} collide.
         */
        NORMALIZE_BACKSLASH_TO_SLASH
    }

    /**
     * The identity of a single central directory entry within a conflict group.
     * <p>
     * Two identities are equal only when they refer to the same physical central directory entry, that is when they share the same
     * {@link #getCentralDirectoryIndex() central directory index}.
     * </p>
     *
     * @since 1.29.0
     */
    public static final class EntryIdentity {

        private final int centralDirectoryIndex;
        private final ZipArchiveEntry entry;
        private final String name;
        private final boolean directory;

        EntryIdentity(final int centralDirectoryIndex, final ZipArchiveEntry entry) {
            this.centralDirectoryIndex = centralDirectoryIndex;
            this.entry = Objects.requireNonNull(entry, "entry");
            this.name = entry.getName();
            this.directory = entry.isDirectory();
        }

        @Override
        public boolean equals(final Object obj) {
            if (this == obj) {
                return true;
            }
            if (!(obj instanceof EntryIdentity)) {
                return false;
            }
            return centralDirectoryIndex == ((EntryIdentity) obj).centralDirectoryIndex;
        }

        /**
         * Gets the position of this entry inside the archive's central directory, zero-based.
         *
         * @return the zero-based central directory index.
         */
        public int getCentralDirectoryIndex() {
            return centralDirectoryIndex;
        }

        /**
         * Gets the raw entry name as stored in the central directory.
         *
         * @return the raw entry name.
         */
        public String getName() {
            return name;
        }

        /**
         * Gets the underlying entry, which can be passed to {@link ZipFile#getInputStream(ZipArchiveEntry)} to read its contents.
         *
         * @return the underlying entry.
         */
        public ZipArchiveEntry getEntry() {
            return entry;
        }

        @Override
        public int hashCode() {
            return centralDirectoryIndex;
        }

        /**
         * Tests whether this entry is a directory entry.
         *
         * @return {@code true} if this entry is a directory entry.
         */
        public boolean isDirectory() {
            return directory;
        }

        @Override
        public String toString() {
            return centralDirectoryIndex + ": " + name + (directory ? "/" : "");
        }
    }

    /**
     * A group of entries sharing the same normalized name.
     * <p>
     * A group is a conflict when it contains more than one entry. Entries are listed in central directory order.
     * </p>
     *
     * @since 1.29.0
     */
    public static final class ConflictGroup {

        private final String normalizedName;
        private final List<EntryIdentity> identities;

        ConflictGroup(final String normalizedName, final List<EntryIdentity> identities) {
            this.normalizedName = Objects.requireNonNull(normalizedName, "normalizedName");
            this.identities = Collections.unmodifiableList(new ArrayList<>(identities));
        }

        /**
         * Gets the unmodifiable list of entry identities in this group, in central directory order.
         *
         * @return the entry identities.
         */
        public List<EntryIdentity> getIdentities() {
            return identities;
        }

        /**
         * Gets the normalized name shared by all entries in this group.
         *
         * @return the normalized name.
         */
        public String getNormalizedName() {
            return normalizedName;
        }

        /**
         * Tests whether this group is a conflict, that is whether it contains more than one entry.
         *
         * @return {@code true} if this group contains more than one entry.
         */
        public boolean isConflict() {
            return identities.size() > 1;
        }

        @Override
        public String toString() {
            return normalizedName + " -> " + identities;
        }
    }

    /**
     * Builds a {@link ZipEntryConflictIndex} with configurable normalization rules.
     *
     * @since 1.29.0
     */
    public static final class Builder {

        private final ZipFile zipFile;
        private CaseSensitivity caseSensitivity = CaseSensitivity.CASE_SENSITIVE;
        private SlashNormalization slashNormalization = SlashNormalization.PRESERVE_BACKSLASH;

        Builder(final ZipFile zipFile) {
            this.zipFile = Objects.requireNonNull(zipFile, "zipFile");
        }

        /**
         * Builds the index.
         * <p>
         * This only reads the central directory; it never reads entry payloads.
         * </p>
         *
         * @return the conflict index.
         */
        public ZipEntryConflictIndex build() {
            return new ZipEntryConflictIndex(zipFile, caseSensitivity, slashNormalization);
        }

        /**
         * Sets the case rule. Defaults to {@link CaseSensitivity#CASE_SENSITIVE}.
         *
         * @param caseSensitivity the case rule, must not be {@code null}.
         * @return {@code this} instance.
         */
        public Builder setCaseSensitivity(final CaseSensitivity caseSensitivity) {
            this.caseSensitivity = Objects.requireNonNull(caseSensitivity, "caseSensitivity");
            return this;
        }

        /**
         * Sets the slash rule. Defaults to {@link SlashNormalization#PRESERVE_BACKSLASH}.
         *
         * @param slashNormalization the slash rule, must not be {@code null}.
         * @return {@code this} instance.
         */
        public Builder setSlashNormalization(final SlashNormalization slashNormalization) {
            this.slashNormalization = Objects.requireNonNull(slashNormalization, "slashNormalization");
            return this;
        }
    }

    /**
     * Creates a builder for the given ZIP file.
     *
     * @param zipFile the ZIP file to build the index from, must not be {@code null}.
     * @return a new builder.
     */
    public static Builder builder(final ZipFile zipFile) {
        return new Builder(zipFile);
    }

    /**
     * Normalizes a raw entry name according to the given rules.
     * <p>
     * Slash normalization (if enabled) is applied first, then case folding (if enabled). The trailing slash of directory entries is never stripped.
     * </p>
     *
     * @param name the raw name.
     * @param caseSensitivity the case rule.
     * @param slashNormalization the slash rule.
     * @return the normalized name.
     */
    static String normalize(final String name, final CaseSensitivity caseSensitivity, final SlashNormalization slashNormalization) {
        String result = name;
        if (slashNormalization == SlashNormalization.NORMALIZE_BACKSLASH_TO_SLASH) {
            result = result.replace('\\', '/');
        }
        if (caseSensitivity == CaseSensitivity.CASE_INSENSITIVE) {
            result = result.toLowerCase(Locale.ROOT);
        }
        return result;
    }

    private final CaseSensitivity caseSensitivity;
    private final SlashNormalization slashNormalization;
    private final List<EntryIdentity> entries;
    private final Map<String, ConflictGroup> groups;

    private ZipEntryConflictIndex(final ZipFile zipFile, final CaseSensitivity caseSensitivity, final SlashNormalization slashNormalization) {
        this.caseSensitivity = caseSensitivity;
        this.slashNormalization = slashNormalization;
        this.entries = new ArrayList<>();
        final Map<String, List<EntryIdentity>> builders = new LinkedHashMap<>();
        int index = 0;
        for (final ZipArchiveEntry entry : Collections.list(zipFile.getEntries())) {
            final EntryIdentity identity = new EntryIdentity(index, entry);
            entries.add(identity);
            final String normalizedName = normalize(entry.getName(), caseSensitivity, slashNormalization);
            builders.computeIfAbsent(normalizedName, key -> new ArrayList<>()).add(identity);
            index++;
        }
        this.groups = new LinkedHashMap<>();
        for (final Map.Entry<String, List<EntryIdentity>> e : builders.entrySet()) {
            groups.put(e.getKey(), new ConflictGroup(e.getKey(), e.getValue()));
        }
    }

    /**
     * Gets the case rule used to build this index.
     *
     * @return the case rule.
     */
    public CaseSensitivity getCaseSensitivity() {
        return caseSensitivity;
    }

    /**
     * Gets the groups that are conflicts, that is the groups containing more than one entry, in the order their first entry appears in the central directory.
     *
     * @return the conflict groups.
     */
    public Collection<ConflictGroup> getConflictGroups() {
        final List<ConflictGroup> result = new ArrayList<>();
        for (final ConflictGroup group : groups.values()) {
            if (group.isConflict()) {
                result.add(group);
            }
        }
        return result;
    }

    /**
     * Gets the group for the given name, normalizing the name with the rules of this index.
     *
     * @param name the name to look up; it is normalized with the rules of this index before comparison.
     * @return the group for the normalized name, or {@code null} if no entry maps to that name.
     */
    public ConflictGroup getGroup(final String name) {
        return groups.get(normalize(name, caseSensitivity, slashNormalization));
    }

    /**
     * Gets all groups in the order their first entry appears in the central directory.
     *
     * @return all groups.
     */
    public Collection<ConflictGroup> getGroups() {
        return Collections.unmodifiableCollection(groups.values());
    }

    /**
     * Gets the slash rule used to build this index.
     *
     * @return the slash rule.
     */
    public SlashNormalization getSlashNormalization() {
        return slashNormalization;
    }

    /**
     * Gets the number of entries in the index, that is the number of entries in the archive's central directory.
     *
     * @return the number of indexed entries.
     */
    public int size() {
        return entries.size();
    }

    @Override
    public String toString() {
        return "ZipEntryConflictIndex[caseSensitivity=" + caseSensitivity + ", slashNormalization=" + slashNormalization + ", groups=" + groups.values() + "]";
    }
}
