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

/**
 * A single problem found by {@link ArchivePlacementChecker}. When two entries are involved (for example a case
 * collision), both entry names are exposed through {@link #getEntryName()} and {@link #getRelatedName()}.
 *
 * @since 1.29.0
 */
public final class PlacementIssue {

    /** The category of a placement problem. */
    public enum Type {
        /** The entry name is empty or normalizes to nothing. */
        EMPTY_PATH,
        /** The entry name is an absolute or rooted path. */
        ABSOLUTE_PATH,
        /** The entry path escapes the target directory through dot-dot components. */
        OUTSIDE_TARGET,
        /** A path component is a reserved device name on the target platform. */
        RESERVED_NAME,
        /** The same normalized path appears more than once with the same kind. */
        DUPLICATE_ENTRY,
        /** The same normalized path is used by both a directory and a non-directory entry. */
        FILE_DIRECTORY_CONFLICT,
        /** Two entries only differ in letter case on a case-insensitive target platform. */
        CASE_COLLISION,
        /** A parent path component of the entry is occupied by a regular file or link. */
        PARENT_OCCUPIED_BY_FILE,
        /** A link entry has an absolute link target. */
        LINK_TARGET_ABSOLUTE,
        /** A link entry has a target that resolves outside the target directory. */
        LINK_TARGET_OUTSIDE
    }

    private final Type type;
    private final String entryName;
    private final String relatedName;
    private final String message;

    PlacementIssue(final Type type, final String entryName, final String relatedName, final String message) {
        this.type = type;
        this.entryName = entryName;
        this.relatedName = relatedName;
        this.message = message;
    }

    /**
     * Gets the name of the entry this issue is reported for.
     *
     * @return the entry name.
     */
    public String getEntryName() {
        return entryName;
    }

    /**
     * Gets the human readable description of this issue.
     *
     * @return the message.
     */
    public String getMessage() {
        return message;
    }

    /**
     * Gets the name of the second entry involved in this issue, if any.
     *
     * @return the related entry name or null.
     */
    public String getRelatedName() {
        return relatedName;
    }

    /**
     * Gets the issue type.
     *
     * @return the type.
     */
    public Type getType() {
        return type;
    }

    @Override
    public String toString() {
        return type + ": " + message;
    }
}
