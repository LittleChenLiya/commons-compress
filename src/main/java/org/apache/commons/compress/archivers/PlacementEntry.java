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

package org.apache.commons.compress.archivers;

import java.util.Objects;

/**
 * Immutable metadata describing a single archive entry for {@link ArchivePlacementChecker}. Only the information
 * needed to reason about where the entry would land on disk is captured; no file is ever created.
 *
 * @since 1.29.0
 */
public final class PlacementEntry {

    /** The kind of file system node an entry would create. */
    public enum Kind {
        /** A regular file. */
        FILE,
        /** A directory. */
        DIRECTORY,
        /** A symbolic link. */
        SYMBOLIC_LINK,
        /** A hard link. */
        HARD_LINK,
        /** Any other node type, treated like a regular file for placement purposes. */
        OTHER
    }

    /**
     * Creates metadata for a directory entry.
     *
     * @param name the entry name, may use a trailing slash.
     * @return the entry metadata.
     */
    public static PlacementEntry directory(final String name) {
        return of(name, Kind.DIRECTORY);
    }

    /**
     * Creates metadata for a regular file entry.
     *
     * @param name the entry name.
     * @return the entry metadata.
     */
    public static PlacementEntry file(final String name) {
        return of(name, Kind.FILE);
    }

    /**
     * Creates metadata for a hard link entry.
     *
     * @param name the entry name.
     * @param linkName the link target.
     * @return the entry metadata.
     */
    public static PlacementEntry hardLink(final String name, final String linkName) {
        return new PlacementEntry(name, Kind.HARD_LINK, linkName);
    }

    /**
     * Creates metadata for an entry of the given kind without a link target.
     *
     * @param name the entry name.
     * @param kind the entry kind.
     * @return the entry metadata.
     */
    public static PlacementEntry of(final String name, final Kind kind) {
        return new PlacementEntry(name, kind, null);
    }

    /**
     * Creates metadata for a symbolic link entry.
     *
     * @param name the entry name.
     * @param linkName the link target.
     * @return the entry metadata.
     */
    public static PlacementEntry symbolicLink(final String name, final String linkName) {
        return new PlacementEntry(name, Kind.SYMBOLIC_LINK, linkName);
    }

    private final String name;
    private final Kind kind;
    private final String linkName;

    private PlacementEntry(final String name, final Kind kind, final String linkName) {
        this.name = name;
        this.kind = Objects.requireNonNull(kind, "kind");
        this.linkName = linkName;
    }

    /**
     * Gets the entry kind.
     *
     * @return the kind.
     */
    public Kind getKind() {
        return kind;
    }

    /**
     * Gets the link target, if any.
     *
     * @return the link target or null.
     */
    public String getLinkName() {
        return linkName;
    }

    /**
     * Gets the entry name as stored in the archive.
     *
     * @return the entry name.
     */
    public String getName() {
        return name;
    }

    /**
     * Tests whether this entry would create a directory.
     *
     * @return true for directory entries.
     */
    public boolean isDirectory() {
        return kind == Kind.DIRECTORY;
    }

    /**
     * Tests whether this entry is a symbolic or hard link.
     *
     * @return true for link entries.
     */
    public boolean isLink() {
        return kind == Kind.SYMBOLIC_LINK || kind == Kind.HARD_LINK;
    }

    @Override
    public String toString() {
        return kind + " " + name;
    }
}
