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

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;

import org.apache.commons.io.IOUtils;

/**
 * Wraps a {@link CompressorStreamFactory} so that {@link InputStream}s which do not support {@link InputStream#mark() mark} can still be auto-detected.
 *
 * <p>
 * {@link CompressorStreamFactory#createCompressorInputStream(InputStream)} requires the input stream to support {@code mark()}; otherwise it throws an
 * {@link IllegalArgumentException}. This wrapper removes that limitation: it reads a bounded prefix of the input, detects the compressor type from that prefix
 * and then feeds the compressor a playback stream that starts with the already-read prefix followed by the rest of the original stream, so no bytes are lost
 * and decompression starts at the original beginning of the stream.
 * </p>
 *
 * <p>
 * If no compressor type is recognized, {@link #createCompressorInputStream(InputStream)} returns a pass-through {@link CompressorInputStream} that plays back
 * the prefix followed by the original stream, allowing callers to read the raw bytes in full. If a compressor type is recognized but the stream header is
 * corrupt, a {@link CompressorException} is thrown that reports the corruption rather than an unknown format.
 * </p>
 *
 * <p>
 * The {@link ClosePolicy} explicitly selects whether closing the created stream also closes the borrowed underlying stream.
 * </p>
 *
 * @since 1.29.0
 */
public class CompressorStreamFactoryWrapper {

    /**
     * Policy for closing the borrowed underlying input stream when the created stream is closed.
     */
    public enum ClosePolicy {

        /**
         * Closing the created stream also closes the underlying input stream.
         */
        CLOSE_UNDERLYING,

        /**
         * Closing the created stream does not close the underlying input stream; the caller keeps ownership and remains responsible for closing it.
         */
        KEEP_UNDERLYING_OPEN
    }

    /** The maximum number of bytes read from the input for format detection, matching the signature buffer used by {@link CompressorStreamFactory#detect}. */
    static final int DEFAULT_SIGNATURE_LENGTH = 12;

    private final CompressorStreamFactory factory;
    private final ClosePolicy closePolicy;
    private final int signatureLength;

    /**
     * Constructs a new wrapper with a default {@link CompressorStreamFactory}, {@link ClosePolicy#CLOSE_UNDERLYING} and the default signature length.
     */
    public CompressorStreamFactoryWrapper() {
        this(new CompressorStreamFactory(), ClosePolicy.CLOSE_UNDERLYING, DEFAULT_SIGNATURE_LENGTH);
    }

    /**
     * Constructs a new wrapper with the default signature length.
     *
     * @param factory     the factory to wrap, must not be {@code null}.
     * @param closePolicy whether closing the created stream closes the underlying stream, must not be {@code null}.
     */
    public CompressorStreamFactoryWrapper(final CompressorStreamFactory factory, final ClosePolicy closePolicy) {
        this(factory, closePolicy, DEFAULT_SIGNATURE_LENGTH);
    }

    /**
     * Constructs a new wrapper.
     *
     * @param factory         the factory to wrap, must not be {@code null}.
     * @param closePolicy      whether closing the created stream closes the underlying stream, must not be {@code null}.
     * @param signatureLength  the maximum number of bytes read from the input for format detection, must be positive.
     */
    public CompressorStreamFactoryWrapper(final CompressorStreamFactory factory, final ClosePolicy closePolicy, final int signatureLength) {
        this.factory = Objects.requireNonNull(factory, "factory");
        this.closePolicy = Objects.requireNonNull(closePolicy, "closePolicy");
        if (signatureLength <= 0) {
            throw new IllegalArgumentException("signatureLength must be positive");
        }
        this.signatureLength = signatureLength;
    }

    /**
     * Creates a compressor input stream from an input stream, auto-detecting the compressor type from a bounded prefix of the stream.
     *
     * <p>
     * The input stream does not need to support {@code mark()}. At most {@code signatureLength} bytes are read for detection. If a compressor type is
     * detected, a decompressing stream is returned that reads from the original beginning of the stream. If no type is detected, a pass-through stream is
     * returned that plays back the already-read prefix followed by the rest of the stream.
     * </p>
     *
     * @param in the input stream, must not be {@code null}.
     * @return a compressor input stream; never {@code null}.
     * @throws CompressorException      if a compressor type was detected but its header is corrupt or unreadable, or if the prefix could not be read.
     * @throws IllegalArgumentException if the stream is {@code null}.
     */
    public CompressorInputStream createCompressorInputStream(final InputStream in) throws CompressorException {
        Objects.requireNonNull(in, "in");
        final byte[] prefix = new byte[signatureLength];
        final int prefixLength;
        try {
            prefixLength = IOUtils.read(in, prefix);
        } catch (final IOException e) {
            throw new CompressorException("Failed to read stream prefix.", e);
        }
        // The playback stream starts with the already-read prefix and continues with the rest of the original stream.
        final InputStream playback = new PlaybackInputStream(prefix, prefixLength, in, closePolicy == ClosePolicy.CLOSE_UNDERLYING);
        final String name;
        try {
            name = CompressorStreamFactory.detect(new ByteArrayInputStream(prefix, 0, prefixLength));
        } catch (final CompressorException e) {
            // No format recognized: hand the prefix back through a playback stream so no bytes are lost.
            return new PlaybackCompressorInputStream(playback);
        }
        try {
            return factory.createCompressorInputStream(name, playback);
        } catch (final CompressorException e) {
            // The signature matched a known format, but the stream header is damaged.
            throw new CompressorException("Detected " + name + " stream, but its header is corrupt or unreadable.", e);
        }
    }
}
