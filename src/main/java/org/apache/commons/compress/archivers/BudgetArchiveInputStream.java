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

import java.io.IOException;
import java.util.Objects;

import org.apache.commons.compress.utils.InputStreamStatistics;

/**
 * A composable read budget layer for {@link ArchiveInputStream}s, guarding against decompression bombs.
 * <p>
 * The stream bills the decompressed bytes it actually reads against an {@link ArchiveBudget}; sizes declared by archive entries are never trusted. The
 * budgets enforced are:
 * </p>
 * <ul>
 * <li>{@link BudgetType#ENTRY_BYTES} - decompressed bytes read for the current entry.</li>
 * <li>{@link BudgetType#TOTAL_BYTES} - decompressed bytes read for the whole archive.</li>
 * <li>{@link BudgetType#ENTRY_COUNT} - entries returned by the stream.</li>
 * <li>{@link BudgetType#COMPRESSION_RATIO} - ratio of decompressed bytes read to compressed bytes consumed for the current entry.</li>
 * </ul>
 * <p>
 * When the caller advances to the next entry without fully reading the current one, the remainder of the current entry is drained through this stream first,
 * so skipped entries are billed to the budgets as well. Likewise, {@link #skip(long)} is routed through {@link #read(byte[], int, int)}. As soon as a budget
 * is exceeded a {@link BudgetExceededException} is thrown, reporting the entry name, the amount read, and the budget type; the stream is then unusable and
 * must be closed.
 * </p>
 * <p>
 * The compression ratio budget requires the delegate to implement {@link InputStreamStatistics}; archive formats that cannot report the number of compressed
 * bytes consumed do not support this budget and are rejected by the constructor.
 * </p>
 *
 * @param <E> The type of {@link ArchiveEntry} produced.
 * @since 1.29.0
 */
public class BudgetArchiveInputStream<E extends ArchiveEntry> extends ArchiveInputStream<E> implements InputStreamStatistics {

    private static final int DRAIN_BUFFER_SIZE = 8192;

    private final ArchiveInputStream<E> delegate;

    private final ArchiveBudget budget;

    private final boolean ratioSupported;

    private final byte[] drainBuffer = new byte[DRAIN_BUFFER_SIZE];

    private final byte[] probe = new byte[1];

    private E currentEntry;

    private boolean entryExhausted = true;

    private long entryBytesRead;

    private long totalBytesRead;

    private long entryCount;

    private long entryCompressedStart;

    private boolean closed;

    /**
     * Constructs a new budget-enforcing archive input stream.
     *
     * @param delegate the archive input stream to wrap.
     * @param budget   the budget to enforce.
     * @throws IllegalArgumentException if a compression ratio budget is configured but the delegate does not implement {@link InputStreamStatistics}.
     */
    @SuppressWarnings("deprecation")
    public BudgetArchiveInputStream(final ArchiveInputStream<E> delegate, final ArchiveBudget budget) {
        super(Objects.requireNonNull(delegate, "delegate"), delegate.getCharset().name());
        this.delegate = delegate;
        this.budget = Objects.requireNonNull(budget, "budget");
        this.ratioSupported = delegate instanceof InputStreamStatistics;
        if (budget.isCompressionRatioLimited() && !ratioSupported) {
            throw new IllegalArgumentException("A compression ratio budget is not supported by " + delegate.getClass().getName()
                    + ": it does not provide compressed byte statistics.");
        }
    }

    @Override
    public int available() throws IOException {
        return delegate.available();
    }

    @Override
    public boolean canReadEntryData(final ArchiveEntry archiveEntry) {
        return delegate.canReadEntryData(archiveEntry);
    }

    private void checkRatio() throws IOException {
        if (!budget.isCompressionRatioLimited() || currentEntry == null) {
            return;
        }
        final long compressed = compressedCount() - entryCompressedStart;
        if (compressed < 0) {
            throw new ArchiveException("A compression ratio budget is not supported by " + delegate.getClass().getName() + " for entry '" + entryName()
                    + "': compressed byte statistics are unavailable.");
        }
        if (entryBytesRead == 0) {
            return;
        }
        final double maxRatio = budget.getMaxCompressionRatio();
        final double actualRatio = compressed == 0 ? Double.POSITIVE_INFINITY : (double) entryBytesRead / compressed;
        if (actualRatio > maxRatio) {
            throw new BudgetExceededException(entryName(), entryBytesRead, actualRatio, maxRatio);
        }
    }

    /**
     * Closes the delegate without draining the current entry; closing early is always allowed and never trips a budget.
     */
    @Override
    public void close() throws IOException {
        if (!closed) {
            closed = true;
            delegate.close();
        }
    }

    private long compressedCount() throws IOException {
        return ratioSupported ? ((InputStreamStatistics) delegate).getCompressedCount() : -1;
    }

