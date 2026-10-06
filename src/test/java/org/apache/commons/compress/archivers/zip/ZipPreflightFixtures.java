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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.CRC32;

/**
 * Builds the ZIP fixtures used by {@link ZipPreflightTest} and {@link ZipPreflightDemo}: a normal archive, an archive with legal data
 * descriptors, a ZIP64 archive, two entries sharing one data region, forged lengths, a CRC error, a truncated central directory and more.
 */
final class ZipPreflightFixtures {

    private static final byte[] ALPHA = repeat("alpha-", 2000);

    private static final byte[] BETA = repeat("beta-", 500);

    static byte[] repeat(final String token, final int length) {
        final byte[] tokenBytes = token.getBytes(StandardCharsets.UTF_8);
        final byte[] result = new byte[length];
        for (int i = 0; i < length; i++) {
            result[i] = tokenBytes[i % tokenBytes.length];
        }
        return result;
    }

    private static long crc32(final byte[] data) {
        final CRC32 crc = new CRC32();
        crc.update(data, 0, data.length);
        return crc.getValue();
    }

    private static ZipArchiveEntry storedEntry(final String name, final byte[] content) {
        final ZipArchiveEntry entry = new ZipArchiveEntry(name);
        entry.setMethod(ZipArchiveOutputStream.STORED);
        entry.setSize(content.length);
        entry.setCrc(crc32(content));
        return entry;
    }

    private static ZipArchiveEntry deflatedEntry(final String name, final byte[] content, final boolean presetSizes) {
        final ZipArchiveEntry entry = new ZipArchiveEntry(name);
        entry.setMethod(ZipArchiveOutputStream.DEFLATED);
        if (presetSizes) {
            entry.setSize(content.length);
            entry.setCrc(crc32(content));
        }
        return entry;
    }

