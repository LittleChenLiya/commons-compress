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

import java.util.Locale;
import java.util.Objects;

/**
 * Rules used by {@link ZipNameConflictIndex} to normalize raw central directory entry names before comparing them for conflicts.
 * <p>
 * A ZIP archive stores names as opaque byte sequences decoded with the archive's charset. The ZIP specification (APPNOTE.TM, section 4.4.17) mandates the
 * forward slash {@code '/'} as the directory separator, but archives created on or for Windows frequently contain the backslash {@code '\\'} instead. File
 * systems also differ in whether names are compared case-sensitively. A caller that wants to know which entries can collide on the file system it extracts to
 * therefore has to state both rules explicitly.
 * </p>
 * <p>
 * The normalization applied to a raw name by {@link #normalize(String)} is exactly the following, in order:
 * </p>
 * <ol>
 * <li>If {@code convertBackslashes} is {@code true}, every backslash character {@code '\\'} (U+005C) is replaced with a forward slash {@code '/'} (U+002F).
 * If it is {@code false}, backslashes are left untouched.</li>
 * <li>If {@code caseInsensitive} is {@code true}, the result of step 1 is mapped to lower case with {@link String#toLowerCase(Locale)} using
 * {@link Locale#ROOT} (locale-independent, so for example {@code "A.TXT"} becomes {@code "a.txt"}, without the Turkish default case mapping surprises). If it
 * is {@code false}, the case is left untouched.</li>
 * </ol>
 * <p>
 * Nothing else is changed: names are never trimmed, leading/trailing or duplicate separators are never collapsed, {@code "."} and {@code ".."} segments are
 * kept, Unicode is not normalized (no NFC/NFKC), and drive letters are not stripped.
 * </p>
 * <p>
 * The trailing directory separator is preserved on purpose: the file {@code "a.txt"} and the directory {@code "a.txt/"} normalize to different keys
 * ({@code "a.txt"} versus {@code "a.txt/"}) and therefore stay distinguishable. Likewise a name ending with a backslash ({@code "a.txt\"}) only collides with
 * {@code "a.txt/"} when {@code convertBackslashes} is {@code true}.
 * </p>
 * <p>
 * Three ready-made rules are provided:
 * </p>
 * <ul>
 * <li>{@link #EXACT} &mdash; raw comparison, reports true duplicate names only.</li>
 * <li>{@link #CASE_INSENSITIVE} &mdash; case-insensitive, but separators are kept apart.</li>
 * <li>{@link #WINDOWS_LIKE} &mdash; backslashes treated as separators and case-insensitive, matching typical Windows file system behavior.</li>
 * </ul>
 *
 * @since 1.29.0
 */
public final class ZipNameNormalization {

    /**
     * Exact, raw-name comparison: no separator conversion and no case folding. Two entries collide only if their decoded names are character-for-character
     * identical.
     */
    public static final ZipNameNormalization EXACT = new ZipNameNormalization(false, false);

    /**
     * Case-insensitive comparison without separator conversion: {@code "a.txt"} collides with {@code "A.TXT"}, but a name using backslashes does not collide
     * with its forward-slash counterpart.
     */
    public static final ZipNameNormalization CASE_INSENSITIVE = new ZipNameNormalization(false, true);

    /**
     * Windows-like comparison: backslashes are treated as forward slashes and case is folded (for example {@code "dir\A.TXT"} collides with
     * {@code "dir/a.txt"}).
     */
    public static final ZipNameNormalization WINDOWS_LIKE = new ZipNameNormalization(true, true);

    /**
     * Whether {@code '\'} is replaced with {@code '/'} before comparison.
     */
    private final boolean convertBackslashes;

    /**
     * Whether names are compared case-insensitively (folded to lower case with the root locale).
     */
    private final boolean caseInsensitive;

    /**
     * Constructs a custom set of rules.
     * <p>
     * Prefer {@link #EXACT}, {@link #CASE_INSENSITIVE} or {@link #WINDOWS_LIKE} unless you need a different combination (for example a case-sensitive
     * comparison that treats both separators as equal).
     * </p>
     *
     * @param convertBackslashes whether to replace {@code '\'} with {@code '/'} before comparison.
     * @param caseInsensitive    whether to fold names to lower case (root locale) before comparison.
     * @return a (possibly shared) {@link ZipNameNormalization} instance.
     */
    public static ZipNameNormalization of(final boolean convertBackslashes, final boolean caseInsensitive) {
        if (!convertBackslashes && !caseInsensitive) {
            return EXACT;
        }
        if (convertBackslashes && caseInsensitive) {
            return WINDOWS_LIKE;
        }
        if (caseInsensitive) {
            return CASE_INSENSITIVE;
        }
        // convertBackslashes only
        return new ZipNameNormalization(true, false);
    }

    private ZipNameNormalization(final boolean convertBackslashes, final boolean caseInsensitive) {
        this.convertBackslashes = convertBackslashes;
        this.caseInsensitive = caseInsensitive;
    }

    @Override
    public boolean equals(final Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof ZipNameNormalization)) {
            return false;
        }
        final ZipNameNormalization other = (ZipNameNormalization) obj;
        return convertBackslashes == other.convertBackslashes && caseInsensitive == other.caseInsensitive;
    }

    /**
     * Gets the rules describing typical Windows file system behavior: separators {@code '\'} and {@code '/'} compare equal and names are
     * case-insensitive.
     *
     * @return {@link #WINDOWS_LIKE}.
     */
    public static ZipNameNormalization getWindowsLike() {
        return WINDOWS_LIKE;
    }

    /**
     * Gets the rules performing no normalization at all (raw names).
     *
     * @return {@link #EXACT}.
     */
    public static ZipNameNormalization getExact() {
        return EXACT;
    }

    /**
     * Gets the rules folding case but keeping {@code '\'} and {@code '/'} apart.
     *
     * @return {@link #CASE_INSENSITIVE}.
     */
    public static ZipNameNormalization getCaseInsensitive() {
        return CASE_INSENSITIVE;
    }

    @Override
    public int hashCode() {
        return Objects.hash(convertBackslashes, caseInsensitive);
    }

    /**
     * Tests whether these rules fold names to lower case.
     *
     * @return {@code true} if {@link #normalize(String)} maps names to lower case using {@link Locale#ROOT}.
     */
    public boolean isCaseInsensitive() {
        return caseInsensitive;
    }

    /**
     * Tests whether these rules treat {@code '\'} as {@code '/'}.
     *
     * @return {@code true} if {@link #normalize(String)} replaces backslashes with forward slashes.
     */
    public boolean isConvertingBackslashes() {
        return convertBackslashes;
    }

    /**
     * Normalizes a raw central directory entry name according to these rules.
     * <p>
     * The transformation is described in detail in this class's Javadoc: optionally replacing {@code '\'} with {@code '/'} and optionally folding to lower
     * case with {@link Locale#ROOT}. The trailing directory separator is preserved, so a file and a directory with the same base name never collide.
     * </p>
     *
     * @param name the raw entry name, must not be {@code null}.
     * @return the normalized name; equal to the input when no rule changes anything.
     * @throws NullPointerException if {@code name} is {@code null}.
     */
    public String normalize(final String name) {
        Objects.requireNonNull(name, "name");
        String result = name;
        if (convertBackslashes) {
            result = result.replace('\\', '/');
        }
        if (caseInsensitive) {
            result = result.toLowerCase(Locale.ROOT);
        }
        return result;
    }

    @Override
    public String toString() {
        return "ZipNameNormalization[convertBackslashes=" + convertBackslashes + ", caseInsensitive=" + caseInsensitive + ']';
    }
}
