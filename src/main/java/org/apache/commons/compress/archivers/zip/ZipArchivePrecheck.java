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

import java.io.IOException;
import java.io.InputStream;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.zip.CRC32;
import java.util.zip.ZipException;

import org.apache.commons.compress.archivers.zip.ZipPrecheckReport.Severity;

/**
 * Read-only structural pre-flight check for ZIP archives, to be run before an archive is extracted or copied.
 * <p>
 * The check parses the (ZIP64) end of central directory records itself and cross-verifies, for every entry of the central directory, the matching local
 * file header: entry name, compression method and the key general purpose flags (encryption, data descriptor) must agree. From the local header layout
 * and the central directory lengths the real compressed-data range of every entry is computed and checked for
 * </p>
 * <ul>
 * <li>overlaps with the data range of any other entry,</li>
 * <li>ranges crossing into the central directory region,</li>
 * <li>truncation (ranges extending beyond the end of the channel),</li>
 * <li>ZIP64 length overflows (placeholder values without a ZIP64 extra field, negative or overflowing ZIP64 lengths).</li>
 * </ul>
 * <p>
 * Entries using a data descriptor (general purpose flag bit 3) legally carry zero CRC/length values in their local header; those deferred values are
 * verified against the trailing data descriptor instead and never reported as mismatches. Both the classic and the ZIP64 descriptor layout are
 * recognized, with and without the optional descriptor signature.
 * </p>
 * <p>
 * Encrypted entries and multi-volume (split) archives are reported as {@link Severity#UNSUPPORTED} - the precheck does not guess that they passed.
 * </p>
 * <p>
 * The check never writes entry data out and restores the position of the channel before returning. An optional second phase,
 * {@link #verifyExtraction(SeekableByteChannel, long, long)}, decompresses every entry while counting the <em>actual</em> uncompressed bytes (the
 * lengths stored in the headers are not trusted), enforces a per-entry and a global byte budget - aborting immediately when a budget is exceeded - and
 * verifies the CRC of the decompressed data against the central directory.
 * </p>
 * <p>
 * A small command line interface is available through {@link #main(String[])}:
 * </p>
 *
 * <pre>
 * java org.apache.commons.compress.archivers.zip.ZipArchivePrecheck archive.zip [--verify] [--entry-budget N] [--total-budget N]
 * </pre>
 *
 * @since 1.29.0
 */
public final class ZipArchivePrecheck {

    /**
     * A {@link SeekableByteChannel} decorator whose {@link #close()} is a no-op, so that a {@link ZipFile} built on top of a caller-owned channel does
     * not close that channel.
     */
    private static final class CloseShieldSeekableByteChannel implements SeekableByteChannel {

        private final SeekableByteChannel delegate;

        CloseShieldSeekableByteChannel(final SeekableByteChannel delegate) {
            this.delegate = delegate;
        }

        @Override
        public void close() {
            // do not close the caller-owned channel
        }

        @Override
        public boolean isOpen() {
            return delegate.isOpen();
        }

        @Override
        public long position() throws IOException {
            return delegate.position();
        }

        @Override
        public SeekableByteChannel position(final long newPosition) throws IOException {
            return delegate.position(newPosition);
        }

        @Override
        public int read(final ByteBuffer dst) throws IOException {
            return delegate.read(dst);
        }

        @Override
        public long size() throws IOException {
            return delegate.size();
        }

        @Override
        public SeekableByteChannel truncate(final long size) throws IOException {
            return delegate.truncate(size);
        }

        @Override
        public int write(final ByteBuffer src) throws IOException {
            return delegate.write(src);
        }
    }

    /** Per-entry view of the structural check. */
    private static final class EntryInfo {

        int index = -1;
        long centralOffset;
        String name = "";
        int flags;
        int method;
        long crc;
        long csize;
        long usize;
        long localHeaderOffset;
        boolean zip64;
        boolean encrypted;
        long dataStart = -1;
        long dataEnd = -1;
        long occupiedEnd = -1;
    }

    private static final long LFH_SIG = 0x04034b50L;
    private static final long CFH_SIG = 0x02014b50L;
    private static final long DD_SIG = 0x08074b50L;
    private static final long ZIP64_EOCD_SIG = 0x06064b50L;
    private static final long ZIP64_EOCD_LOCATOR_SIG = 0x07064b50L;

