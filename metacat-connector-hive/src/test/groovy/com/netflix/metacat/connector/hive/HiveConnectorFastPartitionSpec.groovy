/*
 *  Copyright 2018 Netflix, Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */

package com.netflix.metacat.connector.hive

import com.google.common.collect.Maps
import com.netflix.iceberg.Table
import com.netflix.metacat.common.QualifiedName
import com.netflix.metacat.common.dto.Sort
import com.netflix.metacat.common.dto.SortOrder
import com.netflix.metacat.common.server.connectors.ConnectorContext
import com.netflix.metacat.common.server.connectors.ConnectorRequestContext
import com.netflix.metacat.common.server.connectors.model.PartitionListRequest
import com.netflix.metacat.common.server.properties.Config
import com.netflix.metacat.connector.hive.client.thrift.MetacatHiveClient
import com.netflix.metacat.connector.hive.converters.HiveConnectorInfoConverter
import com.netflix.metacat.connector.hive.converters.HiveTypeConverter
import com.netflix.metacat.connector.hive.iceberg.IcebergTableHandler
import com.netflix.metacat.connector.hive.sql.DirectSqlGetPartition
import com.netflix.metacat.connector.hive.sql.DirectSqlSavePartition
import com.netflix.metacat.connector.hive.sql.HiveConnectorFastPartitionService
import com.netflix.metacat.testdata.provider.MetacatDataInfoProvider
import com.netflix.spectator.api.NoopRegistry
import org.apache.hadoop.hive.metastore.Warehouse
import spock.lang.Shared
import spock.lang.Specification
import spock.lang.Unroll

class HiveConnectorFastPartitionSpec extends Specification {
    @Shared
    MetacatHiveClient metacatHiveClient = Mock(MetacatHiveClient);
    @Shared
    DirectSqlGetPartition directSqlGetPartition = Mock(DirectSqlGetPartition)
    @Shared
    DirectSqlSavePartition directSqlSavePartition = Mock(DirectSqlSavePartition)
    @Shared
    ConnectorRequestContext connectorContext = new ConnectorRequestContext(1, null)
    @Shared
    IcebergTableHandler icebergTableHandler = Mock(IcebergTableHandler)
    @Shared
    conf = Mock(Config)
    @Shared
    HiveConnectorFastPartitionService hiveConnectorFastPartitionService =
        new HiveConnectorFastPartitionService(
            new ConnectorContext('testhive',
                'testhive', 'hive', conf, new NoopRegistry(), Maps.newHashMap()),
            metacatHiveClient,
            Mock(Warehouse),
            new HiveConnectorInfoConverter(new HiveTypeConverter()),
            directSqlGetPartition,
            directSqlSavePartition)

    @Shared
    metric1 = Mock(com.netflix.iceberg.ScanSummary.PartitionMetrics)

    def setupSpec() {
        conf.icebergEnabled >> true
        metric1.fileCount() >> 1
        metric1.dataTimestampMillis() >> 1234500000
        metric1.recordCount() >> 1
        hiveConnectorFastPartitionService.icebergTableHandler = icebergTableHandler
        icebergTableHandler.getIcebergTable(_,_) >> Mock(Table)
        icebergTableHandler.getIcebergTablePartitionMap(_,_,_) >> ["dateint=20170101/hour=1": metric1,
                                                                   "dateint=20170102/hour=1": metric1,
                                                                   "dateint=20170103/hour=1": metric1]
    }

    @Unroll
    def "Test for get iceberg table partitionMaps" (){
        when:
        def partionInfos = hiveConnectorFastPartitionService.getPartitions(
            connectorContext, QualifiedName.ofTable("testhive", "test1", "icebergtable"),
            partitionListRequest, MetacatDataInfoProvider.getIcebergTableInfo("icebergtable"))

        then:
        partionInfos.collect { [it.getName().getPartitionName(),
                                it.getAudit().createdDate.toInstant().toEpochMilli(),
                                it.getAudit().lastModifiedDate.toInstant().toEpochMilli(),
                                it.getAudit().createdBy] } == results
        where:
        partitionListRequest | results
        new PartitionListRequest(null, ["dateint=20170101/hour=1"],false, null,
            new Sort(), null ) | [["dateint=20170101/hour=1", 1234500000, 1234500000, "metacat_test"]]
        new PartitionListRequest(null, null, false, null,
            new Sort(), null) | [["dateint=20170101/hour=1", 1234500000, 1234500000, "metacat_test"], ["dateint=20170102/hour=1", 1234500000, 1234500000, "metacat_test"], ["dateint=20170103/hour=1", 1234500000, 1234500000, "metacat_test"]]
        new PartitionListRequest(null, null, false, null,
            new Sort(null, SortOrder.DESC), null) | [["dateint=20170103/hour=1", 1234500000, 1234500000, "metacat_test"], ["dateint=20170102/hour=1", 1234500000, 1234500000, "metacat_test"], ["dateint=20170101/hour=1", 1234500000, 1234500000, "metacat_test"]]
    }

    def "Test for get iceberg table partitionKeys" (){
        when:
        PartitionListRequest partitionListRequest = new PartitionListRequest();
        partitionListRequest.partitionNames = [
            "dateint=20170101/hour=1"
        ]
        def partKeys = hiveConnectorFastPartitionService.getPartitionKeys(
            connectorContext, QualifiedName.ofTable("testhive", "test1", "icebergtable"),
            partitionListRequest, MetacatDataInfoProvider.getIcebergTableInfo("icebergtable"))

        then:
        partKeys== [
            "dateint=20170101/hour=1"
        ]
    }
}
