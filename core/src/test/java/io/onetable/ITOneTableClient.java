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
 
package io.onetable;

import static io.onetable.GenericTable.getTableName;
import static io.onetable.hudi.HudiTestUtil.PartitionConfig;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import org.apache.spark.SparkConf;
import org.apache.spark.api.java.JavaSparkContext;
import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.SparkSession;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

import org.apache.hudi.client.HoodieReadClient;
import org.apache.hudi.common.config.HoodieMetadataConfig;
import org.apache.hudi.common.model.HoodieAvroPayload;
import org.apache.hudi.common.model.HoodieRecord;
import org.apache.hudi.common.model.HoodieTableType;
import org.apache.hudi.common.table.timeline.HoodieInstant;

import org.apache.iceberg.Table;
import org.apache.iceberg.hadoop.HadoopTables;

import org.apache.spark.sql.delta.DeltaLog;

import com.google.common.collect.ImmutableList;

import io.onetable.client.OneTableClient;
import io.onetable.client.PerTableConfig;
import io.onetable.client.SourceClientProvider;
import io.onetable.delta.DeltaSourceClientProvider;
import io.onetable.hudi.HudiSourceClientProvider;
import io.onetable.hudi.HudiSourceConfig;
import io.onetable.hudi.HudiTestUtil;
import io.onetable.model.storage.TableFormat;
import io.onetable.model.sync.SyncMode;
import io.onetable.model.sync.SyncResult;

public class ITOneTableClient {
  @TempDir public static Path tempDir;
  private static final DateTimeFormatter DATE_FORMAT =
      DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS").withZone(ZoneId.of("UTC"));

  private static JavaSparkContext jsc;
  private static SparkSession sparkSession;
  private SourceClientProvider<HoodieInstant> hudiSourceClientProvider;
  private SourceClientProvider<Long> deltaSourceClientProvider;

  @BeforeAll
  public static void setupOnce() {
    SparkConf sparkConf = HudiTestUtil.getSparkConf(tempDir);
    sparkSession =
        SparkSession.builder().config(HoodieReadClient.addHoodieSupport(sparkConf)).getOrCreate();
    sparkSession
        .sparkContext()
        .hadoopConfiguration()
        .set("parquet.avro.write-old-list-structure", "false");
    jsc = JavaSparkContext.fromSparkContext(sparkSession.sparkContext());
  }

  @BeforeEach
  public void setup() {
    hudiSourceClientProvider = new HudiSourceClientProvider();
    hudiSourceClientProvider.init(jsc.hadoopConfiguration(), Collections.emptyMap());
    deltaSourceClientProvider = new DeltaSourceClientProvider();
    deltaSourceClientProvider.init(jsc.hadoopConfiguration(), Collections.emptyMap());
  }

  @AfterAll
  public static void teardown() {
    if (jsc != null) {
      jsc.close();
    }
    if (sparkSession != null) {
      sparkSession.close();
    }
  }

  private static Stream<Arguments> testCasesWithPartitioningAndSyncModes() {
    return addBasicPartitionCases(testCasesWithSyncModes());
  }

  private static Stream<Arguments> generateTestParametersForFormatsSyncModesAndPartitioning() {
    List<Arguments> arguments = new ArrayList<>();
    for (TableFormat sourceTableFormat : Arrays.asList(TableFormat.HUDI, TableFormat.DELTA)) {
      for (SyncMode syncMode : SyncMode.values()) {
        for (boolean isPartitioned : new boolean[] {true, false}) {
          arguments.add(Arguments.of(sourceTableFormat, syncMode, isPartitioned));
        }
      }
    }
    return arguments.stream();
  }

  private static Stream<Arguments> testCasesWithPartitioningAndTableTypesAndSyncModes() {
    return addBasicPartitionCases(testCasesWithTableTypesAndSyncModes());
  }

  private static Stream<Arguments> testCasesWithTableTypesAndSyncModes() {
    return addTableTypeCases(testCasesWithSyncModes());
  }

  private static Stream<Arguments> testCasesWithSyncModes() {
    return addSyncModeCases(
        Stream.of(Arguments.of(Arrays.asList(TableFormat.ICEBERG, TableFormat.DELTA))));
  }

  private SourceClientProvider<?> getSourceClientProvider(TableFormat sourceTableFormat) {
    if (sourceTableFormat == TableFormat.HUDI) {
      return hudiSourceClientProvider;
    } else if (sourceTableFormat == TableFormat.DELTA) {
      return deltaSourceClientProvider;
    } else {
      throw new IllegalArgumentException("Unsupported source format: " + sourceTableFormat);
    }
  }