    private static final int EOCD_LEN = 22;
    private static final int ZIP64_EOCD_LOCATOR_LEN = 20;
    private static final int ZIP64_EOCD_LEN = 56;
    private static final int CFH_LEN = 46;
    private static final int LFH_LEN = 30;

    /** General purpose flag bit 0: encryption. */
    private static final int GPBF_ENCRYPTION = 1;

    /** General purpose flag bit 3: CRC and sizes are deferred to a data descriptor. */
    private static final int GPBF_DATA_DESCRIPTOR = 1 << 3;

    /** General purpose flag bit 11: UTF-8 encoded names (EFS). */
    private static final int GPBF_UTF8 = 1 << 11;

    /** General purpose flag bit 13: local header offsets in the central directory are masked. */
    private static final int GPBF_MASKED_LOCAL_OFFSETS = 1 << 13;

    /** End of central directory plus the longest possible archive comment, plus some slack. */
    private static final int MAX_EOCD_SEARCH_WINDOW = EOCD_LEN + 0xFFFF + 1024;

    private static final Charset FALLBACK_NAME_CHARSET = loadCharset("IBM437");

    /**
     * Performs the structural pre-flight check of the ZIP archive at the given path.
     *
     * @param path the path of the ZIP archive.
     * @return the precheck report, never null.
     * @throws IOException if the archive cannot be read.
     */
    public static ZipPrecheckReport check(final Path path) throws IOException {
        try (SeekableByteChannel channel = Files.newByteChannel(path, StandardOpenOption.READ)) {
            return check(channel);
        }
    }

    /**
     * Performs the structural pre-flight check of the ZIP archive readable from the given channel. The channel is only read, never written, and its
     * position is restored before this method returns.
     *
     * @param channel the channel to read the archive from.
     * @return the precheck report, never null.
     * @throws IOException if the archive cannot be read.
     */
    public static ZipPrecheckReport check(final SeekableByteChannel channel) throws IOException {
        Objects.requireNonNull(channel, "channel");
        final long savedPosition = channel.position();
        final ZipPrecheckReport report = new ZipPrecheckReport();
        try {
            checkStructure(channel, report);
        } finally {
            channel.position(savedPosition);
        }
        return report;
    }

    /**
     * Command line interface for the precheck. Prints the structural report and, when {@code --verify} is given, additionally runs the extraction
     * validation phase with the given byte budgets. Exits with status 1 when any structural error is found.
     *
     * @param args {@code <archive.zip> [--verify] [--entry-budget N] [--total-budget N]}
     * @throws IOException if the archive cannot be read.
     */
    public static void main(final String[] args) throws IOException {
        if (args.length < 1) {
            System.out.println("Usage: ZipArchivePrecheck <archive.zip> [--verify] [--entry-budget N] [--total-budget N]");
            System.out.println("Performs a read-only structural preflight check of a ZIP archive before extraction.");
            return;
        }
        final Path path = Paths.get(args[0]);
        boolean verify = false;
        long perEntryBudget = -1;
        long totalBudget = -1;
        for (int i = 1; i < args.length; i++) {
            switch (args[i]) {
            case "--verify":
                verify = true;
                break;
            case "--entry-budget":
                perEntryBudget = Long.parseLong(args[++i]);
                break;
            case "--total-budget":
                totalBudget = Long.parseLong(args[++i]);
                break;
            default:
                System.err.println("Ignoring unknown option: " + args[i]);
            }
        }
        final ZipPrecheckReport report = check(path);
        System.out.println("== structural precheck of " + path + " ==");
        System.out.println(report);
        boolean failed = report.hasErrors();
        if (verify) {
            final ZipPrecheckReport extractionReport = verifyExtraction(path, perEntryBudget, totalBudget);
            System.out.println("== extraction validation of " + path + " (per-entry budget " + perEntryBudget + ", total budget " + totalBudget
                    + ") ==");
            System.out.println(extractionReport);
            failed |= extractionReport.hasErrors();
        }
        if (failed) {
            System.exit(1);
        }
    }

