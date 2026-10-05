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
package org.apache.commons.compress.compressors;

import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;

/**
 * An {@link InputStream} that first replays a previously captured prefix and then continues reading from a borrowed underlying input stream, so that no
 * byte consumed for format detection is lost.
 *
 * <p>
 * The underlying stream is not required to support {@link InputStream#mark(int) mark}/{@link InputStream#reset() reset}; this class never calls them.
 * Whether {@link #close()} is forwarded to the underlying stream is an explicit policy chosen at construction time.
 * </p>
 *
 * @since 1.29.0
 */
public class PrefixReplayInputStream extends InputStream {

    private final byte[] prefix;

    private final InputStream delegate;

    private final boolean closeUnderlying;

    private int position;

    private boolean closed;

    /**
     * Constructs a new instance that replays {@code prefix} and then reads from {@code delegate}.
     *
     * @param prefix          the bytes already read from {@code delegate}, replayed first; copied defensively, may be empty but not null.
     * @param delegate        the borrowed underlying stream, read after the prefix is exhausted.
     * @param closeUnderlying whether {@link #close()} also closes {@code delegate}.
     */
    public PrefixReplayInputStream(final byte[] prefix, final InputStream delegate, final boolean closeUnderlying) {
        this.prefix = Objects.requireNonNull(prefix, "prefix").clone();
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.closeUnderlying = closeUnderlying;
    }

    @Override
    public int available() throws IOException {
        checkOpen();
        return prefix.length - position + delegate.available();
    }

    private void checkOpen() throws IOException {
        if (closed) {
            throw new IOException("Stream is closed.");
        }
    }

    /**
     * Closes this stream. The borrowed underlying stream is closed only when this instance was constructed with the close-underlying policy enabled.
     */
    @Override
    public void close() throws IOException {
        if (!closed) {
            closed = true;
            if (closeUnderlying) {
                delegate.close();
            }
        }
    }

    /**
     * Gets the number of prefix bytes that have not been replayed yet.
     *
     * @return the number of remaining prefix bytes.
     */
    public int getRemainingPrefixLength() {
        return prefix.length - position;
    }

    /**
     * Tests whether closing this stream also closes the borrowed underlying stream.
     *
     * @return whether closing this stream also closes the borrowed underlying stream.
     */
    public boolean isCloseUnderlying() {
        return closeUnderlying;
    }

    @Override
    public boolean markSupported() {
        return false;
    }

    @Override
    public int read() throws IOException {
        checkOpen();
        if (position < prefix.length) {
            return prefix[position++] & 0xff;
        }
        return delegate.read();
    }

    @Override
    public int read(final byte[] b, final int off, final int len) throws IOException {
        checkOpen();
        Objects.requireNonNull(b, "b");
        if (off < 0 || len < 0 || len > b.length - off) {
            throw new IndexOutOfBoundsException();
        }
        if (len == 0) {
            return 0;
        }
        if (position < prefix.length) {
            final int count = Math.min(len, prefix.length - position);
            System.arraycopy(prefix, position, b, off, count);
            position += count;
            return count;
        }
        return delegate.read(b, off, len);
    }

    @Override
    public long skip(final long n) throws IOException {
        checkOpen();
        if (n <= 0) {
            return 0;
        }
        final int remaining = prefix.length - position;
        if (remaining > 0) {
            final long skipped = Math.min(n, remaining);
            position += (int) skipped;
            return skipped;
        }
        return delegate.skip(n);
    }

}