  /*
   * This test has the following steps at a high level.
   * 1. Insert few records.
   * 2. Upsert few records.
   * 3. Delete few records.
   * 4. Insert records with new columns.
   * 5. Insert records in a new partition if table is partitioned.
   * 6. drop a partition if table is partitioned.
   * 7. Insert records in the dropped partition again if table is partitioned.
   */
  @ParameterizedTest
  @MethodSource("generateTestParametersForFormatsSyncModesAndPartitioning")
  public void testVariousOperations(
      TableFormat sourceTableFormat, SyncMode syncMode, boolean isPartitioned) {
    String tableName = getTableName();
    OneTableClient oneTableClient = new OneTableClient(jsc.hadoopConfiguration());
    List<TableFormat> targetTableFormats = getOtherFormats(sourceTableFormat);
    String oneTablePartitionConfig = null;
    if (isPartitioned) {
      oneTablePartitionConfig = "level:VALUE";
    }
    SourceClientProvider<?> sourceClientProvider = getSourceClientProvider(sourceTableFormat);
    List<?> insertRecords;
    try (GenericTable table =
        GenericTable.getInstance(
            tableName, tempDir, sparkSession, jsc, sourceTableFormat, isPartitioned)) {
      insertRecords = table.insertRows(100);

      PerTableConfig perTableConfig =
          PerTableConfig.builder()
              .tableName(tableName)
              .targetTableFormats(targetTableFormats)
              .tableBasePath(table.getBasePath())
              .hudiSourceConfig(
                  HudiSourceConfig.builder()
                      .partitionFieldSpecConfig(oneTablePartitionConfig)
                      .build())
              .syncMode(syncMode)
              .build();
      oneTableClient.sync(perTableConfig, sourceClientProvider);
      checkDatasetEquivalence(sourceTableFormat, table, targetTableFormats, 100);

      table.insertRows(100);
      oneTableClient.sync(perTableConfig, sourceClientProvider);
      checkDatasetEquivalence(sourceTableFormat, table, targetTableFormats, 200);

      table.upsertRows(insertRecords.subList(0, 20));
      oneTableClient.sync(perTableConfig, sourceClientProvider);
      checkDatasetEquivalence(sourceTableFormat, table, targetTableFormats, 200);

      table.deleteRows(insertRecords.subList(30, 50));
      oneTableClient.sync(perTableConfig, sourceClientProvider);
      checkDatasetEquivalence(sourceTableFormat, table, targetTableFormats, 180);
    }

    try (GenericTable tableWithUpdatedSchema =
        GenericTable.getInstanceWithAdditionalColumns(
            tableName, tempDir, sparkSession, jsc, sourceTableFormat, isPartitioned)) {
      PerTableConfig perTableConfig =
          PerTableConfig.builder()
              .tableName(tableName)
              .targetTableFormats(targetTableFormats)
              .tableBasePath(tableWithUpdatedSchema.getBasePath())
              .hudiSourceConfig(
                  HudiSourceConfig.builder()
                      .partitionFieldSpecConfig(oneTablePartitionConfig)
                      .build())
              .syncMode(syncMode)
              .build();
      List<Row> insertsAfterSchemaUpdate = tableWithUpdatedSchema.insertRows(100);
      tableWithUpdatedSchema.reload();
      oneTableClient.sync(perTableConfig, sourceClientProvider);
      checkDatasetEquivalence(sourceTableFormat, tableWithUpdatedSchema, targetTableFormats, 280);

      tableWithUpdatedSchema.deleteRows(insertsAfterSchemaUpdate.subList(60, 90));
      oneTableClient.sync(perTableConfig, sourceClientProvider);
      checkDatasetEquivalence(sourceTableFormat, tableWithUpdatedSchema, targetTableFormats, 250);

      if (isPartitioned) {
        // Adds new partition.
        tableWithUpdatedSchema.insertRecordsForSpecialPartition(50);
        oneTableClient.sync(perTableConfig, sourceClientProvider);
        checkDatasetEquivalence(sourceTableFormat, tableWithUpdatedSchema, targetTableFormats, 300);

        // Drops partition.
        tableWithUpdatedSchema.deleteSpecialPartition();
        oneTableClient.sync(perTableConfig, sourceClientProvider);
        checkDatasetEquivalence(sourceTableFormat, tableWithUpdatedSchema, targetTableFormats, 250);

        // Insert records to the dropped partition again.
        tableWithUpdatedSchema.insertRecordsForSpecialPartition(50);
        oneTableClient.sync(perTableConfig, sourceClientProvider);
        checkDatasetEquivalence(sourceTableFormat, tableWithUpdatedSchema, targetTableFormats, 300);
      }
    }
  }