    /**
     * Runs the optional extraction validation phase on the ZIP archive at the given path: every entry is decompressed while the actual output bytes are
     * counted against the budgets and the CRC of the decompressed data is verified against the central directory.
     *
     * @param path           the path of the ZIP archive.
     * @param perEntryBudget maximum uncompressed bytes allowed per entry, or a negative value for no limit.
     * @param totalBudget    maximum uncompressed bytes allowed for the whole archive, or a negative value for no limit.
     * @return the validation report, never null.
     * @throws IOException if the archive cannot be read.
     */
    public static ZipPrecheckReport verifyExtraction(final Path path, final long perEntryBudget, final long totalBudget) throws IOException {
        try (SeekableByteChannel channel = Files.newByteChannel(path, StandardOpenOption.READ)) {
            return verifyExtraction(channel, perEntryBudget, totalBudget);
        }
    }

    /**
     * Runs the optional extraction validation phase: every entry is decompressed while the actual output bytes are counted against the budgets and the
     * CRC of the decompressed data is verified against the central directory. The lengths stored in the archive headers are not trusted; when a budget
     * is exceeded the validation stops immediately. The position of the channel is restored before this method returns and the channel is not closed.
     *
     * @param channel        the channel to read the archive from.
     * @param perEntryBudget maximum uncompressed bytes allowed per entry, or a negative value for no limit.
     * @param totalBudget    maximum uncompressed bytes allowed for the whole archive, or a negative value for no limit.
     * @return the validation report, never null.
     * @throws IOException if the archive cannot be read.
     */
    public static ZipPrecheckReport verifyExtraction(final SeekableByteChannel channel, final long perEntryBudget, final long totalBudget)
            throws IOException {
        Objects.requireNonNull(channel, "channel");
        final long savedPosition = channel.position();
        final ZipPrecheckReport report = new ZipPrecheckReport();
        try {
            try (ZipFile zipFile = ZipFile.builder().setSeekableByteChannel(new CloseShieldSeekableByteChannel(channel)).get()) {
                verifyEntries(zipFile, perEntryBudget, totalBudget, report);
            } catch (final ZipException e) {
                report.addIssue(Severity.ERROR, -1, -1, "archive cannot be read for extraction validation: " + e.getMessage());
            }
        } finally {
            channel.position(savedPosition);
        }
        return report;
    }

    private static void applyZip64ExtraField(final ByteBuffer extra, final EntryInfo info, final boolean needsDiskStart,
            final ZipPrecheckReport report) {
        final boolean needsUsize = info.usize == 0xFFFFFFFFL;
        final boolean needsCsize = info.csize == 0xFFFFFFFFL;
        final boolean needsOffset = info.localHeaderOffset == 0xFFFFFFFFL;
        final ByteBuffer zip64 = findExtraField(extra, 0x0001);
        info.zip64 = zip64 != null;
        if (!needsUsize && !needsCsize && !needsOffset && !needsDiskStart) {
            return;
        }
        if (zip64 == null) {
            report.addIssue(Severity.ERROR, info.index, info.centralOffset, "entry '" + info.name
                    + "' uses ZIP64 placeholder values (0xffffffff) in the central directory but carries no ZIP64 extra field - length overflow");
            return;
        }
        try {
            if (needsUsize) {
                info.usize = zip64.getLong();
            }
            if (needsCsize) {
                info.csize = zip64.getLong();
            }
            if (needsOffset) {
                info.localHeaderOffset = zip64.getLong();
            }
        } catch (final BufferUnderflowException e) {
            report.addIssue(Severity.ERROR, info.index, info.centralOffset,
                    "ZIP64 extra field of entry '" + info.name + "' is too short for the placeholder values used - length overflow");
            return;
        }
        if (info.usize < 0 || info.csize < 0 || info.localHeaderOffset < 0) {
            report.addIssue(Severity.ERROR, info.index, info.centralOffset,
                    "ZIP64 extra field of entry '" + info.name + "' contains negative (overflowing) lengths");
        }
    }

