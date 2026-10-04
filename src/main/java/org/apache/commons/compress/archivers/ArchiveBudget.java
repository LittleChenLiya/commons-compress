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

/**
 * An immutable, composable set of read budgets for {@link BudgetArchiveInputStream}.
 * <p>
 * All budgets are billed against the bytes actually read from the archive stream; sizes declared by archive entries are never trusted. A limit value less
 * than or equal to zero disables that particular budget.
 * </p>
 *
 * @since 1.29.0
 */
public final class ArchiveBudget {

    /**
     * Builds {@link ArchiveBudget} instances.
     */
    public static final class Builder {

        private long maxEntryBytes = UNLIMITED;
        private long maxTotalBytes = UNLIMITED;
        private long maxEntries = UNLIMITED;
        private double maxCompressionRatio = UNLIMITED;

        private Builder() {
            // Use ArchiveBudget#builder().
        }

        /**
         * Builds a new {@link ArchiveBudget}.
         *
         * @return a new {@link ArchiveBudget}.
         */
        public ArchiveBudget build() {
            return new ArchiveBudget(this);
        }

        /**
         * Sets the maximum compression ratio (decompressed bytes read divided by compressed bytes consumed) allowed for a single entry; a value less than
         * or equal to zero disables this budget.
         *
         * @param maxCompressionRatio the maximum compression ratio.
         * @return this builder.
         */
        public Builder maxCompressionRatio(final double maxCompressionRatio) {
            if (Double.isNaN(maxCompressionRatio) || Double.isInfinite(maxCompressionRatio)) {
                throw new IllegalArgumentException("maxCompressionRatio must be a finite number: " + maxCompressionRatio);
            }
            this.maxCompressionRatio = maxCompressionRatio;
            return this;
        }

        /**
         * Sets the maximum number of entries the archive stream may return; a value less than or equal to zero disables this budget.
         *
         * @param maxEntries the maximum entry count.
         * @return this builder.
         */
        public Builder maxEntries(final long maxEntries) {
            this.maxEntries = maxEntries;
            return this;
        }

        /**
         * Sets the maximum number of decompressed bytes read for a single entry; a value less than or equal to zero disables this budget.
         *
         * @param maxEntryBytes the maximum number of bytes per entry.
         * @return this builder.
         */
        public Builder maxEntryBytes(final long maxEntryBytes) {
            this.maxEntryBytes = maxEntryBytes;
            return this;
        }

        /**
         * Sets the maximum number of decompressed bytes read for the whole archive, including skipped entries; a value less than or equal to zero disables
         * this budget.
         *
         * @param maxTotalBytes the maximum total number of bytes.
         * @return this builder.
         */
        public Builder maxTotalBytes(final long maxTotalBytes) {
            this.maxTotalBytes = maxTotalBytes;
            return this;
        }
    }

    /** Sentinel for a disabled budget. */
    private static final long UNLIMITED = -1;

    /**
     * Gets a new {@link Builder}.
     *
     * @return a new {@link Builder}.
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Gets an {@link ArchiveBudget} with all budgets disabled.
     *
     * @return an unlimited budget.
     */
    public static ArchiveBudget unlimited() {
        return builder().build();
    }

    private final long maxEntryBytes;

    private final long maxTotalBytes;

    private final long maxEntries;

    private final double maxCompressionRatio;

    private ArchiveBudget(final Builder builder) {
        this.maxEntryBytes = builder.maxEntryBytes;
        this.maxTotalBytes = builder.maxTotalBytes;
        this.maxEntries = builder.maxEntries;
        this.maxCompressionRatio = builder.maxCompressionRatio;
    }

    /**
     * Gets the maximum compression ratio allowed for a single entry, or a value less than or equal to zero when the budget is disabled.
     *
     * @return the maximum compression ratio.
     */
    public double getMaxCompressionRatio() {
        return maxCompressionRatio;
    }

    /**
     * Gets the maximum number of entries, or a value less than or equal to zero when the budget is disabled.
     *
     * @return the maximum entry count.
     */
    public long getMaxEntries() {
        return maxEntries;
    }

    /**
     * Gets the maximum number of decompressed bytes per entry, or a value less than or equal to zero when the budget is disabled.
     *
     * @return the maximum number of bytes per entry.
     */
    public long getMaxEntryBytes() {
        return maxEntryBytes;
    }

    /**
     * Gets the maximum total number of decompressed bytes, or a value less than or equal to zero when the budget is disabled.
     *
     * @return the maximum total number of bytes.
     */
    public long getMaxTotalBytes() {
        return maxTotalBytes;
    }

    /**
     * Tests whether the compression ratio budget is enabled.
     *
     * @return whether the compression ratio budget is enabled.
     */
    public boolean isCompressionRatioLimited() {
        return maxCompressionRatio > 0;
    }

    @Override
    public String toString() {
        return "ArchiveBudget[maxEntryBytes=" + maxEntryBytes + ", maxTotalBytes=" + maxTotalBytes + ", maxEntries=" + maxEntries + ", maxCompressionRatio="
                + maxCompressionRatio + "]";
    }
}
