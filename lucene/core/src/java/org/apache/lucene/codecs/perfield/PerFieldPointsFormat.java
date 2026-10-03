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
package org.apache.lucene.codecs.perfield;

import java.io.IOException;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.lucene.codecs.PointsFormat;
import org.apache.lucene.codecs.PointsReader;
import org.apache.lucene.codecs.PointsWriter;
import org.apache.lucene.codecs.lucene90.Lucene90PointsFormat;
import org.apache.lucene.codecs.lucene90.Lucene90SplitPointsFormat;
import org.apache.lucene.index.FieldInfo;
import org.apache.lucene.index.FieldInfos;
import org.apache.lucene.index.MergeState;
import org.apache.lucene.index.PointValues;
import org.apache.lucene.index.SegmentReadState;
import org.apache.lucene.index.SegmentWriteState;
import org.apache.lucene.internal.hppc.IntObjectHashMap;
import org.apache.lucene.util.IOUtils;

/**
 * Chooses the points format per field: the stock {@link Lucene90PointsFormat} or the split {@link
 * Lucene90SplitPointsFormat}. Stock fields are written by an unchanged stock writer to the
 * unchanged segment files ({@code _N.kdm}, {@code _N.kdi}, {@code _N.kdd}), so their bytes are the
 * bytes the stock format writes. Split fields go to {@code _N_Lucene90Split_0.*}.
 *
 * <p>The choice is recorded in the {@link FieldInfo} attributes {@link #PER_FIELD_FORMAT_KEY} and
 * {@link #PER_FIELD_SUFFIX_KEY}. A field without the format attribute is stock. Reading uses the
 * attributes only. {@link PointsFormat} has no SPI, so format names resolve through a fixed map:
 * {@value #STOCK_FORMAT_NAME} and {@value #SPLIT_FORMAT_NAME}.
 *
 * @lucene.experimental
 */
public abstract class PerFieldPointsFormat extends PointsFormat {

  /** {@link FieldInfo} attribute name used to store the format name for each field. */
  public static final String PER_FIELD_FORMAT_KEY =
      PerFieldPointsFormat.class.getSimpleName() + ".format";

  /** {@link FieldInfo} attribute name used to store the segment suffix name for each field. */
  public static final String PER_FIELD_SUFFIX_KEY =
      PerFieldPointsFormat.class.getSimpleName() + ".suffix";

  /** Format name of the stock points format. */
  public static final String STOCK_FORMAT_NAME = "Lucene90";

  /** Format name of the split points format. */
  public static final String SPLIT_FORMAT_NAME = "Lucene90Split";

  /** The stock points format. */
  public static final PointsFormat STOCK = new Lucene90PointsFormat();

  /** The split points format with the default number of points per leaf. */
  public static final PointsFormat SPLIT = new Lucene90SplitPointsFormat();

  /** The only suffix the split format uses. */
  static final String SPLIT_SUFFIX = "0";

  /** Sole constructor. */
  protected PerFieldPointsFormat() {}

  /**
   * Returns the points format to write {@code field} with: {@link #STOCK}, or a {@link
   * Lucene90SplitPointsFormat} for a field with one dimension. Only called when writing.
   */
  public abstract PointsFormat getPointsFormatForField(FieldInfo field);

  /**
   * Returns the name recorded for a format that {@link #getPointsFormatForField} returned. The
   * default maps {@link Lucene90PointsFormat} to {@value #STOCK_FORMAT_NAME} and {@link
   * Lucene90SplitPointsFormat} to {@value #SPLIT_FORMAT_NAME}. Override it only for a format that
   * writes exactly what one of these reads.
   */
  protected String getFormatName(PointsFormat format) {
    if (format instanceof Lucene90PointsFormat) {
      return STOCK_FORMAT_NAME;
    } else if (format instanceof Lucene90SplitPointsFormat) {
      return SPLIT_FORMAT_NAME;
    }
    throw new IllegalStateException("unsupported points format " + format);
  }

  /** Returns the format that reads files written under {@code name}, or null if unknown. */
  static PointsFormat readFormat(String name) {
    return switch (name) {
      case STOCK_FORMAT_NAME -> STOCK;
      case SPLIT_FORMAT_NAME -> SPLIT;
      default -> null;
    };
  }

  /** True if {@code fi} is stored in the stock files of its segment. */
  static boolean isStock(FieldInfo fi) {
    String name = fi.getAttribute(PER_FIELD_FORMAT_KEY);
    return name == null || STOCK_FORMAT_NAME.equals(name);
  }

  static String splitSegmentSuffix(String outerSegmentSuffix) {
    return PerFieldDocValuesFormat.getFullSegmentSuffix(
        outerSegmentSuffix, PerFieldDocValuesFormat.getSuffix(SPLIT_FORMAT_NAME, SPLIT_SUFFIX));
  }