    private static void checkDataDescriptor(final SeekableByteChannel channel, final long size, final long cdOffset, final EntryInfo info,
            final ZipPrecheckReport report) throws IOException {
        final long dataEnd = info.dataEnd;
        if (dataEnd < 0) {
            return;
        }
        boolean hasSignature = false;
        if (dataEnd + 4 <= size) {
            hasSignature = getU32(readAt(channel, dataEnd, 4), 0) == DD_SIG;
        }
        final int sizeLength = info.zip64 ? 8 : 4;
        final long descriptorLength = (hasSignature ? 4L : 0) + 4 + 2L * sizeLength;
        if (dataEnd + descriptorLength > size) {
            report.addIssue(Severity.ERROR, info.index, dataEnd,
                    "data descriptor of entry '" + info.name + "' at offset " + dataEnd + " is truncated (channel size is " + size + " bytes)");
            info.occupiedEnd = dataEnd;
            return;
        }
        final ByteBuffer descriptor = readAt(channel, dataEnd, (int) descriptorLength);
        int p = hasSignature ? 4 : 0;
        final long ddCrc = getU32(descriptor, p);
        p += 4;
        final long ddCsize;
        final long ddUsize;
        if (info.zip64) {
            ddCsize = descriptor.getLong(p);
            ddUsize = descriptor.getLong(p + 8);
        } else {
            ddCsize = getU32(descriptor, p);
            ddUsize = getU32(descriptor, p + 4);
        }
        if (ddCsize != info.csize || ddUsize != info.usize) {
            report.addIssue(Severity.ERROR, info.index, dataEnd, "data descriptor of entry '" + info.name + "' declares compressed=" + ddCsize
                    + " uncompressed=" + ddUsize + " but the central directory declares compressed=" + info.csize + " uncompressed=" + info.usize
                    + " - forged length");
        }
        if (ddCrc != info.crc) {
            report.addIssue(Severity.WARNING, info.index, dataEnd, String
                    .format("data descriptor CRC 0x%08x of entry '%s' disagrees with the central directory CRC 0x%08x", ddCrc, info.name, info.crc));
        }
        info.occupiedEnd = dataEnd + descriptorLength;
        if (info.occupiedEnd > cdOffset) {
            report.addIssue(Severity.ERROR, info.index, dataEnd, "data descriptor of entry '" + info.name + "' ends at offset " + info.occupiedEnd
                    + ", crossing into the central directory region starting at offset " + cdOffset);
        }
    }

    private static void checkDuplicateNames(final List<EntryInfo> entries, final ZipPrecheckReport report) {
        final Map<String, Integer> firstSeen = new HashMap<>();
        for (final EntryInfo info : entries) {
            final Integer previous = firstSeen.putIfAbsent(info.name, info.index);
            if (previous != null) {
                report.addIssue(Severity.WARNING, info.index, info.centralOffset, "duplicate entry name '" + info.name + "' (also used by entry #"
                        + previous + ") - a naming clash, not overlapping entry data");
            }
        }
    }

