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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Checks where archive entries would land in a target directory without creating any files.
 * <p>
 * Pass in archive entry metadata as an in-memory manifest together with the rules of the target platform. The checker normalizes each entry name
 * component by component and reports:
 * </p>
 * <ul>
 * <li>names that are empty or absolute,</li>
 * <li>{@code ..} traversals that escape the target directory,</li>
 * <li>reserved Windows file names,</li>
 * <li>duplicate entries, including duplicate directories,</li>
 * <li>files and directories that land on the same path,</li>
 * <li>case collisions on Windows-style case-insensitive file systems,</li>
 * <li>entries whose parent path is already occupied by a regular file or symbolic link,</li>
 * <li>symbolic links whose target is absolute or escapes the target directory.</li>
 * </ul>
 * <p>
 * The check is purely logical: it never touches the file system, so both {@link ArchiveDestinationPlatform#POSIX} and
 * {@link ArchiveDestinationPlatform#WINDOWS} rule sets can be verified on any host operating system.
 * </p>
 *
 * @since 1.29.0
 */
public class ArchiveDestinationChecker {

    /**
     * Builds {@link ArchiveDestinationChecker} instances.
     */
    public static final class Builder {

        private ArchiveDestinationPlatform platform;

        private Builder() {
        }

        /**
         * Builds a new checker.
         *
         * @return A new {@link ArchiveDestinationChecker}.
         */
        public ArchiveDestinationChecker build() {
            return new ArchiveDestinationChecker(platform != null ? platform : ArchiveDestinationPlatform.current());
        }

        /**
         * Sets the target platform rules.
         *
         * @param platform The target platform.
         * @return {@code this} instance.
         */
        public Builder setPlatform(final ArchiveDestinationPlatform platform) {
            this.platform = platform;
            return this;
        }
    }

    /**
     * A registered landing node: either an explicit entry or an implicit directory introduced by another entry's prefix.
     */
    private static final class Node {

        private final String displayPath;

        private final ArchiveDestinationEntry entry;

        private final boolean explicit;

        private final boolean directory;

        private Node(final String displayPath, final ArchiveDestinationEntry entry, final boolean explicit, final boolean directory) {
            this.displayPath = displayPath;
            this.entry = entry;
            this.explicit = explicit;
            this.directory = directory;
        }
    }

    /**
     * Creates a new {@link Builder}.
     *
     * @return A new builder.
     */
    public static Builder builder() {
        return new Builder();
    }

    private final ArchiveDestinationPlatform platform;

    /**
     * Constructs a checker for the given target platform rules.
     *
     * @param platform The target platform.
     */
    public ArchiveDestinationChecker(final ArchiveDestinationPlatform platform) {
        this.platform = platform != null ? platform : ArchiveDestinationPlatform.current();
    }

    /**
     * Checks an in-memory manifest of archive entries.
     *
     * @param entries The archive entries in archive order.
     * @return A report containing every detected landing problem.
     */
    public ArchiveDestinationReport check(final Iterable<ArchiveDestinationEntry> entries) {
        final List<ArchiveDestinationIssue> issues = new ArrayList<>();
        final Map<String, Node> nodes = new LinkedHashMap<>();
        for (final ArchiveDestinationEntry entry : entries) {
            final List<String> components = normalize(entry, issues);
            if (components == null) {
                continue;
            }
            final String landingPath = platform.join(components);
            register(entry, components, landingPath, nodes, issues);
        }
        return ArchiveDestinationReport.of(platform, issues);
    }

    /**
     * Checks an array of archive entries.
     *
     * @param entries The archive entries in archive order.
     * @return A report containing every detected landing problem.
     */
    public ArchiveDestinationReport check(final ArchiveDestinationEntry... entries) {
        return check(Arrays.asList(entries));
    }

    private void addIssue(final List<ArchiveDestinationIssue> issues, final ArchiveDestinationIssue.Kind kind,
            final ArchiveDestinationEntry entry, final ArchiveDestinationEntry other, final String landingPath) {
        issues.add(ArchiveDestinationIssue.of(kind, entry, other, landingPath));
    }

    private boolean isLinkTargetUnsafe(final String target) {
        if (target == null || target.trim().isEmpty()) {
            return true;
        }
        if (platform.isAbsoluteName(target)) {
            return true;
        }
        int depth = 0;
        for (final String component : platform.split(target)) {
            if ("..".equals(component)) {
                depth--;
                if (depth < 0) {
                    return true;
                }
            } else if (!component.isEmpty() && !".".equals(component)) {
                depth++;
            }
        }
        return false;
    }

    private List<String> normalize(final ArchiveDestinationEntry entry, final List<ArchiveDestinationIssue> issues) {
        final String rawName = entry.getName();
        if (rawName == null || rawName.trim().isEmpty()) {
            addIssue(issues, ArchiveDestinationIssue.Kind.EMPTY_NAME, entry, null, null);
            return null;
        }
        if (platform.isAbsoluteName(rawName)) {
            addIssue(issues, ArchiveDestinationIssue.Kind.ABSOLUTE_NAME, entry, null, rawName);
            return null;
        }
        final List<String> components = new ArrayList<>();
        boolean escapes = false;
        for (final String rawComponent : platform.split(rawName)) {
            if (rawComponent.isEmpty() || ".".equals(rawComponent)) {
                continue;
            }
            if ("..".equals(rawComponent)) {
                if (!components.isEmpty()) {
                    components.remove(components.size() - 1);
                } else {
                    escapes = true;
                }
                continue;
            }
            if (platform.isReservedComponent(rawComponent)) {
                addIssue(issues, ArchiveDestinationIssue.Kind.RESERVED_NAME, entry, null, rawComponent);
            }
            components.add(rawComponent);
        }
        if (escapes) {
            addIssue(issues, ArchiveDestinationIssue.Kind.TRAVERSAL_OUTSIDE_TARGET, entry, null,
                    components.isEmpty() ? "" : platform.join(components));
            return null;
        }
        if (components.isEmpty()) {
            addIssue(issues, ArchiveDestinationIssue.Kind.EMPTY_NAME, entry, null, rawName);
            return null;
        }
        if (entry.isSymbolicLink() && isLinkTargetUnsafe(entry.getLinkTarget())) {
            addIssue(issues, ArchiveDestinationIssue.Kind.UNSAFE_LINK_TARGET, entry, null, platform.join(components));
        }
        return components;
    }

    private void register(final ArchiveDestinationEntry entry, final List<String> components, final String landingPath,
            final Map<String, Node> nodes, final List<ArchiveDestinationIssue> issues) {
        final boolean directory = entry.isDirectory();
        final List<String> prefix = new ArrayList<>();
        for (int i = 0; i < components.size() - 1; i++) {
            prefix.add(components.get(i));
            final String prefixPath = platform.join(prefix);
            final Node parent = nodes.get(nodeKey(prefixPath));
            if (parent != null && !parent.directory) {
                addIssue(issues, ArchiveDestinationIssue.Kind.PARENT_OCCUPIED_BY_FILE, entry, parent.entry, landingPath);
            } else if (parent != null && !parent.displayPath.equals(prefixPath)) {
                addIssue(issues, ArchiveDestinationIssue.Kind.CASE_COLLISION, entry, parent.entry, landingPath);
            }
            putIfAbsent(nodes, new Node(prefixPath, entry, false, true));
        }
        final Node existing = nodes.get(nodeKey(landingPath));
        if (existing == null) {
            put(nodes, new Node(landingPath, entry, true, directory));
            return;
        }
        if (!existing.displayPath.equals(landingPath)) {
            addIssue(issues, ArchiveDestinationIssue.Kind.CASE_COLLISION, entry, existing.entry, landingPath);
            return;
        }
        if (!existing.explicit) {
            if (!directory) {
                addIssue(issues, ArchiveDestinationIssue.Kind.FILE_AND_DIRECTORY_SAME_NAME, entry, existing.entry, landingPath);
            }
            put(nodes, new Node(landingPath, entry, true, directory));
            return;
        }
        if (existing.directory != directory) {
            addIssue(issues, ArchiveDestinationIssue.Kind.FILE_AND_DIRECTORY_SAME_NAME, entry, existing.entry, landingPath);
        } else {
            addIssue(issues, ArchiveDestinationIssue.Kind.DUPLICATE_ENTRY, entry, existing.entry, landingPath);
        }
    }

    private String nodeKey(final String path) {
        return platform == ArchiveDestinationPlatform.WINDOWS ? path.toLowerCase(Locale.ROOT) : path;
    }

    private void put(final Map<String, Node> nodes, final Node node) {
        nodes.put(nodeKey(node.displayPath), node);
    }

    private void putIfAbsent(final Map<String, Node> nodes, final Node node) {
        nodes.putIfAbsent(nodeKey(node.displayPath), node);
    }
}
