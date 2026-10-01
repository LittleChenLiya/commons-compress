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
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Checks where archive entries would land if they were extracted to a target directory, without creating any file.
 *
 * <p>
 * Entry names are normalized component by component using the rules of a {@link TargetPlatform}; the checker then
 * reports empty paths, absolute paths, paths escaping the target directory through "{@code ..}" components, reserved
 * device names, duplicate entries, file/directory clashes on the same path, case-only collisions and entries whose
 * parent path is occupied by a regular file or link. Link targets are validated as well.
 * </p>
 *
 * <pre>
 * PlacementReport report = ArchivePlacementChecker.check(TargetPlatform.WINDOWS,
 *     PlacementEntry.directory("docs/"), PlacementEntry.file("docs/index.txt"), PlacementEntry.file("../outside"));
 * </pre>
 *
 * @since 1.29.0
 */
public final class ArchivePlacementChecker {

    /** Normalized form of one entry. */
    private static final class Holder {
        final PlacementEntry entry;
        final List<String> components;
        final String path;

        Holder(final PlacementEntry entry, final List<String> components) {
            this.entry = entry;
            this.components = components;
            this.path = String.join("/", components);
        }
    }

    /**
     * Checks the given entries against the rules of the target platform.
     *
     * @param platform the target platform rules to apply.
     * @param entries the entry metadata to check.
     * @return the report, never null.
     */
    public static PlacementReport check(final TargetPlatform platform, final Iterable<PlacementEntry> entries) {
        Objects.requireNonNull(platform, "platform");
        Objects.requireNonNull(entries, "entries");
        return new ArchivePlacementChecker(platform, entries).run();
    }

    /**
     * Checks the given entries against the rules of the target platform.
     *
     * @param platform the target platform rules to apply.
     * @param entries the entry metadata to check.
     * @return the report, never null.
     */
    public static PlacementReport check(final TargetPlatform platform, final PlacementEntry... entries) {
        Objects.requireNonNull(entries, "entries");
        return check(platform, Arrays.asList(entries));
    }

    private final TargetPlatform platform;
    private final Iterable<PlacementEntry> entries;
    private final List<PlacementIssue> issues = new ArrayList<>();
    private final List<Holder> holders = new ArrayList<>();

    private ArchivePlacementChecker(final TargetPlatform platform, final Iterable<PlacementEntry> entries) {
        this.platform = platform;
        this.entries = entries;
    }

    private void addIssue(final PlacementIssue.Type type, final String entryName, final String relatedName, final String message) {
        issues.add(new PlacementIssue(type, entryName, relatedName, message));
    }

    private void checkCollisions() {
        final Map<String, List<Holder>> byFoldedPath = new LinkedHashMap<>();
        for (final Holder holder : holders) {
            byFoldedPath.computeIfAbsent(platform.foldCase(holder.path), key -> new ArrayList<>()).add(holder);
        }
        final Set<String> seenPairs = new HashSet<>();
        for (final List<Holder> group : byFoldedPath.values()) {
            for (int i = 0; i < group.size(); i++) {
                for (int j = i + 1; j < group.size(); j++) {
                    checkPair(group.get(i), group.get(j), seenPairs);
                }
            }
        }
    }

    private void checkLinkTargets() {
        for (final Holder holder : holders) {
            final PlacementEntry entry = holder.entry;
            final String linkName = entry.getLinkName();
            if (!entry.isLink() || linkName == null || linkName.isEmpty()) {
                continue;
            }
            if (platform.isAbsolute(linkName)) {
                addIssue(PlacementIssue.Type.LINK_TARGET_ABSOLUTE, entry.getName(), null,
                    "link entry '" + entry.getName() + "' has the absolute link target '" + linkName + "'");
                continue;
            }
            final List<String> resolved = new ArrayList<>(holder.components.subList(0, holder.components.size() - 1));
            if (!resolveInto(resolved, linkName)) {
                addIssue(PlacementIssue.Type.LINK_TARGET_OUTSIDE, entry.getName(), null,
                    "link entry '" + entry.getName() + "' has the target '" + linkName + "' which resolves outside the target directory");
            }
        }
    }

