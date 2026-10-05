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

/**
 * A pass-through {@link CompressorInputStream} used when no compressor format is detected.
 *
 * <p>
 * The bytes read from this stream are the raw bytes of the underlying playback stream (the already-read prefix followed by the rest of the original input),
 * so no data is lost.
 * </p>
 */
final class PlaybackCompressorInputStream extends CompressorInputStream {

    private final InputStream in;
    private boolean closed;

    /**
     * Constructs a new pass-through stream.
     *
     * @param in the playback stream to read from.
     */
    PlaybackCompressorInputStream(final InputStream in) {
        this.in = in;
    }

    @Override
    public void close() throws IOException {
        if (closed) {
            return;
        }
        closed = true;
        in.close();
    }

    @Override
    public int read() throws IOException {
        final int b = in.read();
        if (b == -1) {
            return -1;
        }
        count(1);
        return b & 0xFF;
    }

    @Override
    public int read(final byte[] b, final int off, final int len) throws IOException {
        final int n = in.read(b, off, len);
        if (n > 0) {
            count(n);
        }
        return n;
    }
}
