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

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.zip.CRC32;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * Structural preflight check for ZIP archives, to be run before an archive is extracted or copied.
 * <p>
 * The check parses the end of central directory record (including the ZIP64 variants), walks the central directory and cross-checks every entry
 * against its local file header: entry names, compression methods and the key general purpose flags must match. From the local file headers it
 * computes the real compressed data region of every entry and verifies that
 * </p>
 * <ul>
 * <li>no two entries occupy overlapping data regions,</li>
 * <li>no entry reaches into or beyond the central directory region,</li>
 * <li>the archive is not truncated,</li>
 * <li>ZIP64 length fields do not overflow 63 bits and are present whenever a 0xFFFFFFFF placeholder is used.</li>
 * </ul>
 * <p>
 * Data descriptors are recognized from general purpose flag bit 3 and validated in their ZIP32 and ZIP64 forms; legal deferred CRC and size
 * values (zero in the local file header, real values in the descriptor) are accepted and never reported as errors.
 * </p>
 * <p>
 * Every finding is reported as a {@link ZipPreflightDiagnostic} bound to the original central directory index of the entry and the absolute file
 * offset at which it was detected, classified as {@link ZipPreflightSeverity#ERROR structural error},
 * {@link ZipPreflightSeverity#UNSUPPORTED unsupported feature}, {@link ZipPreflightSeverity#UNVERIFIED supported but not verifiable feature} or
 * {@link ZipPreflightSeverity#WARNING warning}. Encrypted entries and split (multi-volume) archives are always reported as
 * {@link ZipPreflightSeverity#UNSUPPORTED} - the check never guesses that they passed.
 * </p>
 * <p>
 * An optional content validation phase (see {@link ZipPreflightOptions}) decompresses STORED and DEFLATED entries in memory, counts the actually
 * produced bytes instead of trusting the lengths recorded in the headers, verifies the CRC-32 of the output and enforces per-entry and global
 * uncompressed byte budgets; the check stops immediately once a budget is exceeded.
 * </p>
 * <p>
 * The check never writes anything, never extracts entries to disk and never delegates to external tools. When it finishes, the position of the
 * channel it inspected is restored to the value it had before the check.
 * </p>
 *
 * @since 1.29.0
 */
public final class ZipPreflight {

    /**
     * Internal view of one central directory entry plus the data derived from its local file header.
     */
    private static final class EntryInfo {

        private final int index;

        private final long centralHeaderOffset;

        private String name;

        private byte[] nameBytes = new byte[0];

        private int flags;

        private int method;

        private long crc;

        private long compressedSize;

        private long uncompressedSize;

        private long localHeaderOffset = -1;

        private long dataOffset = -1;

        private long dataEnd = -1;

        private boolean zip64;

        private boolean encrypted;

        private boolean dataDescriptor;

        private boolean structurallySound = true;

        private EntryInfo(final int index, final long centralHeaderOffset) {
            this.index = index;
            this.centralHeaderOffset = centralHeaderOffset;
        }
    }

    /**
     * The checking engine, holds the mutable state of a single run.
     */
    private static final class Checker {

        private final SeekableByteChannel channel;

        private final ZipPreflightOptions options;

        private final List<ZipPreflightDiagnostic> diagnostics = new ArrayList<>();

        private final List<EntryInfo> entries = new ArrayList<>();

        private long size;

        private long centralDirectoryOffset;

        private long centralDirectoryEnd;

        private long entryCount;

        private boolean stopContentValidation;

        private Checker(final SeekableByteChannel channel, final ZipPreflightOptions options) {
            this.channel = channel;
            this.options = options;
        }

        private void archiveError(final long offset, final String message) {
            diagnostics.add(new ZipPreflightDiagnostic(ZipPreflightSeverity.ERROR, ZipPreflightDiagnostic.ARCHIVE_LEVEL, null, offset, message));
        }

        private void archiveUnsupported(final String message) {
            diagnostics.add(new ZipPreflightDiagnostic(ZipPreflightSeverity.UNSUPPORTED, ZipPreflightDiagnostic.ARCHIVE_LEVEL, null,
                    ZipPreflightDiagnostic.NO_OFFSET, message));
        }

        private void archiveWarning(final long offset, final String message) {
            diagnostics.add(new ZipPreflightDiagnostic(ZipPreflightSeverity.WARNING, ZipPreflightDiagnostic.ARCHIVE_LEVEL, null, offset, message));
        }

        /**
         * Checks that entry data regions do not overlap each other and stay in front of the central directory.
         */
        private void checkDataRegionOverlaps() {
            final List<EntryInfo> ranged = new ArrayList<>();
            for (final EntryInfo entry : entries) {
                if (entry.localHeaderOffset >= 0 && entry.dataEnd >= entry.localHeaderOffset) {
                    if (entry.dataEnd > centralDirectoryOffset) {
                        error(entry, entry.dataEnd, "entry data region [" + entry.localHeaderOffset + ", " + entry.dataEnd
                                + ") extends into the central directory region starting at offset " + centralDirectoryOffset);
                        entry.structurallySound = false;
                    }
                    ranged.add(entry);
                }
            }
            ranged.sort(Comparator.comparingLong(entry -> entry.localHeaderOffset));
            EntryInfo previous = null;
            for (final EntryInfo entry : ranged) {
                if (previous != null && entry.localHeaderOffset < previous.dataEnd) {
                    error(entry, entry.localHeaderOffset,
                            "entry data region [" + entry.localHeaderOffset + ", " + entry.dataEnd + ") overlaps the data region ["
                                    + previous.localHeaderOffset + ", " + previous.dataEnd + ") of entry #" + previous.index
                                    + (previous.name != null ? " '" + previous.name + "'" : ""));
                    entry.structurallySound = false;
                }
                if (previous == null || entry.dataEnd > previous.dataEnd) {
                    previous = entry;
                }
            }
        }

        private void checkDuplicateNames() {
            final Map<String, EntryInfo> firstByName = new HashMap<>();
            for (final EntryInfo entry : entries) {
                if (entry.nameBytes.length == 0) {
                    warning(entry, entry.centralHeaderOffset, "entry has an empty name");
                    continue;
                }
                final String key = new String(entry.nameBytes, StandardCharsets.ISO_8859_1);
                final EntryInfo first = firstByName.putIfAbsent(key, entry);
                if (first != null) {
                    warning(entry, entry.centralHeaderOffset,
                            "duplicate entry name, already used by entry #" + first.index + " (a naming clash, not a data overlap)");
                }
            }
        }

        private void error(final EntryInfo entry, final long offset, final String message) {
            diagnostics.add(new ZipPreflightDiagnostic(ZipPreflightSeverity.ERROR, entry.index, entry.name, offset, message));
        }

        private ZipPreflightReport run() throws IOException {
            final long savedPosition = channel.position();
            try {
                size = channel.size();
                if (size < MIN_EOCD_LENGTH) {
                    archiveError(size, "archive is too small to contain an end of central directory record (truncated archive?)");
                    return report();
                }
                final long eocdOffset = locateEndOfCentralDirectory();
                if (eocdOffset < 0) {
                    archiveError(size, "end of central directory record not found (truncated archive?)");
                    return report();
                }
                if (!readEndOfCentralDirectory(eocdOffset)) {
                    return report();
                }
                readCentralDirectory();
                checkDuplicateNames();
                checkDataRegionOverlaps();
                if (options.isValidateContent()) {
                    validateContent();
                }
                return report();
            } catch (final EOFException e) {
                archiveError(size, "archive is truncated: " + e.getMessage());
                return report();
            } finally {
                // the preflight check must leave the channel exactly where it found it
                channel.position(savedPosition);
            }
        }

        private long locateEndOfCentralDirectory() throws IOException {
            final int window = (int) Math.min(size, MAX_EOCD_LENGTH);
            final byte[] tail = readAt(size - window, window);
            for (int i = window - MIN_EOCD_LENGTH; i >= 0; i--) {
                if (tail[i] == EOCD_SIG[0] && tail[i + 1] == EOCD_SIG[1] && tail[i + 2] == EOCD_SIG[2] && tail[i + 3] == EOCD_SIG[3]) {
                    return size - window + i;
                }
            }
            return -1;
        }

        /**
         * Parses the end of central directory record and, when needed, the ZIP64 records.
         *
         * @return false if a fatal problem made the central directory location unknown.
         */
        private boolean readEndOfCentralDirectory(final long eocdOffset) throws IOException {
            final byte[] eocd = readAt(eocdOffset, MIN_EOCD_LENGTH);
            final int diskNumber = ZipShort.getValue(eocd, 4);
            final int centralDirectoryDisk = ZipShort.getValue(eocd, 6);
            long entriesThisDisk = ZipShort.getValue(eocd, 8);
            long entriesTotal = ZipShort.getValue(eocd, 10);
            long centralDirectorySize = ZipLong.getValue(eocd, 12);
            centralDirectoryOffset = ZipLong.getValue(eocd, 16);
            final int commentLength = ZipShort.getValue(eocd, 20);
            final long recordEnd = eocdOffset + MIN_EOCD_LENGTH + commentLength;
            if (recordEnd > size) {
                archiveError(eocdOffset, "end of central directory record declares a comment of " + commentLength
                        + " bytes reaching past the end of the file (truncated archive)");
                return false;
            }
            if (recordEnd < size) {
                archiveWarning(recordEnd, (size - recordEnd) + " trailing byte(s) after the end of central directory record");
            }
            final boolean zip64Markers = entriesThisDisk == ZIP64_MAGIC_SHORT || entriesTotal == ZIP64_MAGIC_SHORT
                    || centralDirectorySize == ZIP64_MAGIC || centralDirectoryOffset == ZIP64_MAGIC;
            final boolean zip64LocatorPresent = hasZip64Locator(eocdOffset);
            if (zip64Markers || zip64LocatorPresent) {
                final long[] zip64 = readZip64EndOfCentralDirectory(eocdOffset);
                if (zip64 == null) {
                    return false;
                }
                if (zip64Markers) {
                    entriesThisDisk = zip64[0];
                    entriesTotal = zip64[1];
                    centralDirectorySize = zip64[2];
                    centralDirectoryOffset = zip64[3];
                }
            }
            if (diskNumber != 0 || centralDirectoryDisk != 0 || entriesThisDisk != entriesTotal) {
                archiveUnsupported("split (multi-volume) ZIP archives are not supported by the preflight check");
                return false;
            }
            try {
                centralDirectoryEnd = Math.addExact(centralDirectoryOffset, centralDirectorySize);
            } catch (final ArithmeticException e) {
                archiveError(eocdOffset, "ZIP64 length overflow: central directory offset " + centralDirectoryOffset + " plus size "
                        + centralDirectorySize + " does not fit into 63 bits");
                return false;
            }
            if (centralDirectoryEnd > eocdOffset) {
                archiveError(centralDirectoryOffset, "central directory region [" + centralDirectoryOffset + ", " + centralDirectoryEnd
                        + ") reaches into or beyond the end of central directory record at offset " + eocdOffset);
                return false;
            }
            if (centralDirectoryEnd < eocdOffset && !zip64Markers && !zip64LocatorPresent) {
                archiveWarning(centralDirectoryEnd,
                        (eocdOffset - centralDirectoryEnd) + " unaccounted byte(s) between the central directory and its end record");
            }
            if (entriesTotal > centralDirectorySize / CFH_LENGTH) {
                archiveError(centralDirectoryOffset, "central directory declares " + entriesTotal + " entries but only " + centralDirectorySize
                        + " bytes, each entry needs at least " + CFH_LENGTH + " bytes");
                return false;
            }
            if (entriesTotal == 0 && centralDirectorySize != 0) {
                archiveWarning(centralDirectoryOffset, "archive declares no entries but a central directory of " + centralDirectorySize + " bytes");
            }
            entryCount = entriesTotal;
            return true;
        }

        /**
         * Tests whether a ZIP64 end of central directory locator sits directly in front of the end of central directory record.
         */
        private boolean hasZip64Locator(final long eocdOffset) throws IOException {
            if (eocdOffset < ZIP64_EOCDL_LENGTH) {
                return false;
            }
            final byte[] locator = readAt(eocdOffset - ZIP64_EOCDL_LENGTH, ZIP64_EOCDL_LENGTH);
            return matches(locator, 0, ZIP64_EOCD_LOC_SIG);
        }

        /**
         * Parses the ZIP64 end of central directory locator and record.
         *
         * @return entriesThisDisk, entriesTotal, centralDirectorySize, centralDirectoryOffset or null on a fatal problem.
         */
        private long[] readZip64EndOfCentralDirectory(final long eocdOffset) throws IOException {
            if (eocdOffset < ZIP64_EOCDL_LENGTH) {
                archiveError(eocdOffset, "end of central directory record uses ZIP64 placeholder values but no ZIP64 locator can precede it");
                return null;
            }
            final byte[] locator = readAt(eocdOffset - ZIP64_EOCDL_LENGTH, ZIP64_EOCDL_LENGTH);
            if (!matches(locator, 0, ZIP64_EOCD_LOC_SIG)) {
                archiveError(eocdOffset - ZIP64_EOCDL_LENGTH,
                        "end of central directory record uses ZIP64 placeholder values but the ZIP64 end of central directory locator is missing");
                return null;
            }
            final long locatorDisk = ZipLong.getValue(locator, 4);
            final long zip64EocdOffset = ZipEightByteInteger.getLongValue(locator, 8);
            final long totalDisks = ZipLong.getValue(locator, 16);
            if (locatorDisk != 0 || totalDisks > 1) {
                archiveUnsupported("split (multi-volume) ZIP archives are not supported by the preflight check");
                return null;
            }
            if (zip64EocdOffset < 0 || zip64EocdOffset + ZIP64_EOCD_LENGTH > eocdOffset) {
                archiveError(eocdOffset - ZIP64_EOCDL_LENGTH,
                        "ZIP64 end of central directory locator points outside the archive (offset " + zip64EocdOffset + ")");
                return null;
            }
            final byte[] zip64Eocd = readAt(zip64EocdOffset, ZIP64_EOCD_LENGTH);
            if (!matches(zip64Eocd, 0, ZIP64_EOCD_SIG)) {
                archiveError(zip64EocdOffset, "bad ZIP64 end of central directory signature");
                return null;
            }
            final long diskNumber = ZipLong.getValue(zip64Eocd, 16);
            final long centralDirectoryDisk = ZipLong.getValue(zip64Eocd, 20);
            if (diskNumber != 0 || centralDirectoryDisk != 0) {
                archiveUnsupported("split (multi-volume) ZIP archives are not supported by the preflight check");
                return null;
            }
            final long entriesThisDisk = ZipEightByteInteger.getLongValue(zip64Eocd, 24);
            final long entriesTotal = ZipEightByteInteger.getLongValue(zip64Eocd, 32);
            final long centralDirectorySize = ZipEightByteInteger.getLongValue(zip64Eocd, 40);
            final long zip64CentralDirectoryOffset = ZipEightByteInteger.getLongValue(zip64Eocd, 48);
            if (entriesThisDisk < 0 || entriesTotal < 0 || centralDirectorySize < 0 || zip64CentralDirectoryOffset < 0) {
                archiveError(zip64EocdOffset, "ZIP64 length overflow: a value in the ZIP64 end of central directory record exceeds 63 bits");
                return null;
            }
            return new long[] { entriesThisDisk, entriesTotal, centralDirectorySize, zip64CentralDirectoryOffset };
        }

        private void readCentralDirectory() throws IOException {
            long position = centralDirectoryOffset;
            final long count = entryCount;
            for (int i = 0; i < count; i++) {
                final EntryInfo entry = new EntryInfo(i, position);
                entries.add(entry);
                if (position + CFH_LENGTH > centralDirectoryEnd || position + CFH_LENGTH > size) {
                    error(entry, position,
                            "central directory is truncated, entry record does not fit into the declared central directory region ["
                                    + centralDirectoryOffset + ", " + centralDirectoryEnd + ")");
                    entry.structurallySound = false;
                    return;
                }
                final byte[] cfh = readAt(position, CFH_LENGTH);
                if (!matches(cfh, 0, CFH_SIG)) {
                    error(entry, position, "bad central directory file header signature (central directory truncated or entry count forged)");
                    entry.structurallySound = false;
                    return;
                }
                entry.flags = ZipShort.getValue(cfh, 8);
                entry.method = ZipShort.getValue(cfh, 10);
                entry.crc = ZipLong.getValue(cfh, 16);
                entry.compressedSize = ZipLong.getValue(cfh, 20);
                entry.uncompressedSize = ZipLong.getValue(cfh, 24);
                final int nameLength = ZipShort.getValue(cfh, 28);
                final int extraLength = ZipShort.getValue(cfh, 30);
                final int commentLength = ZipShort.getValue(cfh, 32);
                final int diskStart = ZipShort.getValue(cfh, 34);
                entry.localHeaderOffset = ZipLong.getValue(cfh, 42);
                final long recordLength = CFH_LENGTH + (long) nameLength + extraLength + commentLength;
                if (position + recordLength > centralDirectoryEnd) {
                    error(entry, position,
                            "central directory entry crosses the declared end of the central directory at offset " + centralDirectoryEnd);
                    entry.structurallySound = false;
                    return;
                }
                entry.nameBytes = readAt(position + CFH_LENGTH, nameLength);
                entry.name = decodeName(entry.nameBytes, entry.flags);
                final byte[] extra = readAt(position + CFH_LENGTH + nameLength, extraLength);
                entry.encrypted = (entry.flags & (ENCRYPTION_FLAG | STRONG_ENCRYPTION_FLAG)) != 0;
                entry.dataDescriptor = (entry.flags & DATA_DESCRIPTOR_FLAG) != 0;
                if (diskStart != 0 && diskStart != ZIP64_MAGIC_SHORT) {
                    unsupported(entry, "entry starts on disk " + diskStart + ": split (multi-volume) archives are not supported");
                }
                if (entry.encrypted) {
                    unsupported(entry, "entry is encrypted: encrypted entries cannot be verified by the preflight check");
                }
                if (!readZip64ExtraField(entry, extra)) {
                    position += recordLength;
                    continue;
                }
                readLocalFileHeader(entry);
                if (entry.dataOffset >= 0) {
                    verifyDataDescriptor(entry);
                }
                position += recordLength;
            }
            if (position != centralDirectoryEnd) {
                archiveWarning(position,
                        "central directory contains " + (centralDirectoryEnd - position) + " trailing byte(s) not covered by any entry");
            }
        }

        /**
         * Resolves 0xFFFFFFFF placeholders from the ZIP64 extra field 0x0001.
         *
         * @return false if a fatal problem was found.
         */
        private boolean readZip64ExtraField(final EntryInfo entry, final byte[] extra) {
            final boolean needsSize = entry.uncompressedSize == ZIP64_MAGIC;
            final boolean needsCompressedSize = entry.compressedSize == ZIP64_MAGIC;
            final boolean needsOffset = entry.localHeaderOffset == ZIP64_MAGIC;
            if (!needsSize && !needsCompressedSize && !needsOffset) {
                return true;
            }
            entry.zip64 = true;
            int offset = 0;
            while (offset + 4 <= extra.length) {
                final int headerId = ZipShort.getValue(extra, offset);
                final int dataLength = ZipShort.getValue(extra, offset + 2);
                if (offset + 4 + dataLength > extra.length) {
                    error(entry, entry.centralHeaderOffset, "malformed extra field: declared length exceeds the extra field");
                    entry.structurallySound = false;
                    return false;
                }
                if (headerId == ZIP64_EXTRA_FIELD_ID) {
                    int pointer = offset + 4;
                    final int end = pointer + dataLength;
                    if (needsSize) {
                        if (pointer + 8 > end) {
                            return zip64Missing(entry, "uncompressed size");
                        }
                        entry.uncompressedSize = ZipEightByteInteger.getLongValue(extra, pointer);
                        pointer += 8;
                    }
                    if (needsCompressedSize) {
                        if (pointer + 8 > end) {
                            return zip64Missing(entry, "compressed size");
                        }
                        entry.compressedSize = ZipEightByteInteger.getLongValue(extra, pointer);
                        pointer += 8;
                    }
                    if (needsOffset) {
                        if (pointer + 8 > end) {
                            return zip64Missing(entry, "local header offset");
                        }
                        entry.localHeaderOffset = ZipEightByteInteger.getLongValue(extra, pointer);
                        pointer += 8;
                    }
                    if (entry.uncompressedSize < 0 || entry.compressedSize < 0 || entry.localHeaderOffset < 0) {
                        error(entry, entry.centralHeaderOffset, "ZIP64 length overflow: a ZIP64 extra field value exceeds 63 bits");
                        entry.structurallySound = false;
                        return false;
                    }
                    return true;
                }
                offset += 4 + dataLength;
            }
            return zip64Missing(entry, "values");
        }

        private boolean zip64Missing(final EntryInfo entry, final String what) {
            error(entry, entry.centralHeaderOffset,
                    "central directory uses 0xFFFFFFFF ZIP64 placeholder values but the ZIP64 extra field (0x0001) with the " + what
                            + " is missing");
            entry.structurallySound = false;
            return false;
        }

        private void readLocalFileHeader(final EntryInfo entry) throws IOException {
            if (entry.localHeaderOffset < 0 || entry.localHeaderOffset + LFH_LENGTH > centralDirectoryOffset) {
                error(entry, Math.max(entry.localHeaderOffset, 0),
                        "local file header offset " + entry.localHeaderOffset
                                + " points into or beyond the central directory region starting at offset " + centralDirectoryOffset);
                entry.structurallySound = false;
                entry.localHeaderOffset = -1;
                return;
            }
            final byte[] lfh = readAt(entry.localHeaderOffset, LFH_LENGTH);
            if (!matches(lfh, 0, LFH_SIG)) {
                error(entry, entry.localHeaderOffset, "bad local file header signature");
                entry.structurallySound = false;
                return;
            }
            final int localFlags = ZipShort.getValue(lfh, 6);
            final int localMethod = ZipShort.getValue(lfh, 8);
            final int localNameLength = ZipShort.getValue(lfh, 26);
            final int localExtraLength = ZipShort.getValue(lfh, 28);
            if (localMethod != entry.method) {
                error(entry, entry.localHeaderOffset, "compression method mismatch: central directory declares " + entry.method
                        + " but the local file header declares " + localMethod);
                entry.structurallySound = false;
            }
            final int keyFlags = ENCRYPTION_FLAG | DATA_DESCRIPTOR_FLAG | UTF8_NAMES_FLAG;
            if ((localFlags & keyFlags) != (entry.flags & keyFlags)) {
                error(entry, entry.localHeaderOffset,
                        String.format("general purpose flag mismatch: central directory declares 0x%04X but the local file header declares 0x%04X",
                                Integer.valueOf(entry.flags), Integer.valueOf(localFlags)));
                entry.structurallySound = false;
            }
            if (entry.localHeaderOffset + LFH_LENGTH + localNameLength > centralDirectoryOffset) {
                error(entry, entry.localHeaderOffset, "local file header name crosses into the central directory region");
                entry.structurallySound = false;
                return;
            }
            final byte[] localName = readAt(entry.localHeaderOffset + LFH_LENGTH, localNameLength);
            if (!Arrays.equals(localName, entry.nameBytes)) {
                error(entry, entry.localHeaderOffset, "entry name mismatch: central directory names it '" + entry.name
                        + "' but the local file header names it '" + decodeName(localName, localFlags) + "'");
                entry.structurallySound = false;
            }
            try {
                entry.dataOffset = Math.addExact(Math.addExact(entry.localHeaderOffset, LFH_LENGTH + (long) localNameLength), localExtraLength);
                entry.dataEnd = Math.addExact(entry.dataOffset, entry.compressedSize);
            } catch (final ArithmeticException e) {
                error(entry, entry.localHeaderOffset,
                        "ZIP64 length overflow: local header offset plus compressed size " + entry.compressedSize + " does not fit into 63 bits");
                entry.structurallySound = false;
                entry.dataOffset = -1;
                return;
            }
            if (entry.dataEnd > size) {
                error(entry, entry.dataOffset, "entry declares " + entry.compressedSize + " compressed bytes reaching past the end of the file at "
                        + size + " (truncated archive)");
                entry.structurallySound = false;
            }
        }

        /**
         * Verifies the data descriptor that follows the entry data when general purpose flag bit 3 is set. Legal deferred CRC and size values are
         * accepted in both the ZIP32 (12/16 bytes) and ZIP64 (20/24 bytes) forms.
         */
        private void verifyDataDescriptor(final EntryInfo entry) throws IOException {
            if (!entry.dataDescriptor || entry.dataEnd > centralDirectoryOffset) {
                return;
            }
            final int valueLength = entry.zip64 ? 20 : 12;
            final long available = centralDirectoryOffset - entry.dataEnd;
            if (available < valueLength) {
                error(entry, entry.dataEnd, "data descriptor (flag bit 3 is set) is missing or truncated: only " + available
                        + " byte(s) left before the central directory, " + valueLength + " needed");
                entry.structurallySound = false;
                return;
            }
            final byte[] descriptor = readAt(entry.dataEnd, (int) Math.min(available, (long) valueLength + DD_SIG.length));
            // try with and without the optional data descriptor signature
            for (int signatureLength = DD_SIG.length; signatureLength >= 0; signatureLength -= DD_SIG.length) {
                if (signatureLength > 0 && !matches(descriptor, 0, DD_SIG)) {
                    continue;
                }
                if (descriptor.length < signatureLength + valueLength) {
                    continue;
                }
                final long crc = ZipLong.getValue(descriptor, signatureLength);
                final long compressedSize;
                final long uncompressedSize;
                if (entry.zip64) {
                    compressedSize = ZipEightByteInteger.getLongValue(descriptor, signatureLength + 4);
                    uncompressedSize = ZipEightByteInteger.getLongValue(descriptor, signatureLength + 12);
                } else {
                    compressedSize = ZipLong.getValue(descriptor, signatureLength + 4);
                    uncompressedSize = ZipLong.getValue(descriptor, signatureLength + 8);
                }
                if (crc == entry.crc && compressedSize == entry.compressedSize && uncompressedSize == entry.uncompressedSize) {
                    return; // legal deferred CRC/length values
                }
            }
            error(entry, entry.dataEnd,
                    "data descriptor values do not match the central directory (expected CRC 0x" + Long.toHexString(entry.crc)
                            + ", compressed size " + entry.compressedSize + ", uncompressed size " + entry.uncompressedSize + ")");
            entry.structurallySound = false;
        }

        private void validateContent() throws IOException {
            long totalUncompressed = 0;
            for (final EntryInfo entry : entries) {
                if (stopContentValidation) {
                    return;
                }
                if (!entry.structurallySound || entry.encrypted) {
                    continue;
                }
                final long produced;
                if (entry.method == ZipMethod.STORED.getCode()) {
                    produced = validateStored(entry, totalUncompressed);
                } else if (entry.method == ZipMethod.DEFLATED.getCode()) {
                    produced = validateDeflated(entry, totalUncompressed);
                } else if (ZipMethod.getMethodByCode(entry.method) != null) {
                    diagnostics.add(new ZipPreflightDiagnostic(ZipPreflightSeverity.UNVERIFIED, entry.index, entry.name, entry.dataOffset,
                            "compression method " + ZipMethod.getMethodByCode(entry.method)
                                    + " is supported by Commons Compress but not verified by the preflight content validation"));
                    continue;
                } else {
                    unsupported(entry, "unknown compression method " + entry.method);
                    continue;
                }
                if (stopContentValidation) {
                    return;
                }
                totalUncompressed += produced;
            }
        }

        /**
         * Tests the per-entry and global uncompressed byte budgets against the actually produced number of bytes.
         *
         * @return true if a budget was exceeded and validation must stop immediately.
         */
        private boolean budgetExceeded(final EntryInfo entry, final long totalSoFar, final long produced) {
            final long perEntry = options.getMaxUncompressedBytesPerEntry();
            if (perEntry != ZipPreflightOptions.UNLIMITED && produced > perEntry) {
                error(entry, entry.dataOffset, "entry produced more than the allowed " + perEntry
                        + " uncompressed bytes (actual output is counted, header sizes are not trusted); content validation stopped");
                stopContentValidation = true;
                return true;
            }
            final long total = options.getMaxUncompressedBytesTotal();
            if (total != ZipPreflightOptions.UNLIMITED && totalSoFar + produced > total) {
                error(entry, entry.dataOffset, "archive produced more than the allowed total of " + total
                        + " uncompressed bytes (actual output is counted, header sizes are not trusted); content validation stopped");
                stopContentValidation = true;
                return true;
            }
            return false;
        }

        private long validateStored(final EntryInfo entry, final long totalSoFar) throws IOException {
            if (entry.compressedSize != entry.uncompressedSize) {
                error(entry, entry.dataOffset,
                        "STORED entry declares compressed size " + entry.compressedSize + " but uncompressed size " + entry.uncompressedSize);
                entry.structurallySound = false;
                return 0;
            }
            final CRC32 crc = new CRC32();
            long position = entry.dataOffset;
            long remaining = entry.compressedSize;
            long produced = 0;
            while (remaining > 0) {
                final int chunk = (int) Math.min(BUFFER_SIZE, remaining);
                final byte[] buffer = readAt(position, chunk);
                crc.update(buffer, 0, chunk);
                position += chunk;
                remaining -= chunk;
                produced += chunk;
                if (budgetExceeded(entry, totalSoFar, produced)) {
                    return produced;
                }
            }
            verifyCrcAndSize(entry, crc.getValue(), produced);
            return produced;
        }

        private long validateDeflated(final EntryInfo entry, final long totalSoFar) throws IOException {
            final CRC32 crc = new CRC32();
            final Inflater inflater = new Inflater(true);
            try {
                final byte[] output = new byte[BUFFER_SIZE];
                long position = entry.dataOffset;
                long remaining = entry.compressedSize;
                long produced = 0;
                boolean paddingByteFed = false;
                while (!inflater.finished()) {
                    if (inflater.needsInput()) {
                        if (remaining > 0) {
                            final int chunk = (int) Math.min(BUFFER_SIZE, remaining);
                            final byte[] input = readAt(position, chunk);
                            inflater.setInput(input, 0, chunk);
                            position += chunk;
                            remaining -= chunk;
                        } else if (!paddingByteFed) {
                            // Inflater with nowrap=true has this odd contract for a zero padding byte following the data stream
                            paddingByteFed = true;
                            inflater.setInput(ONE_ZERO_BYTE);
                        } else {
                            error(entry, entry.dataOffset, "deflate stream is not finished after " + entry.compressedSize
                                    + " compressed bytes (truncated stream)");
                            entry.structurallySound = false;
                            return produced;
                        }
                    }
                    final int count;
                    try {
                        count = inflater.inflate(output);
                    } catch (final DataFormatException e) {
                        error(entry, entry.dataOffset, "deflate stream is corrupt: " + e.getMessage());
                        entry.structurallySound = false;
                        return produced;
                    }
                    if (count > 0) {
                        crc.update(output, 0, count);
                        produced += count;
                        if (budgetExceeded(entry, totalSoFar, produced)) {
                            return produced;
                        }
                    } else if (inflater.needsDictionary()) {
                        error(entry, entry.dataOffset, "deflate stream requests a preset dictionary");
                        entry.structurallySound = false;
                        return produced;
                    }
                }
                verifyCrcAndSize(entry, crc.getValue(), produced);
                return produced;
            } finally {
                inflater.end();
            }
        }

        private void verifyCrcAndSize(final EntryInfo entry, final long actualCrc, final long produced) {
            if (produced != entry.uncompressedSize) {
                error(entry, entry.dataOffset, "header declares " + entry.uncompressedSize + " uncompressed bytes but the entry actually produced "
                        + produced);
                entry.structurallySound = false;
            }
            if (actualCrc != entry.crc) {
                error(entry, entry.dataOffset, "CRC-32 mismatch: header declares 0x" + Long.toHexString(entry.crc)
                        + " but the actual content hashes to 0x" + Long.toHexString(actualCrc));
                entry.structurallySound = false;
            }
        }

        private void unsupported(final EntryInfo entry, final String message) {
            diagnostics.add(new ZipPreflightDiagnostic(ZipPreflightSeverity.UNSUPPORTED, entry.index, entry.name,
                    ZipPreflightDiagnostic.NO_OFFSET, message));
        }

        private void warning(final EntryInfo entry, final long offset, final String message) {
            diagnostics.add(new ZipPreflightDiagnostic(ZipPreflightSeverity.WARNING, entry.index, entry.name, offset, message));
        }

        private byte[] readAt(final long offset, final int length) throws IOException {
            final byte[] buffer = new byte[length];
            channel.position(offset);
            final ByteBuffer byteBuffer = ByteBuffer.wrap(buffer);
            while (byteBuffer.hasRemaining()) {
                if (channel.read(byteBuffer) < 0) {
                    throw new EOFException("needed " + length + " byte(s) at offset " + offset + " but the archive ends at " + size);
                }
            }
            return buffer;
        }

        private ZipPreflightReport report() {
            return new ZipPreflightReport(diagnostics, entries.size());
        }
    }

    private static final int MIN_EOCD_LENGTH = 22;

    private static final int MAX_EOCD_LENGTH = MIN_EOCD_LENGTH + 0xFFFF;

    private static final int CFH_LENGTH = 46;

    private static final int LFH_LENGTH = 30;

    private static final int ZIP64_EOCD_LENGTH = 56;

    private static final int ZIP64_EOCDL_LENGTH = 20;

    private static final int BUFFER_SIZE = 8192;

    private static final int ZIP64_MAGIC_SHORT = 0xFFFF;

    private static final long ZIP64_MAGIC = 0xFFFFFFFFL;

    private static final int ZIP64_EXTRA_FIELD_ID = 0x0001;

    private static final int ENCRYPTION_FLAG = 1;

    private static final int DATA_DESCRIPTOR_FLAG = 1 << 3;

    private static final int STRONG_ENCRYPTION_FLAG = 1 << 6;

    private static final int UTF8_NAMES_FLAG = 1 << 11;

    private static final byte[] LFH_SIG = ZipLong.LFH_SIG.getBytes();

    private static final byte[] CFH_SIG = ZipLong.CFH_SIG.getBytes();

    private static final byte[] DD_SIG = ZipLong.DD_SIG.getBytes();

    private static final byte[] EOCD_SIG = ZipLong.getBytes(0X06054B50L);

    private static final byte[] ZIP64_EOCD_SIG = ZipLong.getBytes(0X06064B50L);

    private static final byte[] ZIP64_EOCD_LOC_SIG = ZipLong.getBytes(0X07064B50L);

    private static final byte[] ONE_ZERO_BYTE = new byte[1];

    /**
     * Runs a structural preflight check over the ZIP archive read from the given channel using the default options (no content validation).
     * <p>
     * The position of the channel is restored before this method returns. Nothing is written and no external tool is invoked.
     * </p>
     *
     * @param channel the channel to read the archive from, must be open for reading.
     * @return the preflight report, never {@code null}.
     * @throws IOException if reading the archive fails.
     */
    public static ZipPreflightReport check(final SeekableByteChannel channel) throws IOException {
        return check(channel, ZipPreflightOptions.defaultOptions());
    }

    /**
     * Runs a preflight check over the ZIP archive read from the given channel.
     * <p>
     * The position of the channel is restored before this method returns. Nothing is written and no external tool is invoked.
     * </p>
     *
     * @param channel the channel to read the archive from, must be open for reading.
     * @param options the check options.
     * @return the preflight report, never {@code null}.
     * @throws IOException if reading the archive fails.
     */
    public static ZipPreflightReport check(final SeekableByteChannel channel, final ZipPreflightOptions options) throws IOException {
        Objects.requireNonNull(channel, "channel");
        Objects.requireNonNull(options, "options");
        return new Checker(channel, options).run();
    }

    /**
     * Runs a structural preflight check over the ZIP archive stored in the given file using the default options (no content validation).
     *
     * @param path the archive file.
     * @return the preflight report, never {@code null}.
     * @throws IOException if reading the archive fails.
     */
    public static ZipPreflightReport check(final Path path) throws IOException {
        return check(path, ZipPreflightOptions.defaultOptions());
    }

    /**
     * Runs a preflight check over the ZIP archive stored in the given file.
     *
     * @param path the archive file.
     * @param options the check options.
     * @return the preflight report, never {@code null}.
     * @throws IOException if reading the archive fails.
     */
    public static ZipPreflightReport check(final Path path, final ZipPreflightOptions options) throws IOException {
        try (SeekableByteChannel channel = Files.newByteChannel(path, StandardOpenOption.READ)) {
            return check(channel, options);
        }
    }

    private static String decodeName(final byte[] nameBytes, final int flags) {
        if ((flags & UTF8_NAMES_FLAG) != 0) {
            return new String(nameBytes, StandardCharsets.UTF_8);
        }
        return new String(nameBytes, StandardCharsets.ISO_8859_1);
    }

    private static boolean matches(final byte[] data, final int offset, final byte[] signature) {
        for (int i = 0; i < signature.length; i++) {
            if (data[offset + i] != signature[i]) {
                return false;
            }
        }
        return true;
    }

    private ZipPreflight() {
        // static utility
    }
}
