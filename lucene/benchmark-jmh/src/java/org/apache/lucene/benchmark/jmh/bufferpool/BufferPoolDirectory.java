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
package org.apache.lucene.benchmark.jmh.bufferpool;

import java.io.IOException;
import java.nio.file.Path;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.store.FilterDirectory;
import org.apache.lucene.store.IOContext;
import org.apache.lucene.store.IndexInput;
import org.apache.lucene.store.NIOFSDirectory;

/**
 * Encryption-free backport of JunoStorageEncryptionPlugin {@code
 * org.opensearch.index.store.bufferpoolfs.BufferPoolDirectory}: all reads are served through the
 * {@link LRUBlockCache} + {@link MemorySegmentPool} (block-aligned pooled direct buffers, GC-freed
 * on eviction) via {@link BufferPoolIndexInput}; writes pass through to the plain filesystem
 * directory (the plugin's write path is the encrypting {@code BufferIOWithCaching}, skipped per
 * scope).
 */
public final class BufferPoolDirectory extends FilterDirectory {

  private final Path dirPath;
  private final LRUBlockCache blockCache;
  private final int blockSize;

  public BufferPoolDirectory(Path path, LRUBlockCache blockCache, int blockSize)
      throws IOException {
    super(new NIOFSDirectory(path));
    this.dirPath = ((FSDirectory) getDelegate()).getDirectory();
    this.blockCache = blockCache;
    this.blockSize = blockSize;
  }

  @Override
  public IndexInput openInput(String name, IOContext context) throws IOException {
    ensureOpen();
    Path file = dirPath.resolve(name).toAbsolutePath().normalize();
    long length = fileLength(name);
    if (length == 0) {
      // Block loads of empty files are undefined; fall back to the delegate.
      return super.openInput(name, context);
    }
    return BufferPoolIndexInput.newInstance(
        "BufferPoolIndexInput(path=\"" + file + "\")", file, length, blockCache, blockSize);
  }

  @Override
  public void deleteFile(String name) throws IOException {
    Path file = dirPath.resolve(name).toAbsolutePath().normalize();
    blockCache.invalidateFile(file);
    super.deleteFile(name);
  }

  @Override
  public synchronized void close() throws IOException {
    blockCache.close();
    super.close();
  }
}
