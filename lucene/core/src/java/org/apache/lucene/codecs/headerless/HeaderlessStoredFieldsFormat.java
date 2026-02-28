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
package org.apache.lucene.codecs.headerless;

import java.io.IOException;
import org.apache.lucene.codecs.StoredFieldsFormat;
import org.apache.lucene.codecs.StoredFieldsReader;
import org.apache.lucene.codecs.StoredFieldsWriter;
import org.apache.lucene.codecs.lucene90.Lucene90StoredFieldsFormat;
import org.apache.lucene.index.SegmentInfo;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.IOContext;

/**
 * Headerless stored fields format.
 *
 * @lucene.experimental
 */
public final class HeaderlessStoredFieldsFormat extends StoredFieldsFormat {

  private final Lucene90StoredFieldsFormat delegate = new Lucene90StoredFieldsFormat();

  @Override
  public StoredFieldsReader fieldsReader(
      Directory directory, SegmentInfo si, FieldInfos fn, IOContext context) throws IOException {
    // TODO: Wrap directory with HeaderlessDirectory
    return delegate.fieldsReader(directory, si, fn, context);
  }

  @Override
  public StoredFieldsWriter fieldsWriter(
      Directory directory, SegmentInfo si, IOContext context) throws IOException {
    // TODO: Wrap directory with HeaderlessDirectory
    return delegate.fieldsWriter(directory, si, context);
  }
}
