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

import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;

/**
 * Reports a group of central directory entries whose names conflict.
 * <p>
 * A conflict is either a <em>raw</em> conflict (two or more entries with exactly the same decoded name, reported by
 * {@link ZipNameConflictIndex#rawNameCollisions()}) or a <em>normalized</em> conflict (two or more entries whose names become equal after the caller-supplied
 * {@link ZipNameNormalization} has been applied, reported by {@link ZipNameConflictIndex#normalizedNameCollisions()}). Because the trailing directory separator
 * is preserved by the normalization, a file named {@code "a.txt"} and a directory named {@code "a.txt/"} never share a group even though they have the same
 * base name.
 * </p>
 * <p>
 * The member {@link ZipEntryId}s are returned in central directory order.
 * </p>
 *
 * @since 1.29.0
 */
public final class ZipNameCollision implements Iterable<ZipEntryId> {

    /**
     * The key the members share: the raw decoded name for a raw conflict, or the normalized name for a normalized conflict.
     */
    private final String key;

    /**
     * The rules in effect for this group, {@link ZipNameNormalization#EXACT} for raw conflicts.
     */
    private final ZipNameNormalization normalization;

    /**
     * Member identities in central directory order.
     */
    private final List<ZipEntryId> ids;

    /**
     * Constructs a new instance.
     *
     * @param key           the shared name (raw for raw conflicts, normalized otherwise).
     * @param normalization the rules in effect; {@link ZipNameNormalization#EXACT} for raw conflicts.
     * @param ids           the member identities in central directory order, must contain at least two elements.
     */
    ZipNameCollision(final String key, final ZipNameNormalization normalization, final List<ZipEntryId> ids) {
        this.key = Objects.requireNonNull(key, "key");
        this.normalization = Objects.requireNonNull(normalization, "normalization");
        this.ids = Collections.unmodifiableList(Objects.requireNonNull(ids, "ids"));
        if (ids.size() < 2) {
            throw new IllegalArgumentException("A name collision needs at least two entries: " + ids.size());
        }
    }

    /**
     * Gets the shared name key of this conflict group.
     *
     * @return the raw decoded name for raw conflicts, or the normalized name for normalized conflicts; never {@code null}.
     */
    public String getKey() {
        return key;
    }

    /**
     * Gets the member entries' identities in central directory order.
     *
     * @return an unmodifiable list with at least two {@link ZipEntryId}s.
     */
    public List<ZipEntryId> getEntryIds() {
        return ids;
    }

    /**
     * Gets the normalization rules in effect for this conflict.
     *
     * @return the rules; {@link ZipNameNormalization#EXACT} for a raw-name conflict.
     */
    public ZipNameNormalization getNormalization() {
        return normalization;
    }

    /**
     * Gets the number of member entries in this conflict group.
     *
     * @return a value of {@code 2} or greater.
     */
    public int size() {
        return ids.size();
    }

    /**
     * Tests whether this is a raw (exact-name) conflict.
     *
     * @return {@code true} when the members share exactly the same decoded name.
     */
    public boolean isRaw() {
        return ZipNameNormalization.EXACT.equals(normalization);
    }

    @Override
    public Iterator<ZipEntryId> iterator() {
        return ids.iterator();
    }

    @Override
    public String toString() {
        return "ZipNameCollision[key='" + key + "', normalization=" + normalization + ", entries=" + ids + ']';
    }
}