  @ParameterizedTest
  @MethodSource("testCasesWithPartitioningAndTableTypesAndSyncModes")
  public void testConcurrentInsertWritesInSource(
      List<TableFormat> targetTableFormats,
      SyncMode syncMode,
      HoodieTableType tableType,
      PartitionConfig partitionConfig) {
    String tableName = getTableName();
    try (TestJavaHudiTable table =
        TestJavaHudiTable.forStandardSchema(
            tableName, tempDir, partitionConfig.getHudiConfig(), tableType)) {
      // commit time 1 starts first but ends 2nd.
      // commit time 2 starts second but ends 1st.
      List<HoodieRecord<HoodieAvroPayload>> insertsForCommit1 = table.generateRecords(50);
      List<HoodieRecord<HoodieAvroPayload>> insertsForCommit2 = table.generateRecords(50);
      String commitInstant1 = table.startCommit();

      String commitInstant2 = table.startCommit();
      table.insertRecordsWithCommitAlreadyStarted(insertsForCommit2, commitInstant2, true);

      PerTableConfig perTableConfig =
          PerTableConfig.builder()
              .tableName(tableName)
              .targetTableFormats(targetTableFormats)
              .tableBasePath(table.getBasePath())
              .hudiSourceConfig(
                  HudiSourceConfig.builder()
                      .partitionFieldSpecConfig(partitionConfig.getOneTableConfig())
                      .build())
              .syncMode(syncMode)
              .build();
      OneTableClient oneTableClient = new OneTableClient(jsc.hadoopConfiguration());
      oneTableClient.sync(perTableConfig, hudiSourceClientProvider);

      checkDatasetEquivalence(TableFormat.HUDI, table, targetTableFormats, 50);
      table.insertRecordsWithCommitAlreadyStarted(insertsForCommit1, commitInstant1, true);
      oneTableClient.sync(perTableConfig, hudiSourceClientProvider);
      checkDatasetEquivalence(TableFormat.HUDI, table, targetTableFormats, 100);
    }
  }

  @ParameterizedTest
  @MethodSource("testCasesWithPartitioningAndSyncModes")
  public void testConcurrentInsertsAndTableServiceWrites(
      List<TableFormat> targetTableFormats, SyncMode syncMode, PartitionConfig partitionConfig) {
    HoodieTableType tableType = HoodieTableType.MERGE_ON_READ;

    String tableName = getTableName();
    try (TestSparkHudiTable table =
        TestSparkHudiTable.forStandardSchema(
            tableName, tempDir, jsc, partitionConfig.getHudiConfig(), tableType)) {
      List<HoodieRecord<HoodieAvroPayload>> insertedRecords1 = table.insertRecords(50, true);

      PerTableConfig perTableConfig =
          PerTableConfig.builder()
              .tableName(tableName)
              .targetTableFormats(targetTableFormats)
              .tableBasePath(table.getBasePath())
              .hudiSourceConfig(
                  HudiSourceConfig.builder()
                      .partitionFieldSpecConfig(partitionConfig.getOneTableConfig())
                      .build())
              .syncMode(syncMode)
              .build();
      OneTableClient oneTableClient = new OneTableClient(jsc.hadoopConfiguration());
      oneTableClient.sync(perTableConfig, hudiSourceClientProvider);
      checkDatasetEquivalence(TableFormat.HUDI, table, targetTableFormats, 50);

      table.deleteRecords(insertedRecords1.subList(0, 20), true);
      // At this point table should have 30 records but only after compaction.
      String scheduledCompactionInstant = table.onlyScheduleCompaction();

      table.insertRecords(50, true);
      oneTableClient.sync(perTableConfig, hudiSourceClientProvider);
      Map<String, String> sourceHudiOptions =
          Collections.singletonMap("hoodie.datasource.query.type", "read_optimized");
      // Because compaction is not completed yet and read optimized query, there are 100 records.
      checkDatasetEquivalence(
          TableFormat.HUDI,
          table,
          sourceHudiOptions,
          targetTableFormats,
          Collections.emptyMap(),
          100);

      table.insertRecords(50, true);
      oneTableClient.sync(perTableConfig, hudiSourceClientProvider);
      // Because compaction is not completed yet and read optimized query, there are 150 records.
      checkDatasetEquivalence(
          TableFormat.HUDI,
          table,
          sourceHudiOptions,
          targetTableFormats,
          Collections.emptyMap(),
          150);

      table.completeScheduledCompaction(scheduledCompactionInstant);
      oneTableClient.sync(perTableConfig, hudiSourceClientProvider);
      checkDatasetEquivalence(TableFormat.HUDI, table, targetTableFormats, 130);
    }
  }

