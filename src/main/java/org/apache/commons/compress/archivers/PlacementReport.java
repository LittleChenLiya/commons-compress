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

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

/**
 * The outcome of an {@link ArchivePlacementChecker} run: an ordered, immutable list of {@link PlacementIssue}s.
 *
 * @since 1.29.0
 */
public final class PlacementReport implements Iterable<PlacementIssue> {

    private final List<PlacementIssue> issues;

    PlacementReport(final List<PlacementIssue> issues) {
        this.issues = Collections.unmodifiableList(new ArrayList<>(issues));
    }

    /**
     * Gets the number of issues found.
     *
     * @return the issue count.
     */
    public int getIssueCount() {
        return issues.size();
    }

    /**
     * Gets all issues in the order they were detected.
     *
     * @return the issues, never null.
     */
    public List<PlacementIssue> getIssues() {
        return issues;
    }

    /**
     * Tests whether any issue was found.
     *
     * @return true if at least one issue was found.
     */
    public boolean hasIssues() {
        return !issues.isEmpty();
    }

    @Override
    public Iterator<PlacementIssue> iterator() {
        return issues.iterator();
    }

    @Override
    public String toString() {
        final StringBuilder builder = new StringBuilder("PlacementReport[").append(issues.size()).append(" issue(s)]");
        for (final PlacementIssue issue : issues) {
            builder.append(System.lineSeparator()).append(" - ").append(issue);
        }
        return builder.toString();
    }
}