    /**
     * Gets the number of compressed bytes consumed by the delegate, so that budget layers can be stacked.
     *
     * @return the number of compressed bytes consumed, or -1 when the delegate does not provide compressed byte statistics.
     * @throws IOException if the delegate fails to provide the statistics.
     */
    @Override
    public long getCompressedCount() throws IOException {
        return compressedCount();
    }

    /**
     * Gets the total number of decompressed bytes read so far, including drained entries.
     *
     * @return the total number of decompressed bytes read so far.
     */
    @Override
    public long getUncompressedCount() {
        return totalBytesRead;
    }

    private void bill(final int read) throws IOException {
        entryBytesRead += read;
        totalBytesRead += read;
        checkRatio();
    }

    private void drainCurrentEntry() throws IOException {
        while (currentEntry != null && !entryExhausted) {
            if (read(drainBuffer, 0, drainBuffer.length) == -1) {
                break;
            }
        }
    }

    /**
     * Gets the number of decompressed bytes read for the current entry so far.
     *
     * @return the number of decompressed bytes read for the current entry.
     */
    public long getEntryBytesRead() {
        return entryBytesRead;
    }

    /**
     * Gets the number of entries returned so far.
     *
     * @return the number of entries returned so far.
     */
    public long getEntryCount() {
        return entryCount;
    }

    /**
     * Gets the total number of decompressed bytes read so far, including drained entries.
     *
     * @return the total number of decompressed bytes read so far.
     */
    @Override
    public long getBytesRead() {
        return totalBytesRead;
    }

    /**
     * Gets the total number of decompressed bytes read so far, including drained entries.
     *
     * @return the total number of decompressed bytes read so far.
     * @deprecated Use {@link #getBytesRead()}.
     */
    @Deprecated
    @Override
    public int getCount() {
        return (int) totalBytesRead;
    }

    /**
     * Gets the wrapped archive input stream.
     *
     * @return the delegate.
     */
    protected final ArchiveInputStream<E> getDelegate() {
        return delegate;
    }

    @Override
    public E getNextEntry() throws IOException {
        drainCurrentEntry();
        final E entry = delegate.getNextEntry();
        currentEntry = entry;
        entryBytesRead = 0;
        entryExhausted = entry == null;
        if (entry != null) {
            entryCount++;
            final long maxEntries = budget.getMaxEntries();
            if (maxEntries > 0 && entryCount > maxEntries) {
                throw new BudgetExceededException(BudgetType.ENTRY_COUNT, entry.getName(), entryCount, maxEntries);
            }
            entryCompressedStart = compressedCount();
        }
        return entry;
    }

    private void onEntryEof() throws IOException {
        if (currentEntry != null && !entryExhausted) {
            entryExhausted = true;
            checkRatio();
        }
    }

    @Override
    public int read(final byte[] b, final int off, final int len) throws IOException {
        Objects.requireNonNull(b, "b");
        if (len == 0) {
            return 0;
        }
        long allowed = len;
        final long maxEntryBytes = budget.getMaxEntryBytes();
        if (maxEntryBytes > 0) {
            allowed = Math.min(allowed, maxEntryBytes - entryBytesRead);
        }
        final long maxTotalBytes = budget.getMaxTotalBytes();
        if (maxTotalBytes > 0) {
            allowed = Math.min(allowed, maxTotalBytes - totalBytesRead);
        }
        if (allowed <= 0) {
            return readBeyondBudget();
        }
        final int read = delegate.read(b, off, (int) allowed);
        if (read == -1) {
            onEntryEof();
            return -1;
        }
        if (read > 0) {
            bill(read);
        }
        return read;
    }

    /**
     * Handles a read that arrives when a byte budget is exactly exhausted: if the delegate is really at the end of the current entry this is a clean EOF on
     * the budget boundary, otherwise the budget is exceeded.
     */
    private int readBeyondBudget() throws IOException {
        if (delegate.read(probe, 0, 1) == -1) {
            onEntryEof();
            return -1;
        }
        final long maxEntryBytes = budget.getMaxEntryBytes();
        if (maxEntryBytes > 0 && entryBytesRead >= maxEntryBytes) {
            throw new BudgetExceededException(BudgetType.ENTRY_BYTES, entryName(), entryBytesRead, maxEntryBytes);
        }
        throw new BudgetExceededException(BudgetType.TOTAL_BYTES, entryName(), totalBytesRead, budget.getMaxTotalBytes());
    }

    private String entryName() {
        return currentEntry != null ? currentEntry.getName() : null;
    }

    /**
     * Skips by reading through this stream, so skipped bytes are billed to the budgets.
     */
    @Override
    public long skip(final long n) throws IOException {
        long remaining = n;
        while (remaining > 0) {
            final int read = read(drainBuffer, 0, (int) Math.min(drainBuffer.length, remaining));
            if (read == -1) {
                break;
            }
            remaining -= read;
        }
        return n - remaining;
    }
}
