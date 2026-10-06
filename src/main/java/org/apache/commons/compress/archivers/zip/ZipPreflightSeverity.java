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

/**
 * Severity of a {@link ZipPreflightDiagnostic} found by a ZIP preflight check.
 *
 * @since 1.29.0
 */
public enum ZipPreflightSeverity {

    /**
     * A structural error: the archive is corrupt, truncated, internally inconsistent or unsafe to extract. Archives with errors must not be
     * extracted or copied.
     */
    ERROR,

    /**
     * The archive uses a feature that is explicitly not supported, for example encryption or split (multi-volume) archives. A preflight check can
     * never report such an archive as passed; it is unknown whether it is intact.
     */
    UNSUPPORTED,

    /**
     * The archive uses a feature that Commons Compress supports but the preflight check could not verify, for example a compression method without
     * a streaming validator. The entry may be fine, but the check cannot prove it.
     */
    UNVERIFIED,

    /**
     * A suspicious but legal condition, for example duplicate entry names or trailing garbage after the end of central directory record.
     */
    WARNING
}
