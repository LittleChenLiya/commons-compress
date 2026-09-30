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

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * The result of checking an in-memory archive manifest with an {@link ArchiveDestinationChecker}.
 *
 * @since 1.29.0
 */
public final class ArchiveDestinationReport {

    static ArchiveDestinationReport of(final ArchiveDestinationPlatform platform, final List<ArchiveDestinationIssue> issues) {
        return new ArchiveDestinationReport(platform, Collections.unmodifiableList(issues));
    }

    private final List<ArchiveDestinationIssue> issues;

    private final ArchiveDestinationPlatform platform;

    private ArchiveDestinationReport(final ArchiveDestinationPlatform platform, final List<ArchiveDestinationIssue> issues) {
        this.platform = platform;
        this.issues = issues;
    }

    /**
     * Gets all reported landing problems in manifest order.
     *
     * @return An unmodifiable list of issues, possibly empty.
     */
    public List<ArchiveDestinationIssue> getIssues() {
        return issues;
    }

    /**
     * Gets all reported issues of the given kind in manifest order.
     *
     * @param kind The issue kind to filter for.
     * @return An unmodifiable list of matching issues.
     */
    public List<ArchiveDestinationIssue> getIssues(final ArchiveDestinationIssue.Kind kind) {
        return Collections.unmodifiableList(issues.stream().filter(issue -> issue.getKind() == kind).collect(Collectors.toList()));
    }

    /**
     * Gets the target platform rules used for this report.
     *
     * @return The target platform.
     */
    public ArchiveDestinationPlatform getPlatform() {
        return platform;
    }

    /**
     * Tests whether no landing problems were found.
     *
     * @return {@code true} if this report contains no issues.
     */
    public boolean isClean() {
        return issues.isEmpty();
    }

    @Override
    public String toString() {
        return "ArchiveDestinationReport[platform=" + platform + ", issues=" + issues.size() + "]";
    }
}
