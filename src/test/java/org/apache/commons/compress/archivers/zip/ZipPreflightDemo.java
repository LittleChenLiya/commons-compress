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
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.apache.commons.compress.archivers.ArchiveStreamFactory;
import org.apache.commons.compress.utils.SeekableInMemoryByteChannel;

/**
 * Repeatable preflight demo command. Generates the fixture archives (normal ZIP, legal data descriptors, ZIP64, two entries sharing one data
 * region, forged lengths, CRC error, truncated central directory) into {@code target/zip-preflight-demo} and prints the preflight report of
 * each of them, including a normal report, a data region overlap and a failing low decompression budget.
 * <p>
 * Run it with:
 * </p>
 *
 * <pre>
 * mvn -q compile test-compile
 * java -cp target/classes;target/test-classes;&lt;dependencies&gt; org.apache.commons.compress.archivers.zip.ZipPreflightDemo
 * </pre>
 */
public final class ZipPreflightDemo {

    public static void main(final String[] args) throws IOException {
        final Path outDir = Paths.get(args.length > 0 ? args[0] : "target/zip-preflight-demo");
        Files.createDirectories(outDir);
        System.out.println("ZIP preflight demo, fixtures are written to " + outDir.toAbsolutePath());
        System.out.println();

        demo("normal ZIP (STORED + DEFLATED, content validation enabled)", outDir.resolve("normal.zip"), ZipPreflightFixtures.normalZip(),
                ZipPreflightOptions.builder().setValidateContent(true).get());
        demo("legal data descriptors (deferred CRC/length)", outDir.resolve("descriptor.zip"), ZipPreflightFixtures.descriptorZip(),
                ZipPreflightOptions.builder().setValidateContent(true).get());
        demo("ZIP64 archive", outDir.resolve("zip64.zip"), ZipPreflightFixtures.zip64Zip(),
                ZipPreflightOptions.builder().setValidateContent(true).get());
        demo("empty archive", outDir.resolve("empty.zip"), ZipPreflightFixtures.emptyZip(), ZipPreflightOptions.defaultOptions());
        demo("duplicate entry names (warning, not an overlap)", outDir.resolve("duplicate-names.zip"),
                ZipPreflightFixtures.duplicateNamesZip(), ZipPreflightOptions.defaultOptions());
        demo("two entries sharing one data region", outDir.resolve("overlap.zip"), ZipPreflightFixtures.overlapZip(),
                ZipPreflightOptions.defaultOptions());
        demo("forged length reaching into the central directory", outDir.resolve("forged-length.zip"),
                ZipPreflightFixtures.forgedLengthZip(), ZipPreflightOptions.defaultOptions());
        demo("ZIP64 placeholder without ZIP64 extra field", outDir.resolve("forged-zip64.zip"),
                ZipPreflightFixtures.forgedZip64PlaceholderZip(), ZipPreflightOptions.defaultOptions());
        demo("CRC error (found by content validation)", outDir.resolve("bad-crc.zip"), ZipPreflightFixtures.badCrcZip(),
                ZipPreflightOptions.builder().setValidateContent(true).get());
        demo("truncated central directory", outDir.resolve("truncated-cd.zip"), ZipPreflightFixtures.truncatedCentralDirectoryZip(),
                ZipPreflightOptions.defaultOptions());
        demo("low decompression budget (100 bytes per entry)", outDir.resolve("budget.zip"), ZipPreflightFixtures.budgetZip(),
                ZipPreflightOptions.builder().setValidateContent(true).setMaxUncompressedBytesPerEntry(100).get());
        demo("encrypted entry (unsupported, never guessed as passed)", outDir.resolve("encrypted.zip"), ZipPreflightFixtures.encryptedZip(),
                ZipPreflightOptions.defaultOptions());
        demo("split multi-volume archive (unsupported)", outDir.resolve("split.zip"), ZipPreflightFixtures.splitZip(),
                ZipPreflightOptions.defaultOptions());

        System.out.println("== entry points ==");
        final byte[] normal = ZipPreflightFixtures.normalZip();
        final SeekableInMemoryByteChannel channel = new SeekableInMemoryByteChannel(normal);
        channel.position(3);
        final ZipPreflightReport report = new ArchiveStreamFactory().preflightZipArchive(channel);
        System.out.println("ArchiveStreamFactory.preflightZipArchive: passed=" + report.isPassed() + ", entries=" + report.getEntryCount()
                + ", channel position restored to " + channel.position() + " (was 3)");
        try (ZipFile zipFile = ZipFile.builder().setPath(outDir.resolve("normal.zip")).get()) {
            System.out.println("ZipFile.preflight: passed=" + zipFile.preflight().isPassed());
        }
        System.out.println();
        System.out.println("done.");
    }

    private static void demo(final String title, final Path path, final byte[] zip, final ZipPreflightOptions options) throws IOException {
        Files.write(path, zip);
        System.out.println("== " + title + " ==");
        System.out.println("   file: " + path.getFileName() + " (" + zip.length + " bytes)");
        final ZipPreflightReport report = ZipPreflight.check(path, options);
        if (report.getDiagnostics().isEmpty()) {
            System.out.println("   " + report);
        } else {
            report.getDiagnostics().forEach(diagnostic -> System.out.println("   " + diagnostic));
            System.out.println("   => passed=" + report.isPassed() + ", fullyVerified=" + report.isFullyVerified());
        }
        System.out.println();
    }

    private ZipPreflightDemo() {
        // demo command, not to be instantiated
    }
}