    private void checkPair(final Holder first, final Holder second, final Set<String> seenPairs) {
        final String firstName = first.entry.getName();
        final String secondName = second.entry.getName();
        if (!seenPairs.add(firstName + " " + secondName)) {
            return;
        }
        if (!first.path.equals(second.path)) {
            addIssue(PlacementIssue.Type.CASE_COLLISION, firstName, secondName,
                "entries '" + firstName + "' and '" + secondName + "' collide on a case-insensitive file system");
        } else if (first.entry.isDirectory() != second.entry.isDirectory()) {
            final String fileName = first.entry.isDirectory() ? secondName : firstName;
            final String dirName = first.entry.isDirectory() ? firstName : secondName;
            addIssue(PlacementIssue.Type.FILE_DIRECTORY_CONFLICT, fileName, dirName,
                "path '" + first.path + "' is used by both the non-directory entry '" + fileName + "' and the directory entry '" + dirName + "'");
        } else {
            addIssue(PlacementIssue.Type.DUPLICATE_ENTRY, firstName, secondName,
                "entries '" + firstName + "' and '" + secondName + "' duplicate the path '" + first.path + "'");
        }
    }

    private void checkParents() {
        final Map<String, List<Holder>> byFoldedPath = new LinkedHashMap<>();
        for (final Holder holder : holders) {
            byFoldedPath.computeIfAbsent(platform.foldCase(holder.path), key -> new ArrayList<>()).add(holder);
        }
        final Set<String> seenPairs = new HashSet<>();
        for (final Holder holder : holders) {
            for (int depth = 1; depth < holder.components.size(); depth++) {
                final String ancestor = platform.foldCase(String.join("/", holder.components.subList(0, depth)));
                final List<Holder> ancestors = byFoldedPath.get(ancestor);
                if (ancestors == null) {
                    continue;
                }
                for (final Holder parent : ancestors) {
                    if (!parent.entry.isDirectory() && seenPairs.add(holder.entry.getName() + " " + parent.entry.getName())) {
                        addIssue(PlacementIssue.Type.PARENT_OCCUPIED_BY_FILE, holder.entry.getName(), parent.entry.getName(),
                            "entry '" + holder.entry.getName() + "' cannot be extracted because '" + parent.entry.getName() + "' is not a directory");
                    }
                }
            }
        }
    }

    private Holder normalize(final PlacementEntry entry) {
        String name = entry.getName();
        if (name == null || name.isEmpty()) {
            addIssue(PlacementIssue.Type.EMPTY_PATH, name, null, "entry has an empty path");
            return null;
        }
        while (!name.isEmpty() && platform.isSeparator(name.charAt(name.length() - 1))) {
            name = name.substring(0, name.length() - 1);
        }
        if (platform.isAbsolute(name)) {
            addIssue(PlacementIssue.Type.ABSOLUTE_PATH, entry.getName(), null,
                "entry '" + entry.getName() + "' uses an absolute path and would not land inside the target directory");
            return null;
        }
        final List<String> components = new ArrayList<>();
        if (!resolveInto(components, name)) {
            addIssue(PlacementIssue.Type.OUTSIDE_TARGET, entry.getName(), null,
                "entry '" + entry.getName() + "' resolves outside the target directory");
            return null;
        }
        if (components.isEmpty()) {
            addIssue(PlacementIssue.Type.EMPTY_PATH, entry.getName(), null,
                "entry '" + entry.getName() + "' normalizes to an empty path");
            return null;
        }
        for (final String component : components) {
            if (platform.isReservedName(component)) {
                addIssue(PlacementIssue.Type.RESERVED_NAME, entry.getName(), null,
                    "entry '" + entry.getName() + "' uses the reserved name '" + component + "' on the target platform");
            }
        }
        return new Holder(entry, components);
    }

    /**
     * Splits a relative name into components and applies them to the given stack, resolving "{@code .}" and
     * "{@code ..}".
     *
     * @return false if the name escapes above the stack's base.
     */
    private boolean resolveInto(final List<String> stack, final String name) {
        int start = 0;
        final int length = name.length();
        for (int i = 0; i <= length; i++) {
            if (i < length && !platform.isSeparator(name.charAt(i))) {
                continue;
            }
            final String component = name.substring(start, i);
            start = i + 1;
            if (component.isEmpty() || component.equals(".")) {
                continue;
            }
            if (component.equals("..")) {
                if (stack.isEmpty()) {
                    return false;
                }
                stack.remove(stack.size() - 1);
            } else {
                stack.add(component);
            }
        }
        return true;
    }

    private PlacementReport run() {
        for (final PlacementEntry entry : entries) {
            Objects.requireNonNull(entry, "entry");
            final Holder holder = normalize(entry);
            if (holder != null) {
                holders.add(holder);
            }
        }
        checkCollisions();
        checkParents();
        checkLinkTargets();
        return new PlacementReport(issues);
    }
}