  @ParameterizedTest
  @EnumSource(
      value = TableFormat.class,
      names = {"HUDI", "DELTA"})
  public void testTimeTravelQueries(TableFormat sourceTableFormat) throws Exception {
    String tableName = getTableName();
    try (GenericTable table =
        GenericTable.getInstance(tableName, tempDir, sparkSession, jsc, sourceTableFormat, false)) {
      table.insertRows(50);
      List<TableFormat> targetTableFormats = getOtherFormats(sourceTableFormat);
      PerTableConfig perTableConfig =
          PerTableConfig.builder()
              .tableName(tableName)
              .targetTableFormats(targetTableFormats)
              .tableBasePath(table.getBasePath())
              .syncMode(SyncMode.INCREMENTAL)
              .build();
      SourceClientProvider<?> sourceClientProvider = getSourceClientProvider(sourceTableFormat);
      OneTableClient oneTableClient = new OneTableClient(jsc.hadoopConfiguration());
      oneTableClient.sync(perTableConfig, sourceClientProvider);
      Instant instantAfterFirstSync = Instant.now();
      // sleep before starting the next commit to avoid any rounding issues
      Thread.sleep(1000);

      table.insertRows(50);
      oneTableClient.sync(perTableConfig, sourceClientProvider);
      Instant instantAfterSecondSync = Instant.now();
      // sleep before starting the next commit to avoid any rounding issues
      Thread.sleep(1000);

      table.insertRows(50);
      oneTableClient.sync(perTableConfig, sourceClientProvider);

      checkDatasetEquivalence(
          sourceTableFormat,
          table,
          getTimeTravelOption(sourceTableFormat, instantAfterFirstSync),
          targetTableFormats,
          targetTableFormats.stream()
              .collect(
                  Collectors.toMap(
                      Function.identity(),
                      targetTableFormat ->
                          getTimeTravelOption(targetTableFormat, instantAfterFirstSync))),
          50);
      checkDatasetEquivalence(
          sourceTableFormat,
          table,
          getTimeTravelOption(sourceTableFormat, instantAfterSecondSync),
          targetTableFormats,
          targetTableFormats.stream()
              .collect(
                  Collectors.toMap(
                      Function.identity(),
                      targetTableFormat ->
                          getTimeTravelOption(targetTableFormat, instantAfterSecondSync))),
          100);
    }
  }

  @Test
  public void fallBackToSnapshotSyncWhenIncrementalSyncIsNotSufficient() {
    HoodieTableType tableType = HoodieTableType.COPY_ON_WRITE;
    PartitionConfig partitionConfig = PartitionConfig.of(null, null);
    String tableName = getTableName();
    try (TestJavaHudiTable table =
        TestJavaHudiTable.forStandardSchema(
            tableName, tempDir, partitionConfig.getHudiConfig(), tableType)) {
      PerTableConfig perTableConfig =
          PerTableConfig.builder()
              .tableName(tableName)
              .targetTableFormats(Arrays.asList(TableFormat.ICEBERG, TableFormat.DELTA))
              .tableBasePath(table.getBasePath())
              .syncMode(SyncMode.INCREMENTAL)
              .build();
      OneTableClient oneTableClient = new OneTableClient(jsc.hadoopConfiguration());

      List<HoodieRecord<HoodieAvroPayload>> insertsForCommit1 = table.insertRecords(100, true);
      oneTableClient.sync(perTableConfig, hudiSourceClientProvider);
      checkDatasetEquivalence(
          TableFormat.HUDI, table, Arrays.asList(TableFormat.ICEBERG, TableFormat.DELTA), 100);

      table.deleteRecords(insertsForCommit1.subList(30, 70), true);

      table.insertRecords(100, true);

      table.clean(); // This cleans older file slices from commitInstant1.
      oneTableClient.sync(perTableConfig, hudiSourceClientProvider);
      checkDatasetEquivalence(
          TableFormat.HUDI, table, Arrays.asList(TableFormat.ICEBERG, TableFormat.DELTA), 160);
    }
  }

