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

/**
 * The result of a ZIP archive pre-flight check performed by {@link ZipArchivePrecheck}.
 * <p>
 * A report carries the number of entries that were inspected plus one {@link Issue} per finding. Each issue is classified by {@link Severity}:
 * </p>
 * <ul>
 * <li>{@link Severity#ERROR} - a structural problem (truncation, overlapping entry data, forged lengths, CRC mismatch, ...). The archive must not be
 * trusted.</li>
 * <li>{@link Severity#UNSUPPORTED} - a feature the precheck cannot verify (encryption, multi-volume archives, unsupported compression methods). The
 * archive is explicitly <em>not</em> reported as passed.</li>
 * <li>{@link Severity#WARNING} - something suspicious but not necessarily corrupt, for example a duplicate entry name.</li>
 * </ul>
 *
 * @since 1.29.0
 */
public final class ZipPrecheckReport {

    /**
     * Classifies a single precheck finding.
     */
    public enum Severity {

        /** A structural error; the archive must not be trusted. */
        ERROR,

        /** A feature that is not verifiable by the precheck (encryption, multi-volume, unsupported compression method). */
        UNSUPPORTED,

        /** A suspicious but not necessarily corrupt condition. */
        WARNING
    }

    /**
     * A single precheck finding, tied to an entry index of the central directory and a file offset whenever applicable.
     */
    public static final class Issue {

        private final Severity severity;
        private final int entryIndex;
        private final long offset;
        private final String message;

        Issue(final Severity severity, final int entryIndex, final long offset, final String message) {
            this.severity = severity;
            this.entryIndex = entryIndex;
            this.offset = offset;
            this.message = message;
        }

        /**
         * Gets the index of the entry inside the central directory this issue belongs to, or -1 if it is an archive level issue.
         *
         * @return the central directory entry index or -1.
         */
        public int getEntryIndex() {
            return entryIndex;
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
         * Gets the file offset related to this issue, or -1 if no single offset applies.
         *
         * @return the file offset or -1.
         */
        public long getOffset() {
            return offset;
        }

        /**
         * Gets the severity of this issue.
         *
         * @return the severity.
         */
        public Severity getSeverity() {
            return severity;
        }

        @Override
        public String toString() {
            final StringBuilder builder = new StringBuilder("[").append(severity).append("] ");
            builder.append(entryIndex >= 0 ? "entry #" + entryIndex : "archive");
            if (offset >= 0) {
                builder.append(" @offset ").append(offset);
            }
            return builder.append(": ").append(message).toString();
        }
    }

    private final List<Issue> issues = new ArrayList<>();

    private int entryCount;

    /**
     * Constructs an empty report.
     */
    public ZipPrecheckReport() {
    }

    void addIssue(final Severity severity, final int entryIndex, final long offset, final String message) {
        issues.add(new Issue(severity, entryIndex, offset, message));
    }

    /**
     * Gets the number of central directory entries that were inspected.
     *
     * @return the number of inspected entries.
     */
    public int getEntryCount() {
        return entryCount;
    }

    /**
     * Gets all issues found, in detection order.
     *
     * @return an unmodifiable list of issues, never null.
     */
    public List<Issue> getIssues() {
        return Collections.unmodifiableList(issues);
    }

    /**
     * Gets all issues of the given severity, in detection order.
     *
     * @param severity the severity to filter by.
     * @return a list of matching issues, never null.
     */
    public List<Issue> getIssues(final Severity severity) {
        final List<Issue> matches = new ArrayList<>();
        for (final Issue issue : issues) {
            if (issue.getSeverity() == severity) {
                matches.add(issue);
            }
        }
        return matches;
    }

    /**
     * Tests whether any structural {@link Severity#ERROR} was found.
     *
     * @return true if at least one error was found.
     */
    public boolean hasErrors() {
        return !getIssues(Severity.ERROR).isEmpty();
    }

    /**
     * Tests whether any {@link Severity#UNSUPPORTED} feature was found. Such archives are not reported as passed.
     *
     * @return true if at least one unsupported feature was found.
     */
    public boolean hasUnsupported() {
        return !getIssues(Severity.UNSUPPORTED).isEmpty();
    }

    /**
     * Tests whether the archive passed the check without any structural error.
     *
     * @return true if no {@link Severity#ERROR} issue was found.
     */
    public boolean isStructurallySound() {
        return !hasErrors();
    }

    void setEntryCount(final int entryCount) {
        this.entryCount = entryCount;
    }

    @Override
    public String toString() {
        final StringBuilder builder = new StringBuilder();
        builder.append("entries inspected: ").append(entryCount).append(", issues: ").append(issues.size()).append(" (errors: ")
                .append(getIssues(Severity.ERROR).size()).append(", unsupported: ").append(getIssues(Severity.UNSUPPORTED).size()).append(", warnings: ")
                .append(getIssues(Severity.WARNING).size()).append(')');
        for (final Issue issue : issues) {
            builder.append(System.lineSeparator()).append("  ").append(issue);
        }
        return builder.toString();
    }
}
