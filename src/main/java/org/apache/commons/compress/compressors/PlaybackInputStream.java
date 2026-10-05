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
 * An input stream that plays back a byte array prefix followed by the (remaining) tail of another input stream.
 *
 * <p>
 * This is used to feed a compressor stream from the original beginning of the input after a prefix has already been consumed for format detection: the
 * prefix is served from memory and the remaining bytes are read from the underlying stream.
 * </p>
 *
 * <p>
 * Closing this stream closes the underlying tail stream only if {@code closeTail} is {@code true}; otherwise the caller keeps ownership of the tail.
 * </p>
 */
final class PlaybackInputStream extends InputStream {

    private final byte[] prefix;
    private final int prefixLength;
    private int prefixPosition;
    private final InputStream tail;
    private final boolean closeTail;
    private boolean closed;

    /**
     * Constructs a new playback stream.
     *
     * @param prefix       the already-read prefix bytes.
     * @param prefixLength  the number of valid bytes in {@code prefix}.
     * @param tail          the underlying stream positioned just after the prefix.
     * @param closeTail     {@code true} if closing this stream should close {@code tail}, {@code false} otherwise.
     */
    PlaybackInputStream(final byte[] prefix, final int prefixLength, final InputStream tail, final boolean closeTail) {
        this.prefix = prefix;
        this.prefixLength = prefixLength;
        this.tail = Objects.requireNonNull(tail, "tail");
        this.closeTail = closeTail;
    }

    @Override
    public int available() throws IOException {
        return prefixLength - prefixPosition + tail.available();
    }

    @Override
    public void close() throws IOException {
        if (closed) {
            return;
        }
        closed = true;
        if (closeTail) {
            tail.close();
        }
    }

    @Override
    public int read() throws IOException {
        if (prefixPosition < prefixLength) {
            return prefix[prefixPosition++] & 0xFF;
        }
        return tail.read();
    }

    @Override
    public int read(final byte[] b, final int off, final int len) throws IOException {
        Objects.requireNonNull(b, "b");
        if (off < 0 || len < 0 || off + len > b.length) {
            throw new IndexOutOfBoundsException("off=" + off + ", len=" + len + ", b.length=" + b.length);
        }
        if (len == 0) {
            return 0;
        }
        if (prefixPosition < prefixLength) {
            final int count = Math.min(len, prefixLength - prefixPosition);
            System.arraycopy(prefix, prefixPosition, b, off, count);
            prefixPosition += count;
            return count;
        }
        return tail.read(b, off, len);
    }
}