  private static List<TableFormat> getOtherFormats(TableFormat sourceTableFormat) {
    return Arrays.stream(TableFormat.values())
        .filter(format -> !format.equals(sourceTableFormat))
        .collect(Collectors.toList());
  }

  private static Stream<Arguments> provideArgsForPartitionTesting() {
    String timestampFilter =
        String.format(
            "timestamp_micros_nullable_field < timestamp_millis(%s)",
            Instant.now().truncatedTo(ChronoUnit.DAYS).minus(2, ChronoUnit.DAYS).toEpochMilli());
    String levelFilter = "level = 'INFO'";
    String nestedLevelFilter = "nested_record.level = 'INFO'";
    String severityFilter = "severity = 1";
    String timestampAndLevelFilter = String.format("%s and %s", timestampFilter, levelFilter);
    return Stream.of(
        Arguments.of(
            Arrays.asList(TableFormat.ICEBERG, TableFormat.DELTA),
            "level:VALUE",
            "level:SIMPLE",
            levelFilter),
        Arguments.of(
            // Delta Lake does not currently support nested partition columns
            Arrays.asList(TableFormat.ICEBERG),
            "nested_record.level:VALUE",
            "nested_record.level:SIMPLE",
            nestedLevelFilter),
        Arguments.of(
            Arrays.asList(TableFormat.ICEBERG, TableFormat.DELTA),
            "level:VALUE",
            "level:SIMPLE",
            levelFilter),
        Arguments.of(
            Arrays.asList(TableFormat.ICEBERG, TableFormat.DELTA),
            "severity:VALUE",
            "severity:SIMPLE",
            severityFilter),
        Arguments.of(
            Arrays.asList(TableFormat.ICEBERG, TableFormat.DELTA),
            "timestamp_micros_nullable_field:DAY:yyyy/MM/dd,level:VALUE",
            "timestamp_micros_nullable_field:TIMESTAMP,level:SIMPLE",
            timestampAndLevelFilter));
  }

  @ParameterizedTest
  @MethodSource("provideArgsForPartitionTesting")
  public void testPartitionedData(
      List<TableFormat> targetTableFormats,
      String oneTablePartitionConfig,
      String hudiPartitionConfig,
      String filter) {
    String tableName = getTableName();
    try (TestJavaHudiTable table =
        TestJavaHudiTable.forStandardSchema(
            tableName, tempDir, hudiPartitionConfig, HoodieTableType.COPY_ON_WRITE)) {
      PerTableConfig perTableConfig =
          PerTableConfig.builder()
              .tableName(tableName)
              .targetTableFormats(targetTableFormats)
              .tableBasePath(table.getBasePath())
              .hudiSourceConfig(
                  HudiSourceConfig.builder()
                      .partitionFieldSpecConfig(oneTablePartitionConfig)
                      .build())
              .syncMode(SyncMode.INCREMENTAL)
              .build();
      table.insertRecords(100, true);
      OneTableClient oneTableClient = new OneTableClient(jsc.hadoopConfiguration());
      oneTableClient.sync(perTableConfig, hudiSourceClientProvider);
      // Do a second sync to force the test to read back the metadata it wrote earlier
      table.insertRecords(100, true);
      oneTableClient.sync(perTableConfig, hudiSourceClientProvider);

      checkDatasetEquivalenceWithFilter(TableFormat.HUDI, table, targetTableFormats, filter);
    }
  }

