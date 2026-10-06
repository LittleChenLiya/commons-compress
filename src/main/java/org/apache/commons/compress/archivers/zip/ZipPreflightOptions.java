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
 * Options controlling a ZIP preflight check, see {@link ZipPreflight}.
 * <p>
 * By default only the structural check is performed. The optional content validation phase decompresses STORED and DEFLATED entries in memory,
 * counts the actually produced bytes and verifies their CRC-32 against the central directory instead of trusting the sizes recorded in the
 * headers. Budgets limit the number of uncompressed bytes accepted per entry and in total; as soon as a budget is exceeded the check stops
 * immediately with an {@link ZipPreflightSeverity#ERROR} diagnostic.
 * </p>
 * <p>
 * Instances are immutable, use {@link #builder()} to configure one.
 * </p>
 *
 * @since 1.29.0
 */
public final class ZipPreflightOptions {

    /**
     * Builds {@link ZipPreflightOptions} instances.
     */
    public static final class Builder {

        private boolean validateContent;

        private long maxUncompressedBytesPerEntry = UNLIMITED;

        private long maxUncompressedBytesTotal = UNLIMITED;

        private Builder() {
        }

        /**
         * Builds the options instance.
         *
         * @return a new immutable options instance.
         */
        public ZipPreflightOptions get() {
            return new ZipPreflightOptions(this);
        }

        /**
         * Sets the maximum number of uncompressed bytes accepted for a single entry during content validation, {@link #UNLIMITED} disables the
         * limit.
         *
         * @param maxUncompressedBytesPerEntry the per-entry budget in bytes.
         * @return {@code this} instance.
         */
        public Builder setMaxUncompressedBytesPerEntry(final long maxUncompressedBytesPerEntry) {
            this.maxUncompressedBytesPerEntry = maxUncompressedBytesPerEntry < 0 ? UNLIMITED : maxUncompressedBytesPerEntry;
            return this;
        }

        /**
         * Sets the maximum number of uncompressed bytes accepted for the whole archive during content validation, {@link #UNLIMITED} disables the
         * limit.
         *
         * @param maxUncompressedBytesTotal the global budget in bytes.
         * @return {@code this} instance.
         */
        public Builder setMaxUncompressedBytesTotal(final long maxUncompressedBytesTotal) {
            this.maxUncompressedBytesTotal = maxUncompressedBytesTotal < 0 ? UNLIMITED : maxUncompressedBytesTotal;
            return this;
        }

        /**
         * Sets whether the optional content validation phase decompresses entries and verifies CRC-32 checksums and byte budgets.
         *
         * @param validateContent whether to validate entry content.
         * @return {@code this} instance.
         */
        public Builder setValidateContent(final boolean validateContent) {
            this.validateContent = validateContent;
            return this;
        }
    }

    /**
     * Budget value meaning "no limit".
     */
    public static final long UNLIMITED = -1L;

    /**
     * The default options: structural check only, no content validation, no budgets.
     */
    private static final ZipPreflightOptions DEFAULT = builder().get();

    /**
     * Constructs a new builder.
     *
     * @return a new builder.
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Gets the default options: structural check only.
     *
     * @return the default options.
     */
    public static ZipPreflightOptions defaultOptions() {
        return DEFAULT;
    }

    private final boolean validateContent;

    private final long maxUncompressedBytesPerEntry;

    private final long maxUncompressedBytesTotal;

    private ZipPreflightOptions(final Builder builder) {
        this.validateContent = builder.validateContent;
        this.maxUncompressedBytesPerEntry = builder.maxUncompressedBytesPerEntry;
        this.maxUncompressedBytesTotal = builder.maxUncompressedBytesTotal;
    }

    /**
     * Gets the maximum number of uncompressed bytes accepted for a single entry, or {@link #UNLIMITED}.
     *
     * @return the per-entry budget in bytes.
     */
    public long getMaxUncompressedBytesPerEntry() {
        return maxUncompressedBytesPerEntry;
    }

    /**
     * Gets the maximum number of uncompressed bytes accepted for the whole archive, or {@link #UNLIMITED}.
     *
     * @return the global budget in bytes.
     */
    public long getMaxUncompressedBytesTotal() {
        return maxUncompressedBytesTotal;
    }

    /**
     * Tests whether the optional content validation phase is enabled.
     *
     * @return whether entry content is decompressed and verified.
     */
    public boolean isValidateContent() {
        return validateContent;
    }
}
