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
package org.apache.commons.compress.archivers.tar;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.apache.commons.compress.archivers.ArchiveException;

/**
 * Signals that a hard link in a TAR archive cannot be resolved to a content entry.
 *
 * <p>
 * The {@link Reason} describes why the hard link could not be resolved:
 * </p>
 * <ul>
 * <li>{@link Reason#OUT_OF_BOUNDS} - the link path resolves to a location outside the archive root, for example an absolute path or a path containing
 * {@code ..} segments that escape the archive.</li>
 * <li>{@link Reason#MISSING_TARGET} - no entry with the resolved target name exists in the archive.</li>
 * <li>{@link Reason#AMBIGUOUS_TARGET} - more than one entry with the resolved target name exists in the archive, so it is unclear which entry provides the
 * content.</li>
 * <li>{@link Reason#CYCLE} - the link chain leads back to one of the link entries, for example {@code a -> b -> a}.</li>
 * </ul>
 *
 * @since 1.29.0
 */
public class TarHardLinkException extends ArchiveException {

    private static final long serialVersionUID = 1L;

    /**
     * Enumerates the reasons a hard link cannot be resolved.
     *
     * @since 1.29.0
     */
    public enum Reason {

        /**
         * The link path resolves to a location outside the archive root.
         */
        OUT_OF_BOUNDS,

        /**
         * No entry with the resolved target name exists in the archive.
         */
        MISSING_TARGET,

        /**
         * More than one entry with the resolved target name exists in the archive.
         */
        AMBIGUOUS_TARGET,

        /**
         * The link chain leads back to one of the link entries.
         */
        CYCLE
    }

    private final transient Reason reason;

    private final transient String linkName;

    private final transient String targetName;

    private final transient List<String> chain;

    /**
     * Constructs a new instance.
     *
     * @param reason     the reason the hard link could not be resolved.
     * @param linkName   the name of the hard link entry.
     * @param targetName the resolved target name, may be null.
     * @param chain      the link chain from the hard link entry to the point of failure, may be null.
     */
    TarHardLinkException(final Reason reason, final String linkName, final String targetName, final List<String> chain) {
        super(buildMessage(reason, linkName, targetName, chain));
        this.reason = reason;
        this.linkName = linkName;
        this.targetName = targetName;
        this.chain = chain == null ? Collections.emptyList() : Collections.unmodifiableList(new ArrayList<>(chain));
    }

    private static String buildMessage(final Reason reason, final String linkName, final String targetName, final List<String> chain) {
        switch (reason) {
        case OUT_OF_BOUNDS:
            return String.format("Hard link '%s' points outside the archive root: '%s'", linkName, targetName);
        case MISSING_TARGET:
            return String.format("Hard link '%s' points to a missing entry: '%s'", linkName, targetName);
        case AMBIGUOUS_TARGET:
            return String.format("Hard link '%s' points to an ambiguous target: '%s'", linkName, targetName);
        case CYCLE:
            return String.format("Hard link cycle detected: %s", String.join(" -> ", chain));
        default:
            return String.format("Hard link '%s' cannot be resolved: '%s'", linkName, targetName);
        }
    }

    /**
     * Gets the link chain from the hard link entry to the point of failure.
     *
     * @return the link chain, never null.
     */
    public List<String> getChain() {
        return chain;
    }

    /**
     * Gets the name of the hard link entry that could not be resolved.
     *
     * @return the link entry name.
     */
    public String getLinkName() {
        return linkName;
    }

    /**
     * Gets the reason the hard link could not be resolved.
     *
     * @return the reason.
     */
    public Reason getReason() {
        return reason;
    }

    /**
     * Gets the resolved target name.
     *
     * @return the target name, may be null.
     */
    public String getTargetName() {
        return targetName;
    }
}