    private static void checkLocalHeaderAndData(final SeekableByteChannel channel, final long size, final long cdOffset, final EntryInfo info,
            final ZipPrecheckReport report) throws IOException {
        if (info.encrypted) {
            report.addIssue(Severity.UNSUPPORTED, info.index, info.localHeaderOffset,
                    "entry '" + info.name + "' is encrypted - its content cannot be verified");
        }
        if ((info.flags & GPBF_MASKED_LOCAL_OFFSETS) != 0) {
            report.addIssue(Severity.UNSUPPORTED, info.index, info.centralOffset, "entry '" + info.name
                    + "' uses masked local header offsets (central directory encryption) - the local header cannot be verified");
            return;
        }
        final long lho = info.localHeaderOffset;
        if (lho + LFH_LEN > size) {
            report.addIssue(Severity.ERROR, info.index, lho, "local file header of entry '" + info.name + "' at offset " + lho
                    + " is truncated (channel size is " + size + " bytes)");
            return;
        }
        final ByteBuffer local = readAt(channel, lho, LFH_LEN);
        final long sig = getU32(local, 0);
        if (sig != LFH_SIG) {
            report.addIssue(Severity.ERROR, info.index, lho, String.format(
                    "no local file header signature at offset %d (found 0x%08x) - the central directory offset of entry '%s' is wrong", lho, sig,
                    info.name));
            return;
        }
        final int localFlags = getU16(local, 6);
        final int localMethod = getU16(local, 8);
        final long localCrc = getU32(local, 14);
        final long localCsize = getU32(local, 18);
        final long localUsize = getU32(local, 22);
        final int localNameLen = getU16(local, 26);
        final int localExtraLen = getU16(local, 28);
        if (localMethod != info.method) {
            report.addIssue(Severity.ERROR, info.index, lho, "compression method mismatch for entry '" + info.name + "': local header declares "
                    + localMethod + " but the central directory declares " + info.method);
        }
        if ((localFlags & GPBF_ENCRYPTION) != (info.flags & GPBF_ENCRYPTION)
                || (localFlags & GPBF_DATA_DESCRIPTOR) != (info.flags & GPBF_DATA_DESCRIPTOR)) {
            report.addIssue(Severity.ERROR, info.index, lho, String.format(
                    "general purpose flag mismatch for entry '%s': local header 0x%04x vs central directory 0x%04x (encryption/data-descriptor bits)",
                    info.name, localFlags, info.flags));
        }
        if (lho + LFH_LEN + (long) localNameLen + localExtraLen > size) {
            report.addIssue(Severity.ERROR, info.index, lho, "local file header of entry '" + info.name + "' at offset " + lho + " is truncated");
            return;
        }
        final String localName = decodeName(readAt(channel, lho + LFH_LEN, localNameLen), localFlags);
        if (!localName.equals(info.name)) {
            report.addIssue(Severity.ERROR, info.index, lho,
                    "entry name mismatch at offset " + lho + ": local header '" + localName + "' vs central directory '" + info.name + "'");
        }
        final boolean deferred = (info.flags & GPBF_DATA_DESCRIPTOR) != 0;
        if (!deferred) {
            if (localCsize != info.csize || localUsize != info.usize) {
                report.addIssue(Severity.ERROR, info.index, lho, "length mismatch for entry '" + info.name + "': local header declares compressed="
                        + localCsize + " uncompressed=" + localUsize + " but the central directory declares compressed=" + info.csize
                        + " uncompressed=" + info.usize);
            }
            if (localCrc != info.crc) {
                report.addIssue(Severity.ERROR, info.index, lho,
                        String.format("CRC mismatch for entry '%s': local header 0x%08x vs central directory 0x%08x", info.name, localCrc, info.crc));
            }
        } else {
            // with bit 3 set the local header legally carries zeros - or 0xffffffff ZIP64 placeholders; only non-deferred values that disagree
            // with the central directory are suspicious
            final boolean localDeferred = (localCrc == 0 || localCrc == 0xFFFFFFFFL) && (localCsize == 0 || localCsize == 0xFFFFFFFFL)
                    && (localUsize == 0 || localUsize == 0xFFFFFFFFL);
            if (!localDeferred && (localCrc != info.crc || localCsize != info.csize || localUsize != info.usize)) {
                report.addIssue(Severity.WARNING, info.index, lho, "entry '" + info.name
                        + "' uses a data descriptor but the local header lengths/CRC disagree with the central directory");
            }
        }
        final long dataStart = lho + LFH_LEN + (long) localNameLen + localExtraLen;
        info.dataStart = dataStart;
        if (info.csize > size - dataStart) {
            report.addIssue(Severity.ERROR, info.index, dataStart, "entry '" + info.name + "' declares " + info.csize + " compressed bytes at offset "
                    + dataStart + " but the channel only holds " + size + " bytes - archive is truncated or the length is forged");
            return;
        }
        info.dataEnd = dataStart + info.csize;
        if (info.dataEnd > cdOffset) {
            report.addIssue(Severity.ERROR, info.index, dataStart, "data of entry '" + info.name + "' [" + dataStart + ", " + info.dataEnd
                    + ") crosses into the central directory region starting at offset " + cdOffset);
        }
        if (deferred) {
            checkDataDescriptor(channel, size, cdOffset, info, report);
        } else {
            info.occupiedEnd = info.dataEnd;
        }
    }

    private static void checkOverlaps(final List<EntryInfo> entries, final ZipPrecheckReport report) {
        final List<EntryInfo> sorted = new ArrayList<>(entries);
        sorted.removeIf(info -> info.dataStart < 0 || info.occupiedEnd < 0);
        sorted.sort(Comparator.comparingLong(info -> info.dataStart));
        EntryInfo widest = null;
        for (final EntryInfo current : sorted) {
            if (widest != null && current.dataStart < widest.occupiedEnd) {
                report.addIssue(Severity.ERROR, current.index, current.dataStart,
                        "data range [" + current.dataStart + ", " + current.occupiedEnd + ") of entry #" + current.index + " '" + current.name
                                + "' overlaps data range [" + widest.dataStart + ", " + widest.occupiedEnd + ") of entry #" + widest.index + " '"
                                + widest.name + "'");
            }
            if (widest == null || current.occupiedEnd > widest.occupiedEnd) {
                widest = current;
            }
        }
    }