  @ParameterizedTest
  @EnumSource(value = SyncMode.class)
  public void testSyncWithSingleFormat(SyncMode syncMode) {
    String tableName = getTableName();
    try (TestJavaHudiTable table =
        TestJavaHudiTable.forStandardSchema(
            tableName, tempDir, null, HoodieTableType.COPY_ON_WRITE)) {
      table.insertRecords(100, true);

      PerTableConfig perTableConfigIceberg =
          PerTableConfig.builder()
              .tableName(tableName)
              .targetTableFormats(ImmutableList.of(TableFormat.ICEBERG))
              .tableBasePath(table.getBasePath())
              .syncMode(syncMode)
              .build();

      PerTableConfig perTableConfigDelta =
          PerTableConfig.builder()
              .tableName(tableName)
              .targetTableFormats(ImmutableList.of(TableFormat.DELTA))
              .tableBasePath(table.getBasePath())
              .syncMode(syncMode)
              .build();

      OneTableClient oneTableClient = new OneTableClient(jsc.hadoopConfiguration());
      oneTableClient.sync(perTableConfigIceberg, hudiSourceClientProvider);
      checkDatasetEquivalence(
          TableFormat.HUDI, table, Collections.singletonList(TableFormat.ICEBERG), 100);
      oneTableClient.sync(perTableConfigDelta, hudiSourceClientProvider);
      checkDatasetEquivalence(
          TableFormat.HUDI, table, Collections.singletonList(TableFormat.DELTA), 100);

      table.insertRecords(100, true);
      oneTableClient.sync(perTableConfigIceberg, hudiSourceClientProvider);
      checkDatasetEquivalence(
          TableFormat.HUDI, table, Collections.singletonList(TableFormat.ICEBERG), 200);
      oneTableClient.sync(perTableConfigDelta, hudiSourceClientProvider);
      checkDatasetEquivalence(
          TableFormat.HUDI, table, Collections.singletonList(TableFormat.DELTA), 200);
    }
  }

  @Test
  public void testSyncForInvalidPerTableConfig() {
    String tableName = getTableName();
    try (TestJavaHudiTable table =
        TestJavaHudiTable.forStandardSchema(
            tableName, tempDir, "level:SIMPLE", HoodieTableType.COPY_ON_WRITE)) {
      table.insertRecords(100, true);

      PerTableConfig perTableConfig =
          PerTableConfig.builder()
              .tableName(tableName)
              .targetTableFormats(ImmutableList.of())
              .tableBasePath(table.getBasePath())
              .build();
      OneTableClient oneTableClient = new OneTableClient(jsc.hadoopConfiguration());

      assertThrows(
          IllegalArgumentException.class,
          () -> oneTableClient.sync(perTableConfig, hudiSourceClientProvider));
    }
  }

  @Test
  public void testOutOfSyncIncrementalSyncs() {
    String tableName = getTableName();
    try (TestJavaHudiTable table =
        TestJavaHudiTable.forStandardSchema(
            tableName, tempDir, null, HoodieTableType.COPY_ON_WRITE)) {
      PerTableConfig singleTableConfig =
          PerTableConfig.builder()
              .tableName(tableName)
              .targetTableFormats(ImmutableList.of(TableFormat.ICEBERG))
              .tableBasePath(table.getBasePath())
              .syncMode(SyncMode.INCREMENTAL)
              .build();

      PerTableConfig dualTableConfig =
          PerTableConfig.builder()
              .tableName(tableName)
              .targetTableFormats(Arrays.asList(TableFormat.ICEBERG, TableFormat.DELTA))
              .tableBasePath(table.getBasePath())
              .syncMode(SyncMode.INCREMENTAL)
              .build();

      table.insertRecords(50, true);
      OneTableClient oneTableClient = new OneTableClient(jsc.hadoopConfiguration());
      // sync iceberg only
      oneTableClient.sync(singleTableConfig, hudiSourceClientProvider);
      checkDatasetEquivalence(
          TableFormat.HUDI, table, Collections.singletonList(TableFormat.ICEBERG), 50);
      // insert more records
      table.insertRecords(50, true);
      // iceberg will be an incremental sync and delta will need to bootstrap with snapshot sync
      oneTableClient.sync(dualTableConfig, hudiSourceClientProvider);
      checkDatasetEquivalence(
          TableFormat.HUDI, table, Arrays.asList(TableFormat.ICEBERG, TableFormat.DELTA), 100);

      // insert more records
      table.insertRecords(50, true);
      // insert more records
      table.insertRecords(50, true);
      // incremental sync for two commits for iceberg only
      oneTableClient.sync(singleTableConfig, hudiSourceClientProvider);
      checkDatasetEquivalence(
          TableFormat.HUDI, table, Collections.singletonList(TableFormat.ICEBERG), 200);

      // insert more records
      table.insertRecords(50, true);
      // incremental sync for one commit for iceberg and three commits for delta
      oneTableClient.sync(dualTableConfig, hudiSourceClientProvider);
      checkDatasetEquivalence(
          TableFormat.HUDI, table, Arrays.asList(TableFormat.ICEBERG, TableFormat.DELTA), 250);
    }
  }

