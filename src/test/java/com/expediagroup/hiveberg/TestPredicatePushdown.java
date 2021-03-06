/**
 * Copyright (C) 2020 Expedia, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.expediagroup.hiveberg;

import com.klarna.hiverunner.HiveShell;
import com.klarna.hiverunner.StandaloneHiveRunner;
import com.klarna.hiverunner.annotations.HiveSQL;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.apache.hadoop.conf.Configuration;
import org.apache.iceberg.DataFile;
import org.apache.iceberg.FileFormat;
import org.apache.iceberg.PartitionSpec;
import org.apache.iceberg.Schema;
import org.apache.iceberg.Table;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.data.Record;
import org.apache.iceberg.hadoop.HadoopCatalog;
import org.apache.iceberg.types.Types;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;

import static org.apache.iceberg.types.Types.NestedField.optional;
import static org.apache.iceberg.types.Types.NestedField.required;
import static org.junit.Assert.assertEquals;

@RunWith(StandaloneHiveRunner.class)
public class TestPredicatePushdown {

  @HiveSQL(files = {}, autoStart = true)
  private HiveShell shell;

  @Rule
  public TemporaryFolder temp = new TemporaryFolder();

  private File tableLocation;
  private Table table;

  @Before
  public void before() throws IOException {
    tableLocation = temp.newFolder();
    Schema schema = new Schema(required(1, "id", Types.LongType.get()),
        optional(2, "data", Types.StringType.get()));
    PartitionSpec spec = PartitionSpec.unpartitioned();

    Configuration conf = new Configuration();
    HadoopCatalog catalog = new HadoopCatalog(conf, tableLocation.getAbsolutePath());
    TableIdentifier id = TableIdentifier.parse("source_db.table_a");
    table = catalog.createTable(id, schema, spec);
  }

  /**
   * This test is supposed to check that filter properties set in IcebergStorageHandler#decomposePredicate
   * are unset for the next query so that a wrong filter isn't applied to the next read.
   */
  @Test
  public void testFilterPropertyIsUnsetAfterQuery() throws IOException {
    List<Record> dataA = new ArrayList<>();
    dataA.add(TestHelpers.createSimpleRecord(1L, "Michael"));

    List<Record> dataB = new ArrayList<>();
    dataB.add(TestHelpers.createSimpleRecord(2L, "Andy"));

    List<Record> dataC = new ArrayList<>();
    dataC.add(TestHelpers.createSimpleRecord(3L, "Berta"));

    DataFile fileA = TestHelpers.writeFile(temp.newFile(), table, null, FileFormat.PARQUET, dataA);
    DataFile fileB = TestHelpers.writeFile(temp.newFile(), table, null, FileFormat.PARQUET, dataB);
    DataFile fileC = TestHelpers.writeFile(temp.newFile(), table, null, FileFormat.PARQUET, dataC);

    table.newAppend().appendFile(fileA).commit();
    table.newAppend().appendFile(fileB).commit();
    table.newAppend().appendFile(fileC).commit();

    shell.execute("CREATE DATABASE source_db");
    shell.execute(new StringBuilder()
        .append("CREATE TABLE source_db.table_a ")
        .append("STORED BY 'com.expediagroup.hiveberg.IcebergStorageHandler' ")
        .append("LOCATION '")
        .append(tableLocation.getAbsolutePath() + "/source_db/table_a")
        .append("' TBLPROPERTIES ('iceberg.catalog'='hadoop.catalog', 'iceberg.warehouse.location'='")
        .append(tableLocation.getAbsolutePath())
        .append("')")
        .toString());

    List<Object[]> resultFullTable = shell.executeStatement("SELECT * FROM source_db.table_a");
    assertEquals(3, resultFullTable.size());

    List<Object[]> resultFilterId = shell.executeStatement("SELECT * FROM source_db.table_a WHERE id = 1");
    assertEquals(1, resultFilterId.size());

    List<Object[]> resultFullTableAfterQuery = shell.executeStatement("SELECT * FROM source_db.table_a");
    assertEquals(3, resultFullTableAfterQuery.size());
  }
}