    private static void checkStructure(final SeekableByteChannel channel, final ZipPrecheckReport report) throws IOException {
        final long size = channel.size();
        final long eocdOffset = locateEndOfCentralDirectory(channel, size, report);
        if (eocdOffset < 0) {
            report.addIssue(Severity.ERROR, -1, Math.max(0, size - EOCD_LEN),
                    "end of central directory record not found - the archive is truncated or is not a ZIP archive");
            return;
        }
        final ByteBuffer eocd = readAt(channel, eocdOffset, EOCD_LEN);
        if (eocd.remaining() < EOCD_LEN) {
            report.addIssue(Severity.ERROR, -1, eocdOffset, "end of central directory record is truncated");
            return;
        }
        int diskNumber = getU16(eocd, 4);
        int cdDisk = getU16(eocd, 6);
        final int entriesOnDisk = getU16(eocd, 8);
        final int entriesTotal = getU16(eocd, 10);
        long cdSize = getU32(eocd, 12);
        long cdOffset = getU32(eocd, 16);
        long entryCount = entriesTotal;
        if (entriesOnDisk == 0xFFFF || entriesTotal == 0xFFFF || cdSize == 0xFFFFFFFFL || cdOffset == 0xFFFFFFFFL) {
            if (eocdOffset < ZIP64_EOCD_LOCATOR_LEN) {
                report.addIssue(Severity.ERROR, -1, eocdOffset,
                        "end of central directory uses ZIP64 placeholder values but there is no room for a ZIP64 locator - length overflow");
                return;
            }
            final ByteBuffer locator = readAt(channel, eocdOffset - ZIP64_EOCD_LOCATOR_LEN, ZIP64_EOCD_LOCATOR_LEN);
            if (locator.remaining() < ZIP64_EOCD_LOCATOR_LEN || getU32(locator, 0) != ZIP64_EOCD_LOCATOR_SIG) {
                report.addIssue(Severity.ERROR, -1, eocdOffset - ZIP64_EOCD_LOCATOR_LEN,
                        "end of central directory uses ZIP64 placeholder values but the ZIP64 end of central directory locator is missing");
                return;
            }
            final long zip64EocdOffset = locator.getLong(8);
            if (zip64EocdOffset < 0 || zip64EocdOffset + ZIP64_EOCD_LEN > size) {
                report.addIssue(Severity.ERROR, -1, zip64EocdOffset, "ZIP64 end of central directory record is missing or truncated");
                return;
            }
            final ByteBuffer zip64Eocd = readAt(channel, zip64EocdOffset, ZIP64_EOCD_LEN);
            if (zip64Eocd.remaining() < ZIP64_EOCD_LEN || getU32(zip64Eocd, 0) != ZIP64_EOCD_SIG) {
                report.addIssue(Severity.ERROR, -1, zip64EocdOffset, "ZIP64 end of central directory record is missing or truncated");
                return;
            }
            diskNumber = (int) zip64Eocd.getLong(16);
            cdDisk = (int) zip64Eocd.getLong(20);
            entryCount = zip64Eocd.getLong(32);
            cdSize = zip64Eocd.getLong(40);
            cdOffset = zip64Eocd.getLong(48);
            if (entryCount < 0 || cdSize < 0 || cdOffset < 0) {
                report.addIssue(Severity.ERROR, -1, zip64EocdOffset, "ZIP64 end of central directory contains negative (overflowing) lengths");
                return;
            }
        }
        if (diskNumber != 0 || cdDisk != 0) {
            report.addIssue(Severity.UNSUPPORTED, -1, eocdOffset, "multi-volume (split) ZIP archives are not supported by the precheck");
        }
        if (cdOffset > size || cdSize > size - cdOffset) {
            report.addIssue(Severity.ERROR, -1, cdOffset, "central directory at offset " + cdOffset + " spans " + cdSize
                    + " bytes, beyond the end of the channel (" + size + " bytes) - the archive is truncated");
            return;
        }
        if (entryCount > Integer.MAX_VALUE) {
            report.addIssue(Severity.ERROR, -1, eocdOffset, "unreasonable central directory entry count: " + entryCount);
            return;
        }
        final List<EntryInfo> entries = parseCentralDirectory(channel, size, cdOffset, entryCount, report);
        for (final EntryInfo info : entries) {
            checkLocalHeaderAndData(channel, size, cdOffset, info, report);
        }
        checkOverlaps(entries, report);
        checkDuplicateNames(entries, report);
        report.setEntryCount(entries.size());
    }

