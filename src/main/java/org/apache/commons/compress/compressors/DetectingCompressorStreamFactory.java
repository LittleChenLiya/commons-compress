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
import java.util.Arrays;
import java.util.Objects;

import org.apache.commons.io.IOUtils;

/**
 * Wraps a {@link CompressorStreamFactory} so that compressor streams can be auto-detected and created from input streams that do not support
 * {@link InputStream#mark(int) mark}/{@link InputStream#reset() reset}.
 *
 * <p>
 * Detection reads at most {@value CompressorStreamFactory#SIGNATURE_LENGTH} bytes from the given stream and never consumes more. Every byte read for
 * detection is captured in a {@link PrefixReplayInputStream}, so decompression always starts at the original first byte of the borrowed stream:
 * </p>
 *
 * <ul>
 * <li>When a format is detected, the compressor input stream is created over the replay stream and decompresses from the original start.</li>
 * <li>When no format is detected, {@link Detection#getReplayStream()} hands the captured prefix back and yields the complete original bytes, nothing is
 * lost.</li>
 * <li>When a format is detected but its header is corrupted, a {@link CorruptedCompressorStreamException} is thrown instead of reporting an unknown
 * format.</li>
 * </ul>
 *
 * <p>
 * Whether closing the created streams also closes the borrowed underlying stream is an explicit policy chosen at construction time.
 * </p>
 *
 * <pre>
 * DetectingCompressorStreamFactory factory = new DetectingCompressorStreamFactory(true);
 * DetectingCompressorStreamFactory.Detection detection = factory.detect(inputStream);
 * if (detection.isDetected()) {
 *     try (CompressorInputStream in = detection.createCompressorInputStream()) {
 *         IOUtils.copy(in, outputStream);
 *     }
 * } else {
 *     // Not a known compressor format: replay still returns all original bytes.
 *     IOUtils.copy(detection.getReplayStream(), outputStream);
 * }
 * </pre>
 *
 * @since 1.29.0
 */
public class DetectingCompressorStreamFactory {

    /**
     * The result of probing an input stream for a known compressor signature.
     */
    public final class Detection {

        private final String name;

        private final PrefixReplayInputStream replayStream;

        private Detection(final String name, final PrefixReplayInputStream replayStream) {
            this.name = name;
            this.replayStream = replayStream;
        }

        /**
         * Creates a compressor input stream for the detected format, reading from the original start of the probed stream.
         *
         * @return a new compressor input stream.
         * @throws CompressorException                if no compressor format was detected.
         * @throws CorruptedCompressorStreamException if a format was detected but its header is corrupted.
         */
        public CompressorInputStream createCompressorInputStream() throws CompressorException {
            if (name == null) {
                throw new CompressorException("No Compressor found for the stream signature.");
            }
            try {
                return factory.createCompressorInputStream(name, replayStream);
            } catch (final CompressorException e) {
                throw new CorruptedCompressorStreamException(name, e);
            }
        }

        /**
         * Gets the detected compressor name, for example {@link CompressorStreamFactory#GZIP}.
         *
         * @return the detected compressor name, or null if no format was detected.
         */
        public String getName() {
            return name;
        }

        /**
         * Gets the stream that replays the captured prefix and then continues with the borrowed underlying stream. Reading it to the end yields exactly the
         * bytes of the original stream, whether or not a format was detected.
         *
         * @return the replay stream positioned at the original first byte.
         */
        public PrefixReplayInputStream getReplayStream() {
            return replayStream;
        }

        /**
         * Tests whether a known compressor format was detected.
         *
         * @return true if a known compressor format was detected.
         */
        public boolean isDetected() {
            return name != null;
        }

    }

    private final CompressorStreamFactory factory;

    private final boolean closeUnderlying;

    /**
     * Constructs a new instance wrapping the {@link CompressorStreamFactory#getSingleton() singleton} factory.
     *
     * @param closeUnderlying whether closing created streams also closes the borrowed underlying stream.
     */
    public DetectingCompressorStreamFactory(final boolean closeUnderlying) {
        this(CompressorStreamFactory.getSingleton(), closeUnderlying);
    }

    /**
     * Constructs a new instance wrapping the given factory.
     *
     * @param factory         the delegate factory used to create compressor input streams for detected formats.
     * @param closeUnderlying whether closing created streams also closes the borrowed underlying stream.
     */
    public DetectingCompressorStreamFactory(final CompressorStreamFactory factory, final boolean closeUnderlying) {
        this.factory = Objects.requireNonNull(factory, "factory");
        this.closeUnderlying = closeUnderlying;
    }

    /**
     * Creates a compressor input stream from an input stream, auto-detecting the compressor type from the first few bytes of the stream. The input stream is
     * not required to support marks.
     *
     * @param in the input stream, only its first {@value CompressorStreamFactory#SIGNATURE_LENGTH} bytes are read for detection.
     * @return the compressor input stream, reading from the original start of {@code in}.
     * @throws CompressorException                if no compressor format was detected.
     * @throws CorruptedCompressorStreamException if a format was detected but its header is corrupted.
     * @throws IllegalArgumentException           if the stream is null.
     */
    public CompressorInputStream createCompressorInputStream(final InputStream in) throws CompressorException {
        return detect(in).createCompressorInputStream();
    }

    /**
     * Detects the compressor format of the given stream by reading at most {@value CompressorStreamFactory#SIGNATURE_LENGTH} bytes, without requiring mark
     * support. The returned {@link Detection} always holds a replay stream positioned at the original first byte, even when nothing was detected.
     *
     * @param in the input stream to probe.
     * @return the detection result.
     * @throws CompressorException      if reading the signature fails.
     * @throws IllegalArgumentException if the stream is null.
     */
    public Detection detect(final InputStream in) throws CompressorException {
        if (in == null) {
            throw new IllegalArgumentException("Stream must not be null.");
        }
        final byte[] buffer = new byte[CompressorStreamFactory.SIGNATURE_LENGTH];
        final int signatureLength;
        try {
            signatureLength = IOUtils.read(in, buffer);
        } catch (final IOException e) {
            throw new CompressorException("Failed to read signature.", e);
        }
        final byte[] prefix = signatureLength == buffer.length ? buffer : Arrays.copyOf(buffer, signatureLength);
        final PrefixReplayInputStream replayStream = new PrefixReplayInputStream(prefix, in, closeUnderlying);
        String name = null;
        try {
            name = CompressorStreamFactory.detect(prefix, signatureLength, CompressorStreamFactory.ALL_NAMES);
        } catch (final CompressorException e) {
            // Unknown format: the captured prefix stays available through the replay stream.
        }
        return new Detection(name, replayStream);
    }

    /**
     * Gets the delegate factory used to create compressor input streams for detected formats.
     *
     * @return the delegate factory.
     */
    public CompressorStreamFactory getFactory() {
        return factory;
    }

    /**
     * Tests whether closing created streams also closes the borrowed underlying stream.
     *
     * @return whether closing created streams also closes the borrowed underlying stream.
     */
    public boolean isCloseUnderlying() {
        return closeUnderlying;
    }

}
