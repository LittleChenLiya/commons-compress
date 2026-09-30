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

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Target platform rules used by an {@link ArchiveDestinationChecker} to normalize entry names into path components.
 * <p>
 * The platform only describes the rules of the file system the archive is extracted to; both rule sets are available on every host operating system.
 * </p>
 *
 * @since 1.29.0
 */
public enum ArchiveDestinationPlatform {

    /**
     * POSIX-like rules: {@code /} is the only name separator, backslash is an ordinary character and no file names are reserved.
     */
    POSIX("/") {

        @Override
        boolean isAbsoluteName(final String name) {
            return name.startsWith("/");
        }

        @Override
        boolean isReservedComponent(final String component) {
            return false;
        }

        @Override
        boolean isSeparator(final char ch) {
            return ch == '/';
        }
    },

    /**
     * Windows rules: {@code /} and {@code \\} are name separators, drive and UNC names are absolute, reserved DOS device names are rejected and a colon
     * ({@code :}) cannot appear inside a path component.
     */
    WINDOWS("/", "\\") {

        private final Pattern drivePattern = Pattern.compile("[a-zA-Z]:[\\\\/].*");

        @Override
        boolean isAbsoluteName(final String name) {
            return name.startsWith("/") || name.startsWith("\\") || drivePattern.matcher(name).matches();
        }

        @Override
        boolean isReservedComponent(final String component) {
            final String base = component.split("[.]", 2)[0].toUpperCase(Locale.ROOT);
            return component.indexOf(':') >= 0 || RESERVED_NAMES.contains(base);
        }

        @Override
        boolean isSeparator(final char ch) {
            return ch == '/' || ch == '\\';
        }
    };

    private static final Set<String> RESERVED_NAMES = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "CON", "PRN", "AUX", "NUL", "CONIN$", "CONOUT$",
            "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
            "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9")));

    private final List<String> displaySeparators;

    private final Pattern splitPattern;

    ArchiveDestinationPlatform(final String... displaySeparators) {
        this.displaySeparators = Arrays.asList(displaySeparators);
        final StringBuilder regex = new StringBuilder("[");
        for (final String separator : displaySeparators) {
            regex.append("\\".equals(separator) ? "\\\\" : Pattern.quote(separator));
        }
        this.splitPattern = Pattern.compile(regex.append(']').toString());
    }

    /**
     * Gets the canonical display separator.
     *
     * @return The first display separator.
     */
    String getSeparator() {
        return displaySeparators.get(0);
    }

    /**
     * Joins normalized components with the canonical name separator.
     *
     * @param components The path components.
     * @return The joined display path.
     */
    String join(final List<String> components) {
        return String.join(getSeparator(), components);
    }

    /**
     * Splits a raw name on platform name separators.
     *
     * @param name The raw entry name, guaranteed to be non-{@code null} and not absolute.
     * @return The raw path components, including {@code .}, {@code ..} and empty components for consecutive separators.
     */
    List<String> split(final String name) {
        return Arrays.asList(splitPattern.split(name, -1));
    }

    /**
     * Gets the platform corresponding to the host operating system.
     *
     * @return {@link #WINDOWS} when running on Windows, otherwise {@link #POSIX}.
     */
    public static ArchiveDestinationPlatform current() {
        final String osName = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        return osName.contains("win") ? WINDOWS : POSIX;
    }

    abstract boolean isAbsoluteName(String name);

    abstract boolean isReservedComponent(String component);

    abstract boolean isSeparator(char ch);
}
