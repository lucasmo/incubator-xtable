/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
 
package io.onetable.hudi;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import lombok.Value;

import com.google.common.base.Preconditions;

import io.onetable.model.schema.PartitionTransformType;
import io.onetable.reflection.ReflectionUtils;

/** Configuration of Hudi source format for the sync process. */
@Value
public class HudiSourceConfigImpl {
  public static final String PARTITION_SPEC_EXTRACTOR_CLASS =
      "onetable.hudi.source.partition_spec_extractor_class";
  public static final String PARTITION_FIELD_SPEC_CONFIG =
      "onetable.hudi.source.partition_field_spec_config";

  String partitionSpecExtractorClass;
  List<PartitionFieldSpec> partitionFieldSpecs;

  public static HudiSourceConfigImpl fromPartitionFieldSpecConfig(String partitionFieldSpecConfig) {
    return new HudiSourceConfigImpl(
        ConfigurationBasedPartitionSpecExtractor.class.getName(),
        parsePartitionFieldSpecs(partitionFieldSpecConfig));
  }

  public static HudiSourceConfigImpl fromProperties(Map<String, String> properties) {
    String partitionSpecExtractorClass =
        properties.getOrDefault(
            PARTITION_SPEC_EXTRACTOR_CLASS,
            ConfigurationBasedPartitionSpecExtractor.class.getName());
    String partitionFieldSpecString = properties.get(PARTITION_FIELD_SPEC_CONFIG);
    List<PartitionFieldSpec> partitionFieldSpecs =
        parsePartitionFieldSpecs(partitionFieldSpecString);
    return new HudiSourceConfigImpl(partitionSpecExtractorClass, partitionFieldSpecs);
  }

  @Value
  static class PartitionFieldSpec {
    String sourceFieldPath;
    PartitionTransformType transformType;
    String format;
  }

  private static List<PartitionFieldSpec> parsePartitionFieldSpecs(String input) {
    if (input == null || input.isEmpty()) {
      return Collections.emptyList();
    }
    String[] perFieldConfigs = input.split(",");
    List<PartitionFieldSpec> partitionFields = new ArrayList<>(perFieldConfigs.length);
    for (String fieldConfig : perFieldConfigs) {
      String[] parts = fieldConfig.split(":");
      String path = parts[0];
      PartitionTransformType type =
          PartitionTransformType.valueOf(parts[1].toUpperCase(Locale.ROOT));
      String format = parts.length == 3 ? parts[2] : null;

      partitionFields.add(new PartitionFieldSpec(path, type, format));
    }
    return partitionFields;
  }

  public HudiSourcePartitionSpecExtractor loadSourcePartitionSpecExtractor() {
    Preconditions.checkNotNull(
        partitionSpecExtractorClass, "HudiSourcePartitionSpecExtractor class not provided");
    return ReflectionUtils.createInstanceOfClass(partitionSpecExtractorClass, this);
  }
}
