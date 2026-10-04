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
package org.apache.commons.compress.archivers;

/**
 * Identifies the kind of read budget enforced by {@link BudgetArchiveInputStream} and reported by {@link BudgetExceededException}.
 *
 * @since 1.29.0
 */
public enum BudgetType {

    /**
     * Maximum number of decompressed bytes read for a single archive entry.
     */
    ENTRY_BYTES,

    /**
     * Maximum number of decompressed bytes read for the whole archive, including entries the caller skipped (which are drained through the budget).
     */
    TOTAL_BYTES,

    /**
     * Maximum number of entries returned by the archive stream.
     */
    ENTRY_COUNT,

    /**
     * Maximum ratio between decompressed bytes read and compressed bytes consumed for a single entry.
     */
    COMPRESSION_RATIO
}
