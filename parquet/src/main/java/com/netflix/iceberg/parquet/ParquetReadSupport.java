/*
 * Copyright 2017 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.netflix.iceberg.parquet;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Sets;
import com.netflix.iceberg.Schema;
import com.netflix.iceberg.avro.AvroSchemaUtil;
import org.apache.hadoop.conf.Configuration;
import org.apache.parquet.avro.AvroReadSupport;
import org.apache.parquet.hadoop.api.InitContext;
import org.apache.parquet.hadoop.api.ReadSupport;
import org.apache.parquet.io.api.RecordMaterializer;
import org.apache.parquet.schema.MessageType;
import org.apache.parquet.schema.Type;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Parquet {@link ReadSupport} that handles column projection based on {@link Schema} column IDs.
 *
 * @param <T> Java type produced by this read support instance
 */
class ParquetReadSupport<T> extends ReadSupport<T> {
  private final Schema expectedSchema;
  private final ReadSupport<T> wrapped;
  private final boolean callInit;

  ParquetReadSupport(Schema expectedSchema, ReadSupport<T> readSupport, boolean callInit) {
    this.expectedSchema = expectedSchema;
    this.wrapped = readSupport;
    this.callInit = callInit;
  }

  @Override
  @SuppressWarnings("deprecation")
  public ReadContext init(Configuration configuration, Map<String, String> keyValueMetaData, MessageType fileSchema) {
    // Columns are selected from the Parquet file by taking the read context's message type and
    // matching to the file's columns by full path, so this must select columns by using the path
    // in the file's schema.

    List<Type> fileFields = fileSchema.getFields();
    MessageType projection;
    if (fileFields.size() > 0 && fileFields.get(0).getId() != null) {
      projection = ParquetSchemaUtil.pruneColumns(fileSchema, expectedSchema);
    } else {
      // the file was written without field IDs
      projection = ParquetSchemaUtil.pruneColumnsFallback(fileSchema, expectedSchema);
    }

    // override some known backward-compatibility options
    configuration.set("parquet.strict.typing", "false");
    configuration.set("parquet.avro.add-list-element-records", "false");
    configuration.set("parquet.avro.write-old-list-structure", "false");

    // set Avro schemas in case the reader is Avro
    AvroReadSupport.setRequestedProjection(configuration,
        AvroSchemaUtil.convert(expectedSchema, projection.getName()));
    org.apache.avro.Schema avroReadSchema = AvroSchemaUtil.buildAvroProjection(
        AvroSchemaUtil.convert(ParquetSchemaUtil.convert(projection), projection.getName()),
        expectedSchema, ImmutableMap.of());
    AvroReadSupport.setAvroReadSchema(configuration, ParquetAvro.parquetAvroSchema(avroReadSchema));

    // let the context set up read support metadata, but always use the correct projection
    ReadContext context = null;
    if (callInit) {
      try {
        context = wrapped.init(configuration, keyValueMetaData, projection);
      } catch (UnsupportedOperationException e) {
        // try the InitContext version
        context = wrapped.init(new InitContext(
            configuration, makeMultimap(keyValueMetaData), projection));
      }
    }

    return new ReadContext(projection,
        context != null ? context.getReadSupportMetadata() : ImmutableMap.of());
  }

  @Override
  public RecordMaterializer<T> prepareForRead(Configuration configuration,
                                              Map<String, String> fileMetadata,
                                              MessageType fileMessageType,
                                              ReadContext readContext) {
    // This is the type created in init that was based on the file's schema. The schema that this
    // will pass to the wrapped ReadSupport needs to match the expected schema's names. Rather than
    // renaming the file's schema, convert the expected schema to Parquet. This relies on writing
    // files with the correct schema.
    // TODO: this breaks when columns are reordered.
    MessageType readSchema = ParquetSchemaUtil.convert(expectedSchema, fileMessageType.getName());
    return wrapped.prepareForRead(configuration, fileMetadata, readSchema, readContext);
  }

  private Map<String, Set<String>> makeMultimap(Map<String, String> map) {
    ImmutableMap.Builder<String, Set<String>> builder = ImmutableMap.builder();
    for (Map.Entry<String, String> entry : map.entrySet()) {
      builder.put(entry.getKey(), Sets.newHashSet(entry.getValue()));
    }
    return builder.build();
  }
}
