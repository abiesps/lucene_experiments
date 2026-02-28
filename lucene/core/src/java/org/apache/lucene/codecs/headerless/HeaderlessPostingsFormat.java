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
import org.apache.lucene.codecs.FieldsConsumer;
import org.apache.lucene.codecs.FieldsProducer;
import org.apache.lucene.codecs.PostingsFormat;
import org.apache.lucene.codecs.PostingsReaderBase;
import org.apache.lucene.codecs.PostingsWriterBase;
import org.apache.lucene.codecs.lucene103.blocktree.Lucene103BlockTreeTermsReader;
import org.apache.lucene.codecs.lucene103.blocktree.Lucene103BlockTreeTermsWriter;
import org.apache.lucene.codecs.lucene104.Lucene104PostingsReader;
import org.apache.lucene.codecs.lucene104.Lucene104PostingsWriter;
import org.apache.lucene.index.SegmentReadState;
import org.apache.lucene.index.SegmentWriteState;
import org.apache.lucene.util.IOUtils;

/**
 * Headerless postings format that wraps Lucene104.
 *
 * <p>This format uses the standard Lucene104 postings implementation but
 * skips header/footer I/O by wrapping the directory.
 *
 * @lucene.experimental
 */
public final class HeaderlessPostingsFormat extends PostingsFormat {

  public static final String NAME = "HeaderlessPostings";
  public static final int VERSION = 1;

  public HeaderlessPostingsFormat() {
    super(NAME);
  }

  @Override
  public FieldsConsumer fieldsConsumer(SegmentWriteState state) throws IOException {
    // Create manifest writer for this segment
    ManifestWriter manifestWriter = new ManifestWriter();

    // Wrap directory to intercept file creation
    HeaderlessDirectory headerlessDir = new HeaderlessDirectory(
        state.directory,
        state.segmentInfo.name,
        manifestWriter);

    // Create modified state with headerless directory
    SegmentWriteState headerlessState = new SegmentWriteState(
        state.infoStream,
        headerlessDir,
        state.segmentInfo,
        state.fieldInfos,
        state.segmentUpdates,
        state.context);

    // Use standard Lucene104 implementation
    PostingsWriterBase postingsWriter = new Lucene104PostingsWriter(headerlessState);
    try {
      return new Lucene103BlockTreeTermsWriter(headerlessState, postingsWriter, 25, 48);
    } catch (Throwable t) {
      IOUtils.closeWhileSuppressingExceptions(t, postingsWriter);
      throw t;
    }
  }

  @Override
  public FieldsProducer fieldsProducer(SegmentReadState state) throws IOException {
    // Read manifest for this segment
    ManifestReader manifestReader = new ManifestReader(
        state.directory,
        state.segmentInfo.name,
        state.segmentSuffix);

    // Wrap directory to provide headerless views
    HeaderlessDirectory headerlessDir = new HeaderlessDirectory(
        state.directory,
        state.segmentInfo.name,
        manifestReader);

    // Create modified state with headerless directory
    SegmentReadState headerlessState = new SegmentReadState(
        headerlessDir,
        state.segmentInfo,
        state.fieldInfos,
        state.context,
        state.segmentSuffix);

    // Use standard Lucene104 implementation
    PostingsReaderBase postingsReader = new Lucene104PostingsReader(headerlessState);
    try {
      return new Lucene103BlockTreeTermsReader(postingsReader, headerlessState);
    } catch (Throwable t) {
      IOUtils.closeWhileSuppressingExceptions(t, postingsReader);
      throw t;
    }
  }
}
