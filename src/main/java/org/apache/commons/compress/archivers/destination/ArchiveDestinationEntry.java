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
package org.apache.commons.compress.archivers.destination;

import java.util.Objects;

import org.apache.commons.compress.archivers.ArchiveEntry;

/**
 * Describes a single archive entry as metadata for an {@link ArchiveDestinationChecker}.
 * <p>
 * Instances only carry the information needed to decide where an entry would land in a target directory, no file system access is involved.
 * </p>
 *
 * @since 1.29.0
 */
public final class ArchiveDestinationEntry {

    /**
     * The kind of file system node an archive entry creates when it is extracted.
     */
    public enum Type {

        /** A regular file. */
        FILE,

        /** A directory. */
        DIRECTORY,

        /** A symbolic link with a {@link ArchiveDestinationEntry#getLinkTarget() link target}. */
        LINK
    }

    /**
     * Creates a directory entry.
     *
     * @param name The raw entry name as stored in the archive.
     * @return A new directory entry.
     */
    public static ArchiveDestinationEntry directory(final String name) {
        return new ArchiveDestinationEntry(name, Type.DIRECTORY, null);
    }

    /**
     * Creates a regular file entry.
     *
     * @param name The raw entry name as stored in the archive.
     * @return A new regular file entry.
     */
    public static ArchiveDestinationEntry file(final String name) {
        return new ArchiveDestinationEntry(name, Type.FILE, null);
    }

    /**
     * Adapts the name and directory flag of an {@link ArchiveEntry}.
     * <p>
     * Format-specific symbolic links are not part of the generic {@link ArchiveEntry} interface, use {@link #symbolicLink(String, String)} for those.
     * </p>
     *
     * @param entry The archive entry to adapt.
     * @return A new regular file or directory entry.
     */
    public static ArchiveDestinationEntry from(final ArchiveEntry entry) {
        Objects.requireNonNull(entry, "entry");
        return entry.isDirectory() ? directory(entry.getName()) : file(entry.getName());
    }

    /**
     * Creates a symbolic link entry.
     *
     * @param name The raw entry name as stored in the archive.
     * @param target The raw symbolic link target as stored in the archive.
     * @return A new symbolic link entry.
     */
    public static ArchiveDestinationEntry symbolicLink(final String name, final String target) {
        return new ArchiveDestinationEntry(name, Type.LINK, target);
    }

    private final String linkTarget;

    private final String name;

    private final Type type;

    private ArchiveDestinationEntry(final String name, final Type type, final String linkTarget) {
        this.name = name;
        this.type = Objects.requireNonNull(type, "type");
        this.linkTarget = linkTarget;
    }

    /**
     * Gets the raw link target, only defined for {@link Type#LINK} entries.
     *
     * @return The raw symbolic link target, may be {@code null}.
     */
    public String getLinkTarget() {
        return linkTarget;
    }

    /**
     * Gets the raw entry name as stored in the archive.
     *
     * @return The raw entry name.
     */
    public String getName() {
        return name;
    }

    /**
     * Gets the node type this entry creates when extracted.
     *
     * @return The entry type.
     */
    public Type getType() {
        return type;
    }

    /**
     * Tests whether this entry is a directory.
     *
     * @return {@code true} if this entry is a directory.
     */
    public boolean isDirectory() {
        return type == Type.DIRECTORY;
    }

    /**
     * Tests whether this entry is a symbolic link.
     *
     * @return {@code true} if this entry is a symbolic link.
     */
    public boolean isSymbolicLink() {
        return type == Type.LINK;
    }

    @Override
    public String toString() {
        return type + ":" + name + (type == Type.LINK ? " -> " + linkTarget : "");
    }
}
