/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.lucene.bufferpool;

import java.nio.file.Path;

/**
 * Cache key of one block of one file.
 *
 * @param file absolute path of the file, used to purge a whole directory on close
 * @param fileId id the directory assigned to this incarnation of the file; a file re-created under
 *     the same name gets a new id, so blocks cached from the old file can never be served for the
 *     new one
 * @param blockOffset block-aligned offset of the block in the file
 */
record BlockKey(Path file, long fileId, long blockOffset) {}
