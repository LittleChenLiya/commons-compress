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

/**
 * A single finding of a ZIP preflight check, bound to an archive entry or to the archive as a whole.
 * <p>
 * Instances are immutable.
 * </p>
 *
 * @since 1.29.0
 */
public final class ZipPreflightDiagnostic {

    /**
     * Entry index used for diagnostics that are not bound to a specific entry.
     */
    public static final int ARCHIVE_LEVEL = -1;

    /**
     * Offset used for diagnostics that are not bound to a specific file position.
     */
    public static final long NO_OFFSET = -1L;

    private final ZipPreflightSeverity severity;

    private final int entryIndex;

    private final String entryName;

    private final long offset;

    private final String message;

    ZipPreflightDiagnostic(final ZipPreflightSeverity severity, final int entryIndex, final String entryName, final long offset, final String message) {
        this.severity = severity;
        this.entryIndex = entryIndex;
        this.entryName = entryName;
        this.offset = offset;
        this.message = message;
    }

    /**
     * Gets the index of the entry inside the central directory this diagnostic belongs to, or {@link #ARCHIVE_LEVEL} if it applies to the archive
     * as a whole.
     *
     * @return the original central directory index of the entry.
     */
    public int getEntryIndex() {
        return entryIndex;
    }

    /**
     * Gets the name of the entry this diagnostic belongs to, or {@code null} if it applies to the archive as a whole.
     *
     * @return the entry name or {@code null}.
     */
    public String getEntryName() {
        return entryName;
    }

    /**
     * Gets the human readable description of the finding.
     *
     * @return the message.
     */
    public String getMessage() {
        return message;
    }

    /**
     * Gets the absolute file offset inside the archive at which the problem was detected, or {@link #NO_OFFSET} if not applicable.
     *
     * @return the file offset.
     */
    public long getOffset() {
        return offset;
    }

    /**
     * Gets the severity of this diagnostic.
     *
     * @return the severity.
     */
    public ZipPreflightSeverity getSeverity() {
        return severity;
    }

    @Override
    public String toString() {
        final StringBuilder builder = new StringBuilder();
        builder.append('[').append(severity).append(']');
        if (entryIndex >= 0) {
            builder.append(" entry #").append(entryIndex);
            if (entryName != null) {
                builder.append(" '").append(entryName).append('\'');
            }
        } else {
            builder.append(" archive");
        }
        if (offset >= 0) {
            builder.append(" @offset ").append(offset);
        }
        return builder.append(": ").append(message).toString();
    }
}