  @Test
  public void testMetadataRetention() {
    String tableName = getTableName();
    try (TestJavaHudiTable table =
        TestJavaHudiTable.forStandardSchema(
            tableName, tempDir, null, HoodieTableType.COPY_ON_WRITE)) {
      PerTableConfig perTableConfig =
          PerTableConfig.builder()
              .tableName(tableName)
              .targetTableFormats(Arrays.asList(TableFormat.ICEBERG, TableFormat.DELTA))
              .tableBasePath(table.getBasePath())
              .syncMode(SyncMode.INCREMENTAL)
              .targetMetadataRetentionInHours(0) // force cleanup
              .build();
      OneTableClient oneTableClient = new OneTableClient(jsc.hadoopConfiguration());
      table.insertRecords(10, true);
      oneTableClient.sync(perTableConfig, hudiSourceClientProvider);
      // later we will ensure we can still read the source table at this instant to ensure that
      // neither target cleaned up the underlying parquet files in the table
      Instant instantAfterFirstCommit = Instant.now();
      // create 5 total commits to ensure Delta Log cleanup is
      IntStream.range(0, 4)
          .forEach(
              unused -> {
                table.insertRecords(10, true);
                oneTableClient.sync(perTableConfig, hudiSourceClientProvider);
              });
      // ensure that hudi rows can still be read and underlying files were not removed
      List<Row> rows =
          sparkSession
              .read()
              .format("hudi")
              .options(getTimeTravelOption(TableFormat.HUDI, instantAfterFirstCommit))
              .load(table.getBasePath())
              .collectAsList();
      Assertions.assertEquals(10, rows.size());
      // check snapshots retained in iceberg is under 4
      Table icebergTable = new HadoopTables().load(table.getBasePath());
      int snapshotCount =
          (int) StreamSupport.stream(icebergTable.snapshots().spliterator(), false).count();
      Assertions.assertEquals(1, snapshotCount);
      // assert that proper settings are enabled for delta log
      DeltaLog deltaLog = DeltaLog.forTable(sparkSession, table.getBasePath());
      Assertions.assertTrue(deltaLog.enableExpiredLogCleanup());
    }
  }

  private Map<String, String> getTimeTravelOption(TableFormat tableFormat, Instant time) {
    Map<String, String> options = new HashMap<>();
    switch (tableFormat) {
      case HUDI:
        options.put("as.of.instant", DATE_FORMAT.format(time));
        break;
      case ICEBERG:
        options.put("as-of-timestamp", String.valueOf(time.toEpochMilli()));
        break;
      case DELTA:
        options.put("timestampAsOf", DATE_FORMAT.format(time));
        break;
      default:
        throw new IllegalArgumentException("Unknown table format: " + tableFormat);
    }
    return options;
  }

  private void checkDatasetEquivalenceWithFilter(
      TableFormat sourceFormat,
      GenericTable<?, ?> sourceTable,
      List<TableFormat> targetFormats,
      String filter) {
    checkDatasetEquivalence(
        sourceFormat,
        sourceTable,
        Collections.emptyMap(),
        targetFormats,
        Collections.emptyMap(),
        null,
        filter);
  }

  private void checkDatasetEquivalence(
      TableFormat sourceFormat,
      GenericTable<?, ?> sourceTable,
      List<TableFormat> targetFormats,
      Integer expectedCount) {
    checkDatasetEquivalence(
        sourceFormat,
        sourceTable,
        Collections.emptyMap(),
        targetFormats,
        Collections.emptyMap(),
        expectedCount,
        "1 = 1");
  }

  private void checkDatasetEquivalence(
      TableFormat sourceFormat,
      GenericTable<?, ?> sourceTable,
      Map<String, String> sourceOptions,
      List<TableFormat> targetFormats,
      Map<TableFormat, Map<String, String>> targetOptions,
      Integer expectedCount) {
    checkDatasetEquivalence(
        sourceFormat,
        sourceTable,
        sourceOptions,
        targetFormats,
        targetOptions,
        expectedCount,
        "1 = 1");
  }

