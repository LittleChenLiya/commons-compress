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

/**
 * A single collision or safety problem reported by an {@link ArchiveDestinationChecker} for an in-memory archive manifest.
 *
 * @since 1.29.0
 */
public final class ArchiveDestinationIssue {

    /**
     * The kind of landing problem.
     */
    public enum Kind {

        /** The entry name is {@code null}, empty or only contains name separators. */
        EMPTY_NAME("The entry name is empty"),

        /** The entry name is absolute (leading separator, Windows drive or UNC prefix). */
        ABSOLUTE_NAME("The entry name is absolute"),

        /** Normalized {@code ..} components escape the target directory. */
        TRAVERSAL_OUTSIDE_TARGET("The entry escapes the target directory through parent references"),

        /** A path component is a reserved DOS device name or otherwise illegal on Windows. */
        RESERVED_NAME("The entry contains a reserved Windows file name"),

        /** Two explicit entries land on the same path. */
        DUPLICATE_ENTRY("Two entries land on the same path"),

        /** A file entry and a directory entry land on the exact same path. */
        FILE_AND_DIRECTORY_SAME_NAME("A file and a directory land on the same path"),

        /** Two explicit entries land on paths that differ only in case on a case-insensitive platform. */
        CASE_COLLISION("Two entries collide on a case-insensitive file system"),

        /** A parent component is already occupied by a file or symbolic link. */
        PARENT_OCCUPIED_BY_FILE("A parent path is already occupied by a file or symbolic link"),

        /** A symbolic link target is absolute or escapes the target directory. */
        UNSAFE_LINK_TARGET("The symbolic link target is absolute or escapes the target directory");

        private final String description;

        Kind(final String description) {
            this.description = description;
        }

        /**
         * Gets a human-readable description of this kind.
         *
         * @return The description.
         */
        public String getDescription() {
            return description;
        }
    }

    static ArchiveDestinationIssue of(final Kind kind, final ArchiveDestinationEntry entry, final ArchiveDestinationEntry other,
            final String landingPath) {
        return new ArchiveDestinationIssue(kind, entry, other, landingPath);
    }

    private final ArchiveDestinationEntry entry;

    private final Kind kind;

    private final String landingPath;

    private final ArchiveDestinationEntry otherEntry;

    private ArchiveDestinationIssue(final Kind kind, final ArchiveDestinationEntry entry, final ArchiveDestinationEntry otherEntry,
            final String landingPath) {
        this.kind = kind;
        this.entry = entry;
        this.otherEntry = otherEntry;
        this.landingPath = landingPath;
    }

    /**
     * Gets the entry that triggered this issue.
     *
     * @return The triggering entry.
     */
    public ArchiveDestinationEntry getEntry() {
        return entry;
    }

    /**
     * Gets the issue kind.
     *
     * @return The issue kind.
     */
    public Kind getKind() {
        return kind;
    }

    /**
     * Gets the normalized landing path within the target directory, or {@code null} if it cannot be computed.
     *
     * @return The normalized landing path or {@code null}.
     */
    public String getLandingPath() {
        return landingPath;
    }

    /**
     * Gets the other entry involved in a two-entry collision, or {@code null} for single-entry problems.
     *
     * @return The conflicting entry or {@code null}.
     */
    public ArchiveDestinationEntry getOtherEntry() {
        return otherEntry;
    }

    @Override
    public String toString() {
        final StringBuilder sb = new StringBuilder(kind.description).append(' ');
        if (landingPath != null) {
            sb.append(landingPath);
        } else if (entry != null) {
            sb.append(entry.getName());
        }
        if (otherEntry != null) {
            sb.append(" conflicts with ").append(otherEntry.getName());
        }
        return sb.toString();
    }
}