  @Override
  public final PointsWriter fieldsWriter(SegmentWriteState state) throws IOException {
    return new PerFieldPointsWriter(state);
  }

  @Override
  public final PointsReader fieldsReader(SegmentReadState state) throws IOException {
    return new PerFieldPointsReader(state);
  }

  private final class PerFieldPointsWriter extends PointsWriter {
    private final SegmentWriteState state;
    private PointsWriter stockWriter;
    private PointsWriter splitWriter;
    // format name -> the first format the chooser returned under that name
    private final Map<String, PointsFormat> formats = new LinkedHashMap<>();

    PerFieldPointsWriter(SegmentWriteState state) {
      this.state = state;
    }

    /** Asks the chooser, records the choice on {@code fi}, returns the format name. */
    private String choose(FieldInfo fi) {
      PointsFormat format = getPointsFormatForField(fi);
      if (format == null) {
        throw new IllegalStateException("invalid null PointsFormat for field=\"" + fi.name + "\"");
      }
      String name = getFormatName(format);
      formats.putIfAbsent(name, format);
      if (SPLIT_FORMAT_NAME.equals(name)) {
        fi.putAttribute(PER_FIELD_FORMAT_KEY, SPLIT_FORMAT_NAME);
        fi.putAttribute(PER_FIELD_SUFFIX_KEY, SPLIT_SUFFIX);
      } else if (fi.getAttribute(PER_FIELD_FORMAT_KEY) != null) {
        // inherited from a segment where the field was split
        fi.putAttribute(PER_FIELD_FORMAT_KEY, STOCK_FORMAT_NAME);
      }
      return name;
    }

    private PointsWriter writer(String name, PointsFormat format) throws IOException {
      if (SPLIT_FORMAT_NAME.equals(name)) {
        if (splitWriter == null) {
          splitWriter =
              format.fieldsWriter(
                  new SegmentWriteState(state, splitSegmentSuffix(state.segmentSuffix)));
        }
        return splitWriter;
      } else {
        if (stockWriter == null) {
          // the unchanged state: the stock files, exactly as the stock format names them
          stockWriter = format.fieldsWriter(state);
        }
        return stockWriter;
      }
    }

    @Override
    public void writeField(FieldInfo fieldInfo, PointsReader values) throws IOException {
      String name = choose(fieldInfo);
      writer(name, formats.get(name)).writeField(fieldInfo, values);
    }

    @Override
    public void merge(MergeState mergeState) throws IOException {
      // 1. group the fields by the format the chooser picks now, and record the choice on every
      // field, including fields that end with 0 points
      List<String> stockFields = new ArrayList<>();
      List<String> splitFields = new ArrayList<>();
      for (FieldInfo fi : mergeState.mergeFieldInfos) {
        if (fi.getPointDimensionCount() == 0) {
          continue;
        }
        String name = choose(fi);
        (SPLIT_FORMAT_NAME.equals(name) ? splitFields : stockFields).add(fi.name);
      }
      // 2. and 3. one merge per group, on a state restricted to the group's fields; each writer
      // finishes itself
      if (stockFields.isEmpty() == false) {
        writer(STOCK_FORMAT_NAME, formats.get(STOCK_FORMAT_NAME))
            .merge(
                PerFieldMergeState.restrictFields(
                    mergeState, stockFields, stockReaders(mergeState, stockFields)));
      }
      if (splitFields.isEmpty() == false) {
        writer(SPLIT_FORMAT_NAME, formats.get(SPLIT_FORMAT_NAME))
            .merge(
                PerFieldMergeState.restrictFields(
                    mergeState, splitFields, routingReaders(mergeState)));
      }
    }

    /**
     * Per segment: its stock reader if every group field it holds with points is stock there (so an
     * all-stock merge takes the stock bulk path), else a reader that routes every field to the
     * segment's reader for that field.
     */
    private PointsReader[] stockReaders(MergeState mergeState, List<String> fields) {
      PointsReader[] readers = new PointsReader[mergeState.pointsReaders.length];
      for (int i = 0; i < readers.length; i++) {
        PointsReader reader = mergeState.pointsReaders[i];
        if (reader instanceof PerFieldPointsReader perField) {
          boolean allStock = true;
          FieldInfos segmentFieldInfos = mergeState.fieldInfos[i];
          for (String field : fields) {
            FieldInfo fi = segmentFieldInfos.fieldInfo(field);
            if (fi != null && fi.getPointDimensionCount() > 0 && isStock(fi) == false) {
              allStock = false;
              break;
            }
          }
          readers[i] = allStock ? perField.stockDelegate() : perField.routingReader();
        } else {
          readers[i] = reader;
        }
      }
      return readers;
    }