    private static String decodeName(final ByteBuffer buffer, final int flags) {
        final byte[] bytes = new byte[buffer.remaining()];
        buffer.get(bytes);
        return new String(bytes, (flags & GPBF_UTF8) != 0 ? StandardCharsets.UTF_8 : FALLBACK_NAME_CHARSET);
    }

    private static ByteBuffer findExtraField(final ByteBuffer extra, final int headerId) {
        final ByteBuffer buffer = extra.duplicate().order(ByteOrder.LITTLE_ENDIAN);
        while (buffer.remaining() >= 4) {
            final int id = buffer.getShort() & 0xFFFF;
            final int length = buffer.getShort() & 0xFFFF;
            if (length > buffer.remaining()) {
                return null;
            }
            if (id == headerId) {
                final ByteBuffer field = buffer.slice().order(ByteOrder.LITTLE_ENDIAN);
                field.limit(length);
                return field;
            }
            buffer.position(buffer.position() + length);
        }
        return null;
    }

    private static int getU16(final ByteBuffer buffer, final int position) {
        return buffer.getShort(position) & 0xFFFF;
    }

    private static long getU32(final ByteBuffer buffer, final int position) {
        return buffer.getInt(position) & 0xFFFFFFFFL;
    }

    private static Charset loadCharset(final String name) {
        try {
            return Charset.forName(name);
        } catch (final RuntimeException e) {
            return StandardCharsets.UTF_8;
        }
    }

    private static long locateEndOfCentralDirectory(final SeekableByteChannel channel, final long size, final ZipPrecheckReport report)
            throws IOException {
        final int window = (int) Math.min(size, MAX_EOCD_SEARCH_WINDOW);
        if (window < EOCD_LEN) {
            return -1;
        }
        final ByteBuffer tail = readAt(channel, size - window, window);
        final byte[] bytes = tail.array();
        long fallback = -1;
        for (int i = window - EOCD_LEN; i >= 0; i--) {
            if (bytes[i] == 0x50 && bytes[i + 1] == 0x4b && bytes[i + 2] == 0x05 && bytes[i + 3] == 0x06) {
                final int commentLength = (bytes[i + 20] & 0xFF) | (bytes[i + 21] & 0xFF) << 8;
                final long absolute = size - window + i;
                if (absolute + EOCD_LEN + commentLength == size) {
                    return absolute;
                }
                if (fallback < 0) {
                    fallback = absolute;
                }
            }
        }
        if (fallback >= 0) {
            report.addIssue(Severity.WARNING, -1, fallback, "trailing bytes after the end of central directory record");
        }
        return fallback;
    }

    private static List<EntryInfo> parseCentralDirectory(final SeekableByteChannel channel, final long size, final long cdOffset,
            final long entryCount, final ZipPrecheckReport report) throws IOException {
        final List<EntryInfo> entries = new ArrayList<>();
        long pos = cdOffset;
        for (int i = 0; i < entryCount; i++) {
            if (pos + CFH_LEN > size) {
                report.addIssue(Severity.ERROR, i, pos, "central directory is truncated: the header of entry #" + i
                        + " lies beyond the end of the channel (" + size + " bytes)");
                break;
            }
            final ByteBuffer header = readAt(channel, pos, CFH_LEN);
            final long sig = getU32(header, 0);
            if (sig != CFH_SIG) {
                report.addIssue(Severity.ERROR, i, pos,
                        String.format("expected central directory header signature 0x02014b50 for entry #%d but found 0x%08x"
                                + " - the central directory is truncated or corrupt", i, sig));
                break;
            }
            final EntryInfo info = new EntryInfo();
            info.index = i;
            info.centralOffset = pos;
            info.flags = getU16(header, 8);
            info.method = getU16(header, 10);
            info.crc = getU32(header, 16);
            info.csize = getU32(header, 20);
            info.usize = getU32(header, 24);
            final int nameLen = getU16(header, 28);
            final int extraLen = getU16(header, 30);
            final int commentLen = getU16(header, 32);
            final int diskStart = getU16(header, 34);
            info.localHeaderOffset = getU32(header, 42);
            final long recordLength = CFH_LEN + (long) nameLen + extraLen + commentLen;
            if (pos + recordLength > size) {
                report.addIssue(Severity.ERROR, i, pos, "central directory record of entry #" + i + " is truncated");
                break;
            }
            info.name = decodeName(readAt(channel, pos + CFH_LEN, nameLen), info.flags);
            info.encrypted = (info.flags & GPBF_ENCRYPTION) != 0;
            applyZip64ExtraField(readAt(channel, pos + CFH_LEN + nameLen, extraLen), info, diskStart == 0xFFFF, report);
            if (diskStart != 0 && diskStart != 0xFFFF) {
                report.addIssue(Severity.UNSUPPORTED, i, pos,
                        "entry '" + info.name + "' starts on disk " + diskStart + " - multi-volume archives are not supported by the precheck");
            }
            entries.add(info);
            pos += recordLength;
        }
        return entries;
    }

