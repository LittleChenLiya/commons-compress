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

import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Describes the path rules of the platform an archive would be extracted on, so that
 * {@link ArchivePlacementChecker} can validate entry names without touching a real file system.
 *
 * @since 1.29.0
 */
public enum TargetPlatform {

    /** POSIX style rules: case sensitive, only slash separates path components. */
    POSIX(true, false),

    /** Windows style rules: case insensitive, slash and backslash separate components, reserved device names. */
    WINDOWS(false, true);

    private static final Set<String> WINDOWS_RESERVED_NAMES = new HashSet<>(Arrays.asList("CON", "PRN", "AUX", "NUL", "COM1", "COM2", "COM3",
        "COM4", "COM5", "COM6", "COM7", "COM8", "COM9", "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9"));

    private final boolean caseSensitive;
    private final boolean windows;

    TargetPlatform(final boolean caseSensitive, final boolean windows) {
        this.caseSensitive = caseSensitive;
        this.windows = windows;
    }

    /**
     * Folds a path component or path to the form used for comparisons on this platform.
     *
     * @param path the path or component to fold.
     * @return the comparison form of {@code path}.
     */
    public String foldCase(final String path) {
        return caseSensitive ? path : path.toLowerCase(Locale.ROOT);
    }

    /**
     * Tests whether the given entry name denotes an absolute or rooted path on this platform.
     *
     * @param name the entry name to test.
     * @return true if the name is absolute (POSIX root, Windows drive or UNC path, or rooted relative).
     */
    public boolean isAbsolute(final String name) {
        if (name == null || name.isEmpty()) {
            return false;
        }
        if (isSeparator(name.charAt(0))) {
            return true;
        }
        return windows && name.length() >= 2 && name.charAt(1) == ':' && isDriveLetter(name.charAt(0));
    }

    /**
     * Tests whether this platform's file names are case sensitive.
     *
     * @return true if file names are case sensitive.
     */
    public boolean isCaseSensitive() {
        return caseSensitive;
    }

    /**
     * Tests whether the given character separates path components on this platform.
     *
     * @param ch the character to test.
     * @return true if {@code ch} is a path separator.
     */
    public boolean isSeparator(final char ch) {
        return ch == '/' || windows && ch == '\\';
    }

    /**
     * Tests whether a single path component is a reserved device name on this platform.
     *
     * @param component a single path component, must not contain separators.
     * @return true if the component is reserved (for example {@code CON} or {@code NUL.txt} on Windows).
     */
    public boolean isReservedName(final String component) {
        if (!windows || component == null) {
            return false;
        }
        final int dot = component.indexOf('.');
        final String base = (dot < 0 ? component : component.substring(0, dot)).toUpperCase(Locale.ROOT);
        return WINDOWS_RESERVED_NAMES.contains(base);
    }

    private static boolean isDriveLetter(final char ch) {
        return ch >= 'a' && ch <= 'z' || ch >= 'A' && ch <= 'Z';
    }
}