    private PointsReader[] routingReaders(MergeState mergeState) {
      PointsReader[] readers = new PointsReader[mergeState.pointsReaders.length];
      for (int i = 0; i < readers.length; i++) {
        PointsReader reader = mergeState.pointsReaders[i];
        readers[i] =
            reader instanceof PerFieldPointsReader perField ? perField.routingReader() : reader;
      }
      return readers;
    }

    @Override
    public void finish() throws IOException {
      // only called after flushes; merges finish each writer themselves
      if (stockWriter != null) {
        stockWriter.finish();
      }
      if (splitWriter != null) {
        splitWriter.finish();
      }
    }

    @Override
    public void close() throws IOException {
      IOUtils.close(stockWriter, splitWriter);
    }
  }

  /**
   * Reads every points field of a segment through the reader of the format its attributes name.
   *
   * @lucene.experimental
   */
  public static final class PerFieldPointsReader extends PointsReader {
    private final FieldInfos fieldInfos;
    private final IntObjectHashMap<PointsReader> fields = new IntObjectHashMap<>();
    // segment suffix -> reader
    private final Map<String, PointsReader> formats = new LinkedHashMap<>();
    // the reader of the stock files, null if no field is stock
    private final PointsReader stock;

    PerFieldPointsReader(SegmentReadState state) throws IOException {
      this.fieldInfos = state.fieldInfos;
      PointsReader stockReader = null;
      boolean success = false;
      try {
        for (FieldInfo fi : state.fieldInfos) {
          if (fi.getPointDimensionCount() == 0) {
            continue;
          }
          String formatName = fi.getAttribute(PER_FIELD_FORMAT_KEY);
          if (formatName == null) {
            formatName = STOCK_FORMAT_NAME;
          }
          PointsFormat format = readFormat(formatName);
          if (format == null) {
            throw new IllegalStateException(
                "unknown points format [" + formatName + "] for field [" + fi.name + "]");
          }
          final String segmentSuffix;
          if (format == STOCK) {
            segmentSuffix = state.segmentSuffix;
          } else {
            String suffix = fi.getAttribute(PER_FIELD_SUFFIX_KEY);
            if (suffix == null) {
              throw new IllegalStateException(
                  "missing attribute: " + PER_FIELD_SUFFIX_KEY + " for field: " + fi.name);
            }
            segmentSuffix =
                PerFieldDocValuesFormat.getFullSegmentSuffix(
                    state.segmentSuffix, PerFieldDocValuesFormat.getSuffix(formatName, suffix));
          }
          PointsReader reader = formats.get(segmentSuffix);
          if (reader == null) {
            reader =
                format == STOCK
                    ? format.fieldsReader(state)
                    : format.fieldsReader(new SegmentReadState(state, segmentSuffix));
            formats.put(segmentSuffix, reader);
            if (format == STOCK) {
              stockReader = reader;
            }
          }
          fields.put(fi.number, reader);
        }
        success = true;
      } finally {
        if (success == false) {
          IOUtils.closeWhileHandlingException(formats.values());
        }
      }
      this.stock = stockReader;
    }

    // merge instance
    private PerFieldPointsReader(PerFieldPointsReader other) {
      this.fieldInfos = other.fieldInfos;
      Map<PointsReader, PointsReader> oldToNew = new IdentityHashMap<>();
      for (Map.Entry<String, PointsReader> e : other.formats.entrySet()) {
        PointsReader mergeInstance = e.getValue().getMergeInstance();
        formats.put(e.getKey(), mergeInstance);
        oldToNew.put(e.getValue(), mergeInstance);
      }
      for (IntObjectHashMap.IntObjectCursor<PointsReader> e : other.fields) {
        fields.put(e.key, oldToNew.get(e.value));
      }
      this.stock = other.stock == null ? null : oldToNew.get(other.stock);
    }

    /** The reader of the stock files, or null if no field of the segment is stock. */
    public PointsReader stockDelegate() {
      return stock;
    }

    /** A reader whose {@link #getValues} returns each field's values, whatever its format. */
    public PointsReader routingReader() {
      return this;
    }

    @Override
    public PointValues getValues(String field) throws IOException {
      FieldInfo fi = fieldInfos.fieldInfo(field);
      if (fi == null) {
        throw new IllegalArgumentException("field=\"" + field + "\" is unrecognized");
      }
      if (fi.getPointDimensionCount() == 0) {
        throw new IllegalArgumentException("field=\"" + field + "\" did not index point values");
      }
      PointsReader reader = fields.get(fi.number);
      return reader == null ? null : reader.getValues(field);
    }

    @Override
    public void checkIntegrity() throws IOException {
      for (PointsReader reader : formats.values()) {
        reader.checkIntegrity();
      }
    }

    @Override
    public PointsReader getMergeInstance() {
      return new PerFieldPointsReader(this);
    }

    @Override
    public void close() throws IOException {
      IOUtils.close(formats.values());
    }

    @Override
    public String toString() {
      return "PerFieldPoints(formats=" + formats.size() + ")";
    }
  }
}
