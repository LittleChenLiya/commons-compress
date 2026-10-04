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
 * Signals that a {@link BudgetArchiveInputStream} read budget has been exceeded. The exception reports the name of the entry being read, the amount read
 * against the exceeded budget, and the {@link BudgetType} that tripped.
 * <p>
 * Once this exception has been thrown the stream must be considered unusable and should be closed.
 * </p>
 *
 * @since 1.29.0
 */
public class BudgetExceededException extends ArchiveException {

    private static final long serialVersionUID = 1L;

    private static String buildMessage(final BudgetType budgetType, final String entryName, final long bytesRead, final long limit) {
        return String.format("Budget exceeded (%s) reading entry '%s': read %,d, limit is %,d.", budgetType, entryName, bytesRead, limit);
    }

    private static String buildRatioMessage(final String entryName, final long bytesRead, final double actualRatio, final double ratioLimit) {
        return String.format("Budget exceeded (%s) reading entry '%s': read %,d bytes, compression ratio %.2f exceeds limit %.2f.", BudgetType.COMPRESSION_RATIO,
                entryName, bytesRead, actualRatio, ratioLimit);
    }

    /** The kind of budget that was exceeded. */
    private final BudgetType budgetType;

    /** The name of the entry being read when the budget tripped, may be null. */
    private final String entryName;

    /** The amount read against the exceeded budget (bytes or entries, depending on {@link #budgetType}). */
    private final long bytesRead;

    /** The configured limit, or -1 when this exception reports a compression ratio. */
    private final long limit;

    /** The configured compression ratio limit, or {@link Double#NaN} when this exception does not report a compression ratio. */
    private final double ratioLimit;

    /** The actual compression ratio observed, or {@link Double#NaN} when this exception does not report a compression ratio. */
    private final double actualRatio;

    /**
     * Constructs a new instance for a byte or entry count budget.
     *
     * @param budgetType the kind of budget that was exceeded.
     * @param entryName  the name of the entry being read, may be null.
     * @param bytesRead  the amount read against the exceeded budget.
     * @param limit      the configured limit.
     */
    public BudgetExceededException(final BudgetType budgetType, final String entryName, final long bytesRead, final long limit) {
        super(buildMessage(budgetType, entryName, bytesRead, limit));
        this.budgetType = budgetType;
        this.entryName = entryName;
        this.bytesRead = bytesRead;
        this.limit = limit;
        this.ratioLimit = Double.NaN;
        this.actualRatio = Double.NaN;
    }

    /**
     * Constructs a new instance for the compression ratio budget.
     *
     * @param entryName   the name of the entry being read, may be null.
     * @param bytesRead   the number of decompressed bytes read for the entry.
     * @param actualRatio the actual compression ratio observed.
     * @param ratioLimit  the configured compression ratio limit.
     */
    public BudgetExceededException(final String entryName, final long bytesRead, final double actualRatio, final double ratioLimit) {
        super(buildRatioMessage(entryName, bytesRead, actualRatio, ratioLimit));
        this.budgetType = BudgetType.COMPRESSION_RATIO;
        this.entryName = entryName;
        this.bytesRead = bytesRead;
        this.limit = -1;
        this.ratioLimit = ratioLimit;
        this.actualRatio = actualRatio;
    }

    /**
     * Gets the actual compression ratio observed when the budget tripped.
     *
     * @return the actual compression ratio, or {@link Double#NaN} when this exception does not report a compression ratio.
     */
    public double getActualRatio() {
        return actualRatio;
    }

    /**
     * Gets the kind of budget that was exceeded.
     *
     * @return the budget type.
     */
    public BudgetType getBudgetType() {
        return budgetType;
    }

    /**
     * Gets the amount read against the exceeded budget: decompressed bytes for {@link BudgetType#ENTRY_BYTES}, {@link BudgetType#TOTAL_BYTES} and
     * {@link BudgetType#COMPRESSION_RATIO}, or the number of entries for {@link BudgetType#ENTRY_COUNT}.
     *
     * @return the amount read against the exceeded budget.
     */
    public long getBytesRead() {
        return bytesRead;
    }

    /**
     * Gets the name of the entry being read when the budget tripped.
     *
     * @return the entry name, may be null.
     */
    public String getEntryName() {
        return entryName;
    }

    /**
     * Gets the configured limit.
     *
     * @return the configured limit, or -1 when this exception reports a compression ratio; see {@link #getRatioLimit()}.
     */
    public long getLimit() {
        return limit;
    }

    /**
     * Gets the configured compression ratio limit.
     *
     * @return the configured compression ratio limit, or {@link Double#NaN} when this exception does not report a compression ratio.
     */
    public double getRatioLimit() {
        return ratioLimit;
    }
}
