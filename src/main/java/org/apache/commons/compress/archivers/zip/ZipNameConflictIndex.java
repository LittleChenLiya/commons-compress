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
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Explicit index of the name conflicts inside a ZIP archive's central directory.
 * <p>
 * ZIP entries are not required to have unique names. The legacy name based lookup methods ({@link ZipFile#getEntry(String)} and
 * {@link ZipFile#getEntries(String)}) keep their documented behavior of returning the first matching central directory entry; this index instead reports
 * <em>all</em> entries affected by a name collision and identifies each entry by its stable {@link ZipEntryId} (its zero-based central directory position).
 * </p>
 * <p>
 * Two kinds of conflicts are reported:
 * </p>
 * <ul>
 * <li><strong>Raw conflicts</strong> ({@link #rawNameCollisions()}): groups of entries whose decoded names are character-for-character identical.</li>
 * <li><strong>Normalized conflicts</strong> ({@link #normalizedNameCollisions()}): groups of entries whose names become equal after applying a caller supplied
 * {@link ZipNameNormalization} (slash conversion and/or case folding). The trailing directory separator is preserved by the normalization, so a file and a
 * directory with the same base name stay in separate groups.</li>
 * </ul>
 * <p>
 * Instances are created by {@link ZipFile#getNameConflictIndex()} and {@link ZipFile#getNameConflictIndex(ZipNameNormalization)}. Building an index only walks
 * the already parsed central directory metadata; it never decompresses entry content, so even an archive opened with
 * {@link ZipFile.Builder#setIgnoreLocalFileHeader(boolean) ignore local file headers} can be indexed.
 * </p>
 *
 * @since 1.29.0
 */
public final class ZipNameConflictIndex {

    /**
     * Builds the index for the entries of a {@link ZipFile}, in central directory order.
     *
     * @param entries       all central directory entries in central directory order.
     * @param normalization the rules used to fold names when looking up normalized conflicts.
     * @return a new index.
     */
    static ZipNameConflictIndex create(final List<? extends ZipArchiveEntry> entries, final ZipNameNormalization normalization) {
        Objects.requireNonNull(entries, "entries");
        Objects.requireNonNull(normalization, "normalization");
        final Map<String, List<ZipEntryId>> rawGroups = new LinkedHashMap<>();
        final Map<String, List<ZipEntryId>> normalizedGroups = new LinkedHashMap<>();
        int index = 0;
        for (final ZipArchiveEntry entry : entries) {
            final String rawName = entry.getName();
            final ZipEntryId id = new ZipEntryId(index, rawName, entry.isDirectory());
            rawGroups.computeIfAbsent(rawName, k -> new ArrayList<>()).add(id);
            normalizedGroups.computeIfAbsent(normalization.normalize(rawName), k -> new ArrayList<>()).add(id);
            index++;
        }
        return new ZipNameConflictIndex(normalization, entries.size(), freeze(rawGroups), freeze(normalizedGroups));
    }

    private static Map<String, List<ZipEntryId>> freeze(final Map<String, List<ZipEntryId>> groups) {
        final Map<String, List<ZipEntryId>> copy = new LinkedHashMap<>();
        groups.forEach((k, v) -> copy.put(k, Collections.unmodifiableList(new ArrayList<>(v))));
        return Collections.unmodifiableMap(copy);
    }

    private static int groupCount(final Map<String, List<ZipEntryId>> groups) {
        int count = 0;
        for (final List<ZipEntryId> ids : groups.values()) {
            if (ids.size() > 1) {
                count++;
            }
        }
        return count;
    }

    /**
     * Rules used when building the normalized-name view.
     */
    private final ZipNameNormalization normalization;

    /**
     * Total number of indexed central directory entries.
     */
    private final int entryCount;

    /**
     * All groups keyed by the exact decoded name (including singleton groups).
     */
    private final Map<String, List<ZipEntryId>> rawGroups;

    /**
     * All groups keyed by the normalized name (including singleton groups).
     */
    private final Map<String, List<ZipEntryId>> normalizedGroups;

    private ZipNameConflictIndex(final ZipNameNormalization normalization, final int entryCount,
            final Map<String, List<ZipEntryId>> rawGroups, final Map<String, List<ZipEntryId>> normalizedGroups) {
        this.normalization = normalization;
        this.entryCount = entryCount;
        this.rawGroups = rawGroups;
        this.normalizedGroups = normalizedGroups;
    }

    /**
     * Gets the number of central directory entries covered by this index.
     *
     * @return the total number of entries, including entries that do not collide with anything.
     */
    public int getEntryCount() {
        return entryCount;
    }

    /**
     * Gets the identities of all entries that share the given name without any normalization applied.
     *
     * @param rawName the exact decoded entry name.
     * @return the matching identities in central directory order, or an empty list if no entry has that name; never {@code null}.
     */
    public List<ZipEntryId> getEntryIds(final String rawName) {
        Objects.requireNonNull(rawName, "rawName");
        return rawGroups.getOrDefault(rawName, Collections.emptyList());
    }

    /**
     * Gets the identities of all entries whose name normalizes to the given lookup name.
     * <p>
     * The given lookup name is normalized with the index's {@link ZipNameNormalization} first, so callers may pass either a raw name or an already normalized
     * one. For example, with {@link ZipNameNormalization#WINDOWS_LIKE}, {@code getEntryIdsNormalized("A.TXT")} returns the same identities as
     * {@code getEntryIdsNormalized("a.txt")}.
     * </p>
     *
     * @param name the raw or normalized lookup name.
     * @return the matching identities in central directory order, or an empty list if nothing matches; never {@code null}.
     */
    public List<ZipEntryId> getEntryIdsNormalized(final String name) {
        Objects.requireNonNull(name, "name");
        return normalizedGroups.getOrDefault(normalization.normalize(name), Collections.emptyList());
    }

    /**
     * Gets the normalization rules in effect for the normalized view of this index.
     *
     * @return the rules passed when this index was created; never {@code null}.
     */
    public ZipNameNormalization getNormalization() {
        return normalization;
    }

    /**
     * Gets the normalized-name conflict group for the given lookup name.
     *
     * @param name a raw or normalized name; it is normalized with this index's rules before lookup.
     * @return the conflict group, or {@code null} if fewer than two entries normalize to the name.
     */
    public ZipNameCollision getNormalizedNameCollision(final String name) {
        Objects.requireNonNull(name, "name");
        return toCollision(normalization.normalize(name), normalizedGroups, normalization);
    }

    /**
     * Gets the raw-name conflict group for the given exact name.
     *
     * @param rawName the exact decoded entry name.
     * @return the conflict group, or {@code null} if fewer than two entries carry that exact name.
     */
    public ZipNameCollision getRawNameCollision(final String rawName) {
        return toCollision(rawName, rawGroups, ZipNameNormalization.EXACT);
    }

    /**
     * Gets all normalized-name conflict groups.
     * <p>
     * The iteration order follows the first central directory occurrence of each conflicting normalized name.
     * </p>
     *
     * @return an unmodifiable map from normalized name to conflict group; possibly empty, never {@code null}.
     */
    public Map<String, ZipNameCollision> normalizedNameCollisions() {
        return collectCollisions(normalizedGroups, normalization);
    }

    /**
     * Gets all raw (exact-name) conflict groups.
     * <p>
     * The iteration order follows the first central directory occurrence of each conflicting raw name.
     * </p>
     *
     * @return an unmodifiable map from raw name to conflict group; possibly empty, never {@code null}.
     */
    public Map<String, ZipNameCollision> rawNameCollisions() {
        return collectCollisions(rawGroups, ZipNameNormalization.EXACT);
    }

    private static Map<String, ZipNameCollision> collectCollisions(final Map<String, List<ZipEntryId>> groups,
            final ZipNameNormalization normalization) {
        final Map<String, ZipNameCollision> collisions = new LinkedHashMap<>();
        groups.forEach((key, ids) -> {
            if (ids.size() > 1) {
                collisions.put(key, new ZipNameCollision(key, normalization, ids));
            }
        });
        return Collections.unmodifiableMap(collisions);
    }

    private static ZipNameCollision toCollision(final String key, final Map<String, List<ZipEntryId>> groups,
            final ZipNameNormalization normalization) {
        final List<ZipEntryId> ids = groups.get(key);
        if (ids == null || ids.size() < 2) {
            return null;
        }
        return new ZipNameCollision(key, normalization, ids);
    }

    @Override
    public String toString() {
        return "ZipNameConflictIndex[entries=" + entryCount + ", normalization=" + normalization + ", rawCollisions="
                + groupCount(rawGroups) + ", normalizedCollisions=" + groupCount(normalizedGroups) + ']';
    }
}
