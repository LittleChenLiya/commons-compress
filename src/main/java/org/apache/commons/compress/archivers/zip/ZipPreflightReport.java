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
import java.util.List;
import java.util.stream.Collectors;

/**
 * Result of a ZIP preflight check performed by {@link ZipPreflight}.
 * <p>
 * The report carries one {@link ZipPreflightDiagnostic} per finding. Entries are referenced by their original index inside the central directory
 * and findings carry the absolute file offset at which the problem was detected whenever that makes sense.
 * </p>
 * <p>
 * Instances are immutable.
 * </p>
 *
 * @since 1.29.0
 */
public final class ZipPreflightReport {

    private final List<ZipPreflightDiagnostic> diagnostics;

    private final int entryCount;

    ZipPreflightReport(final List<ZipPreflightDiagnostic> diagnostics, final int entryCount) {
        this.diagnostics = Collections.unmodifiableList(new ArrayList<>(diagnostics));
        this.entryCount = entryCount;
    }

    /**
     * Counts the diagnostics with the given severity.
     *
     * @param severity the severity to count.
     * @return the number of diagnostics with the given severity.
     */
    public long count(final ZipPreflightSeverity severity) {
        return diagnostics.stream().filter(diagnostic -> diagnostic.getSeverity() == severity).count();
    }

    /**
     * Gets all diagnostics in the order they were raised.
     *
     * @return an unmodifiable list of diagnostics, never {@code null}.
     */
    public List<ZipPreflightDiagnostic> getDiagnostics() {
        return diagnostics;
    }

    /**
     * Gets the diagnostics with the given severity in the order they were raised.
     *
     * @param severity the severity to filter by.
     * @return the matching diagnostics, never {@code null}.
     */
    public List<ZipPreflightDiagnostic> getDiagnostics(final ZipPreflightSeverity severity) {
        return diagnostics.stream().filter(diagnostic -> diagnostic.getSeverity() == severity).collect(Collectors.toList());
    }

    /**
     * Gets the number of entries found in the central directory, or 0 if the central directory could not be read at all.
     *
     * @return the number of entries inspected.
     */
    public int getEntryCount() {
        return entryCount;
    }

    /**
     * Tests whether any structural errors were found. Archives with errors must not be extracted or copied.
     *
     * @return whether the report contains {@link ZipPreflightSeverity#ERROR} diagnostics.
     */
    public boolean hasErrors() {
        return count(ZipPreflightSeverity.ERROR) > 0;
    }

    /**
     * Tests whether the archive passed the preflight check, that is no structural errors and no unsupported features were found. Warnings and
     * unverifiable entries do not prevent an archive from passing, but see {@link #isFullyVerified()} for a stricter test.
     *
     * @return whether the archive may be extracted or copied.
     */
    public boolean isPassed() {
        return count(ZipPreflightSeverity.ERROR) == 0 && count(ZipPreflightSeverity.UNSUPPORTED) == 0;
    }

    /**
     * Tests whether every entry of the archive could be fully verified: no errors, no unsupported features and no entries the check could not
     * verify.
     *
     * @return whether the archive is completely verified.
     */
    public boolean isFullyVerified() {
        return isPassed() && count(ZipPreflightSeverity.UNVERIFIED) == 0;
    }

    @Override
    public String toString() {
        final StringBuilder builder = new StringBuilder();
        builder.append("ZipPreflightReport[entries=").append(entryCount);
        builder.append(", errors=").append(count(ZipPreflightSeverity.ERROR));
        builder.append(", unsupported=").append(count(ZipPreflightSeverity.UNSUPPORTED));
        builder.append(", unverified=").append(count(ZipPreflightSeverity.UNVERIFIED));
        builder.append(", warnings=").append(count(ZipPreflightSeverity.WARNING));
        builder.append(", passed=").append(isPassed()).append(']');
        diagnostics.forEach(diagnostic -> builder.append(System.lineSeparator()).append("  ").append(diagnostic));
        return builder.toString();
    }
}