    /** A plain archive: one STORED and one DEFLATED entry, sizes and CRCs recorded in the local headers, no data descriptors. */
    static byte[] normalZip() throws IOException {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipArchiveOutputStream zip = new ZipArchiveOutputStream(out)) {
            zip.putArchiveEntry(storedEntry("alpha.txt", ALPHA));
            zip.write(ALPHA);
            zip.closeArchiveEntry();
            zip.putArchiveEntry(deflatedEntry("beta.txt", BETA, true));
            zip.write(BETA);
            zip.closeArchiveEntry();
        }
        return out.toByteArray();
    }

    /** DEFLATED entries written without preset sizes: legal data descriptors with deferred CRC/length values. */
    static byte[] descriptorZip() throws IOException {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipArchiveOutputStream zip = new ZipArchiveOutputStream(out)) {
            zip.putArchiveEntry(deflatedEntry("alpha.txt", ALPHA, false));
            zip.write(ALPHA);
            zip.closeArchiveEntry();
            zip.putArchiveEntry(deflatedEntry("beta.txt", BETA, false));
            zip.write(BETA);
            zip.closeArchiveEntry();
        }
        return out.toByteArray();
    }

    /** ZIP64 everywhere: ZIP64 extra fields, ZIP64 data descriptors and the ZIP64 end records. */
    static byte[] zip64Zip() throws IOException {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipArchiveOutputStream zip = new ZipArchiveOutputStream(out)) {
            zip.setUseZip64(Zip64Mode.Always);
            zip.putArchiveEntry(deflatedEntry("alpha.txt", ALPHA, false));
            zip.write(ALPHA);
            zip.closeArchiveEntry();
            zip.putArchiveEntry(deflatedEntry("beta.txt", BETA, false));
            zip.write(BETA);
            zip.closeArchiveEntry();
        }
        return out.toByteArray();
    }

    /** An archive without any entries. */
    static byte[] emptyZip() throws IOException {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipArchiveOutputStream zip = new ZipArchiveOutputStream(out)) {
            // no entries
        }
        return out.toByteArray();
    }

    /** Two entries with the same name but separate data regions: a warning, not a data overlap. */
    static byte[] duplicateNamesZip() throws IOException {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipArchiveOutputStream zip = new ZipArchiveOutputStream(out)) {
            zip.putArchiveEntry(storedEntry("same.txt", ALPHA));
            zip.write(ALPHA);
            zip.closeArchiveEntry();
            zip.putArchiveEntry(storedEntry("same.txt", BETA));
            zip.write(BETA);
            zip.closeArchiveEntry();
        }
        return out.toByteArray();
    }

    /** A big DEFLATED entry followed by a STORED entry whose central directory CRC is forged. */
    static byte[] budgetZip() throws IOException {
        final byte[] big = repeat("z", 5000);
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipArchiveOutputStream zip = new ZipArchiveOutputStream(out)) {
            zip.putArchiveEntry(deflatedEntry("big.txt", big, true));
            zip.write(big);
            zip.closeArchiveEntry();
            zip.putArchiveEntry(storedEntry("tail.txt", BETA));
            zip.write(BETA);
            zip.closeArchiveEntry();
        }
        final byte[] zip = out.toByteArray();
        final List<Integer> cfh = centralDirectoryRecordOffsets(zip);
        putInt(zip, cfh.get(1) + 16, 0xDEADBEEFL); // forge the CRC of the second entry
        return zip;
    }

    /** Hand crafted archive in which the local file header of the second entry sits inside the data of the first entry. */
    static byte[] overlapZip() {
        final byte[] first = repeat("A", 100);
        final byte[] second = repeat("B", 50);
        final byte[] firstName = "first.txt".getBytes(StandardCharsets.UTF_8);
        final byte[] secondName = "second.txt".getBytes(StandardCharsets.UTF_8);
        // layout: LFH0 at 0, data0 at 39..139, LFH1 at 89 (inside data0), data1 at 129..179, central directory at 179
        final int secondLocalHeaderOffset = 39 + 50;
        final int centralDirectoryOffset = 39 + 100 + 40;
        final int centralDirectorySize = 46 + firstName.length + 46 + secondName.length;
        final byte[] zip = new byte[centralDirectoryOffset + centralDirectorySize + 22];
        writeLocalFileHeader(zip, 0, firstName, crc32(first), first.length);
        System.arraycopy(first, 0, zip, 39, first.length);
        writeLocalFileHeader(zip, secondLocalHeaderOffset, secondName, crc32(second), second.length);
        System.arraycopy(second, 0, zip, secondLocalHeaderOffset + 30 + secondName.length, second.length);
        int position = centralDirectoryOffset;
        position = writeCentralDirectoryEntry(zip, position, firstName, crc32(first), first.length, 0);
        position = writeCentralDirectoryEntry(zip, position, secondName, crc32(second), second.length, secondLocalHeaderOffset);
        writeEndOfCentralDirectory(zip, position, 2, centralDirectorySize, centralDirectoryOffset);
        return zip;
    }

    private static void writeLocalFileHeader(final byte[] zip, final int offset, final byte[] name, final long crc, final long size) {
        putInt(zip, offset, 0x04034B50L);
        putShort(zip, offset + 4, 20); // version needed
        putShort(zip, offset + 6, 0); // flags
        putShort(zip, offset + 8, 0); // method STORED
        putShort(zip, offset + 10, 0); // time
        putShort(zip, offset + 12, 0); // date
        putInt(zip, offset + 14, crc);
        putInt(zip, offset + 18, size);
        putInt(zip, offset + 22, size);
        putShort(zip, offset + 26, name.length);
        putShort(zip, offset + 28, 0); // extra length
        System.arraycopy(name, 0, zip, offset + 30, name.length);
    }

    private static int writeCentralDirectoryEntry(final byte[] zip, final int offset, final byte[] name, final long crc, final long size,
            final long localHeaderOffset) {
        putInt(zip, offset, 0x02014B50L);
        putShort(zip, offset + 4, 20); // version made by
        putShort(zip, offset + 6, 20); // version needed
        putShort(zip, offset + 8, 0); // flags
        putShort(zip, offset + 10, 0); // method STORED
        putShort(zip, offset + 12, 0); // time
        putShort(zip, offset + 14, 0); // date
        putInt(zip, offset + 16, crc);
        putInt(zip, offset + 20, size);
        putInt(zip, offset + 24, size);
        putShort(zip, offset + 28, name.length);
        putShort(zip, offset + 30, 0); // extra length
        putShort(zip, offset + 32, 0); // comment length
        putShort(zip, offset + 34, 0); // disk number start
        putShort(zip, offset + 36, 0); // internal attributes
        putInt(zip, offset + 38, 0); // external attributes
        putInt(zip, offset + 42, localHeaderOffset);
        System.arraycopy(name, 0, zip, offset + 46, name.length);
        return offset + 46 + name.length;
    }

    private static void writeEndOfCentralDirectory(final byte[] zip, final int offset, final int entries, final long size,
            final long centralDirectoryOffset) {
        putInt(zip, offset, 0x06054B50L);
        putShort(zip, offset + 4, 0); // disk number
        putShort(zip, offset + 6, 0); // central directory disk
        putShort(zip, offset + 8, entries);
        putShort(zip, offset + 10, entries);
        putInt(zip, offset + 12, size);
        putInt(zip, offset + 16, centralDirectoryOffset);
        putShort(zip, offset + 20, 0); // comment length
    }

    static void putShort(final byte[] data, final int offset, final int value) {
        data[offset] = (byte) (value & 0xFF);
        data[offset + 1] = (byte) (value >> 8 & 0xFF);
    }

    static void putInt(final byte[] data, final int offset, final long value) {
        data[offset] = (byte) (value & 0xFF);
        data[offset + 1] = (byte) (value >> 8 & 0xFF);
        data[offset + 2] = (byte) (value >> 16 & 0xFF);
        data[offset + 3] = (byte) (value >> 24 & 0xFF);
    }

    static int getShort(final byte[] data, final int offset) {
        return (data[offset] & 0xFF) | (data[offset + 1] & 0xFF) << 8;
    }

    static long getInt(final byte[] data, final int offset) {
        return (data[offset] & 0xFFL) | (data[offset + 1] & 0xFFL) << 8 | (data[offset + 2] & 0xFFL) << 16 | (data[offset + 3] & 0xFFL) << 24;
    }

    /** Offset of the end of central directory record or -1. */
    static int endOfCentralDirectoryOffset(final byte[] zip) {
        for (int i = zip.length - 22; i >= 0; i--) {
            if (zip[i] == 0x50 && zip[i + 1] == 0x4B && zip[i + 2] == 0x05 && zip[i + 3] == 0x06) {
                return i;
            }
        }
        return -1;
    }

    /** Absolute offsets of all central directory file header records. */
    static List<Integer> centralDirectoryRecordOffsets(final byte[] zip) {
        final int eocd = endOfCentralDirectoryOffset(zip);
        final int entries = getShort(zip, eocd + 10);
        final long offset = getInt(zip, eocd + 16);
        final List<Integer> offsets = new ArrayList<>();
        long position = offset;
        for (int i = 0; i < entries; i++) {
            offsets.add((int) position);
            final int nameLength = getShort(zip, (int) position + 28);
            final int extraLength = getShort(zip, (int) position + 30);
            final int commentLength = getShort(zip, (int) position + 32);
            position += 46 + nameLength + extraLength + commentLength;
        }
        return offsets;
    }

    /** The compressed size of the first entry is forged so its data region reaches into the central directory. */
    static byte[] forgedLengthZip() throws IOException {
        final byte[] zip = normalZip();
        final int eocd = endOfCentralDirectoryOffset(zip);
        final long centralDirectoryOffset = getInt(zip, eocd + 16);
        final List<Integer> cfh = centralDirectoryRecordOffsets(zip);
        final long localHeaderOffset = getInt(zip, cfh.get(0) + 42);
        final int localNameLength = getShort(zip, (int) localHeaderOffset + 26);
        final int localExtraLength = getShort(zip, (int) localHeaderOffset + 28);
        final long dataOffset = localHeaderOffset + 30 + localNameLength + localExtraLength;
        final long forged = centralDirectoryOffset - dataOffset + 5;
        putInt(zip, cfh.get(0) + 20, forged); // compressed size
        putInt(zip, cfh.get(0) + 24, forged); // uncompressed size
        return zip;
    }

    /** The sizes of the first entry are replaced by 0xFFFFFFFF ZIP64 placeholders without adding the ZIP64 extra field. */
    static byte[] forgedZip64PlaceholderZip() throws IOException {
        final byte[] zip = normalZip();
        final List<Integer> cfh = centralDirectoryRecordOffsets(zip);
        putInt(zip, cfh.get(0) + 20, 0xFFFFFFFFL);
        putInt(zip, cfh.get(0) + 24, 0xFFFFFFFFL);
        return zip;
    }

    /** The central directory CRC of the first entry is forged. */
    static byte[] badCrcZip() throws IOException {
        final byte[] zip = normalZip();
        final List<Integer> cfh = centralDirectoryRecordOffsets(zip);
        putInt(zip, cfh.get(0) + 16, 0xDEADBEEFL);
        return zip;
    }

    /** The file is cut in the middle of the central directory. */
    static byte[] truncatedCentralDirectoryZip() throws IOException {
        final byte[] zip = normalZip();
        final int eocd = endOfCentralDirectoryOffset(zip);
        final long centralDirectoryOffset = getInt(zip, eocd + 16);
        return Arrays.copyOf(zip, (int) centralDirectoryOffset + 20);
    }

    /** The end of central directory record declares more entries than the central directory contains. */
    static byte[] forgedEntryCountZip() throws IOException {
        final byte[] zip = normalZip();
        final int eocd = endOfCentralDirectoryOffset(zip);
        putShort(zip, eocd + 8, 5);
        putShort(zip, eocd + 10, 5);
        return zip;
    }

    /** The encryption flag is set for the first entry in both headers. */
    static byte[] encryptedZip() throws IOException {
        final byte[] zip = normalZip();
        final List<Integer> cfh = centralDirectoryRecordOffsets(zip);
        putShort(zip, cfh.get(0) + 8, getShort(zip, cfh.get(0) + 8) | 1);
        final long localHeaderOffset = getInt(zip, cfh.get(0) + 42);
        putShort(zip, (int) localHeaderOffset + 6, getShort(zip, (int) localHeaderOffset + 6) | 1);
        return zip;
    }

    /** The end of central directory record claims the archive continues on another disk. */
    static byte[] splitZip() throws IOException {
        final byte[] zip = normalZip();
        final int eocd = endOfCentralDirectoryOffset(zip);
        putShort(zip, eocd + 4, 1); // number of this disk
        return zip;
    }

    /** The compression method of the first entry is changed to BZIP2 in both headers. */
    static byte[] bzip2MethodZip() throws IOException {
        final byte[] zip = normalZip();
        final List<Integer> cfh = centralDirectoryRecordOffsets(zip);
        putShort(zip, cfh.get(0) + 10, 12);
        final long localHeaderOffset = getInt(zip, cfh.get(0) + 42);
        putShort(zip, (int) localHeaderOffset + 8, 12);
        return zip;
    }

    private ZipPreflightFixtures() {
        // utility
    }
}
