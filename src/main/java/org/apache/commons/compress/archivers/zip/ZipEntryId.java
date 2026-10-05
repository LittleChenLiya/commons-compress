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

import java.util.Objects;

/**
 * Immutable identity of a single central directory entry of a ZIP archive.
 * <p>
 * ZIP archives are not required to contain unique entry names: the same name may occur multiple times, names may only differ in character case or in the use of
 * the {@code /} versus {@code \} separator, and a file and a directory may share the same base name. Looking entries up by name alone therefore cannot uniquely
 * identify an entry. A {@link ZipEntryId} solves this by carrying the entry's zero-based position inside the central directory, which is stable for a given
 * archive and uniquely identifies each entry.
 * </p>
 * <p>
 * Use {@link ZipFile#getNameConflictIndex()} together with {@link ZipFile#getEntry(ZipEntryId)} and {@link ZipFile#getInputStream(ZipEntryId)} to resolve name
 * conflicts and to read the exact entry you are interested in.
 * </p>
 *
 * @since 1.29.0
 */
public final class ZipEntryId {

    /**
     * Zero-based position of the entry inside the archive's central directory.
     */
    private final int centralDirectoryIndex;

    /**
     * The raw entry name as decoded from the central directory, including a trailing {@code '/'} for directory entries.
     */
    private final String name;

    /**
     * Whether the identified entry is a directory.
     */
    private final boolean directory;

    /**
     * Constructs a new instance.
     *
     * @param centralDirectoryIndex zero-based position of the entry inside the central directory.
     * @param name                  the raw entry name as decoded from the central directory.
     * @param directory             whether the entry is a directory.
     */
    ZipEntryId(final int centralDirectoryIndex, final String name, final boolean directory) {
        if (centralDirectoryIndex < 0) {
            throw new IllegalArgumentException("Central directory index must not be negative: " + centralDirectoryIndex);
        }
        this.centralDirectoryIndex = centralDirectoryIndex;
        this.name = Objects.requireNonNull(name, "name");
        this.directory = directory;
    }

    @Override
    public boolean equals(final Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof ZipEntryId)) {
            return false;
        }
        final ZipEntryId other = (ZipEntryId) obj;
        return centralDirectoryIndex == other.centralDirectoryIndex && directory == other.directory && Objects.equals(name, other.name);
    }

    /**
     * Gets the zero-based position of the entry inside the archive's central directory.
     *
     * @return the zero-based central directory position.
     */
    public int getCentralDirectoryIndex() {
        return centralDirectoryIndex;
    }

    /**
     * Gets the raw entry name as decoded from the central directory.
     * <p>
     * Directory names end with {@code '/'}.
     * </p>
     *
     * @return the raw entry name, never {@code null}.
     */
    public String getName() {
        return name;
    }

    @Override
    public int hashCode() {
        return Objects.hash(centralDirectoryIndex, name, directory);
    }

    /**
     * Tests whether the identified entry is a directory.
     *
     * @return {@code true} if the entry's name ends with {@code '/'}.
     */
    public boolean isDirectory() {
        return directory;
    }

    @Override
    public String toString() {
        return "ZipEntryId[centralDirectoryIndex=" + centralDirectoryIndex + ", name='" + name + "', directory=" + directory + ']';
    }
}