  private void checkDatasetEquivalence(
      TableFormat sourceFormat,
      GenericTable<?, ?> sourceTable,
      Map<String, String> sourceOptions,
      List<TableFormat> targetFormats,
      Map<TableFormat, Map<String, String>> targetOptions,
      Integer expectedCount,
      String filterCondition) {
    Dataset<Row> sourceRows =
        sparkSession
            .read()
            .options(sourceOptions)
            .format(sourceFormat.name().toLowerCase())
            .load(sourceTable.getBasePath())
            .orderBy(sourceTable.getOrderByColumn())
            .filter(filterCondition);
    Map<TableFormat, Dataset<Row>> targetRowsByFormat =
        targetFormats.stream()
            .collect(
                Collectors.toMap(
                    Function.identity(),
                    targetFormat -> {
                      Map<String, String> finalTargetOptions =
                          targetOptions.getOrDefault(targetFormat, Collections.emptyMap());
                      if (targetFormat.equals(TableFormat.HUDI)) {
                        finalTargetOptions = new HashMap<>(finalTargetOptions);
                        finalTargetOptions.put(HoodieMetadataConfig.ENABLE.key(), "true");
                        finalTargetOptions.put(
                            "hoodie.datasource.read.extract.partition.values.from.path", "true");
                      }
                      return sparkSession
                          .read()
                          .options(finalTargetOptions)
                          .format(targetFormat.name().toLowerCase())
                          .load(sourceTable.getBasePath())
                          .orderBy(sourceTable.getOrderByColumn())
                          .filter(filterCondition);
                    }));

    String[] selectColumnsArr = sourceTable.getColumnsToSelect().toArray(new String[] {});
    List<String> dataset1Rows = sourceRows.selectExpr(selectColumnsArr).toJSON().collectAsList();
    targetRowsByFormat.forEach(
        (format, targetRows) -> {
          List<String> dataset2Rows =
              targetRows.selectExpr(selectColumnsArr).toJSON().collectAsList();
          assertEquals(
              dataset1Rows.size(),
              dataset2Rows.size(),
              String.format(
                  "Datasets have different row counts when reading from Spark. Source: %s, Target: %s",
                  sourceFormat, format));
          // sanity check the count to ensure test is set up properly
          if (expectedCount != null) {
            assertEquals(expectedCount, dataset1Rows.size());
          } else {
            // if count is not known ahead of time, ensure datasets are non-empty
            assertFalse(dataset1Rows.isEmpty());
          }
          assertEquals(
              dataset1Rows,
              dataset2Rows,
              String.format(
                  "Datasets are not equivalent when reading from Spark. Source: %s, Target: %s",
                  sourceFormat, format));
        });
  }

  private void assertNoSyncFailures(Map<TableFormat, SyncResult> results) {
    Assertions.assertTrue(
        results.values().stream()
            .noneMatch(
                result -> result.getStatus().getStatusCode() != SyncResult.SyncStatusCode.SUCCESS));
  }

  private static Stream<Arguments> addSyncModeCases(Stream<Arguments> arguments) {
    return arguments.flatMap(
        args -> {
          Object[] snapshotArgs = Arrays.copyOf(args.get(), args.get().length + 1);
          snapshotArgs[snapshotArgs.length - 1] = SyncMode.FULL;
          Object[] incrementalArgs = Arrays.copyOf(args.get(), args.get().length + 1);
          incrementalArgs[incrementalArgs.length - 1] = SyncMode.INCREMENTAL;
          return Stream.of(Arguments.arguments(snapshotArgs), Arguments.arguments(incrementalArgs));
        });
  }

  private static Stream<Arguments> addTableTypeCases(Stream<Arguments> arguments) {
    return arguments.flatMap(
        args -> {
          Object[] morArgs = Arrays.copyOf(args.get(), args.get().length + 1);
          morArgs[morArgs.length - 1] = HoodieTableType.MERGE_ON_READ;
          Object[] cowArgs = Arrays.copyOf(args.get(), args.get().length + 1);
          cowArgs[cowArgs.length - 1] = HoodieTableType.COPY_ON_WRITE;
          return Stream.of(Arguments.arguments(morArgs), Arguments.arguments(cowArgs));
        });
  }

  private static Stream<Arguments> addBasicPartitionCases(Stream<Arguments> arguments) {
    // add unpartitioned and partitioned cases
    return arguments.flatMap(
        args -> {
          Object[] unpartitionedArgs = Arrays.copyOf(args.get(), args.get().length + 1);
          unpartitionedArgs[unpartitionedArgs.length - 1] = PartitionConfig.of(null, null);
          Object[] partitionedArgs = Arrays.copyOf(args.get(), args.get().length + 1);
          partitionedArgs[partitionedArgs.length - 1] =
              PartitionConfig.of("level:SIMPLE", "level:VALUE");
          return Stream.of(
              Arguments.arguments(unpartitionedArgs), Arguments.arguments(partitionedArgs));
        });
  }
}