    private static ByteBuffer readAt(final SeekableByteChannel channel, final long position, final int length) throws IOException {
        final ByteBuffer buffer = ByteBuffer.allocate(length).order(ByteOrder.LITTLE_ENDIAN);
        channel.position(position);
        while (buffer.hasRemaining() && channel.read(buffer) != -1) {
            // read until the buffer is full or the channel ends
        }
        buffer.flip();
        return buffer;
    }

    @SuppressWarnings("deprecation") // getEntries() keeps the central directory order, which matches the entry indexes of the structural check
    private static void verifyEntries(final ZipFile zipFile, final long perEntryBudget, final long totalBudget, final ZipPrecheckReport report)
            throws IOException {
        final byte[] buffer = new byte[8192];
        long total = 0;
        int index = 0;
        final Enumeration<ZipArchiveEntry> entries = zipFile.getEntries();
        while (entries.hasMoreElements()) {
            final ZipArchiveEntry entry = entries.nextElement();
            final int entryIndex = index++;
            if (entry.isDirectory()) {
                continue;
            }
            if (entry.getGeneralPurposeBit().usesEncryption()) {
                report.addIssue(Severity.UNSUPPORTED, entryIndex, -1,
                        "entry '" + entry.getName() + "' is encrypted - its content cannot be verified");
                continue;
            }
            final CRC32 crc = new CRC32();
            long count = 0;
            try (InputStream input = zipFile.getInputStream(entry)) {
                int read;
                while ((read = input.read(buffer)) != -1) {
                    crc.update(buffer, 0, read);
                    count += read;
                    total += read;
                    if (perEntryBudget >= 0 && count > perEntryBudget) {
                        report.addIssue(Severity.ERROR, entryIndex, -1, "entry '" + entry.getName()
                                + "' exceeds the per-entry uncompressed byte budget of " + perEntryBudget + " bytes - aborting validation");
                        report.setEntryCount(index);
                        return;
                    }
                    if (totalBudget >= 0 && total > totalBudget) {
                        report.addIssue(Severity.ERROR, entryIndex, -1, "global uncompressed byte budget of " + totalBudget
                                + " bytes exceeded while reading entry '" + entry.getName() + "' - aborting validation");
                        report.setEntryCount(index);
                        return;
                    }
                }
            } catch (final UnsupportedZipFeatureException e) {
                report.addIssue(Severity.UNSUPPORTED, entryIndex, -1, "entry '" + entry.getName() + "': " + e.getMessage());
                continue;
            } catch (final ZipException e) {
                report.addIssue(Severity.ERROR, entryIndex, -1, "entry '" + entry.getName() + "' failed to decompress: " + e.getMessage());
                continue;
            }
            if (entry.getCrc() != crc.getValue()) {
                report.addIssue(Severity.ERROR, entryIndex, -1,
                        String.format("CRC mismatch for entry '%s': the central directory declares 0x%08x but the decompressed data has 0x%08x",
                                entry.getName(), entry.getCrc(), crc.getValue()));
            }
        }
        report.setEntryCount(index);
    }

    private ZipArchivePrecheck() {
        // utility class
    }
}
