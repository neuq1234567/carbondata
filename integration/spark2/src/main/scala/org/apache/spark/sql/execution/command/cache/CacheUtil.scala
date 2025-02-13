/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.sql.execution.command.cache

import org.apache.hadoop.mapred.JobConf
import scala.collection.JavaConverters._

import org.apache.carbondata.core.cache.CacheType
import org.apache.carbondata.core.constants.CarbonCommonConstants
import org.apache.carbondata.core.datamap.Segment
import org.apache.carbondata.core.datastore.impl.FileFactory
import org.apache.carbondata.core.metadata.schema.table.{CarbonTable, DataMapSchema}
import org.apache.carbondata.core.readcommitter.LatestFilesReadCommittedScope
import org.apache.carbondata.datamap.bloom.{BloomCacheKeyValue, BloomCoarseGrainDataMapFactory}
import org.apache.carbondata.processing.merger.CarbonDataMergerUtil


object CacheUtil {

  /**
   * Given a carbonTable, returns the list of all carbonindex files
   *
   * @param carbonTable
   * @return List of all index files
   */
  def getAllIndexFiles(carbonTable: CarbonTable): List[String] = {
    if (carbonTable.isTransactionalTable) {
      val absoluteTableIdentifier = carbonTable.getAbsoluteTableIdentifier
      CarbonDataMergerUtil.getValidSegmentList(absoluteTableIdentifier).asScala.flatMap {
        segment =>
          segment.getCommittedIndexFile.keySet().asScala
      }.toList
    } else {
      val tablePath = carbonTable.getTablePath
      val readCommittedScope = new LatestFilesReadCommittedScope(tablePath,
        FileFactory.getConfiguration)
      readCommittedScope.getSegmentList.flatMap {
        load =>
          val seg = new Segment(load.getLoadName, null, readCommittedScope)
          seg.getCommittedIndexFile.keySet().asScala
      }.toList
    }
  }

  /**
   * Given a carbonTable file, returns a list of all dictionary entries which can be in cache
   *
   * @param carbonTable
   * @return List of all dict entries which can in cache
   */
  def getAllDictCacheKeys(carbonTable: CarbonTable): List[String] = {
    def getDictCacheKey(columnIdentifier: String,
        cacheType: CacheType[_, _]): String = {
      columnIdentifier + CarbonCommonConstants.UNDERSCORE + cacheType.getCacheName
    }

    carbonTable.getAllDimensions.asScala
      .collect {
        case dict if dict.isGlobalDictionaryEncoding =>
          Seq(getDictCacheKey(dict.getColumnId, CacheType.FORWARD_DICTIONARY),
            getDictCacheKey(dict.getColumnId, CacheType.REVERSE_DICTIONARY))
      }.flatten.toList
  }

  def getBloomCacheKeys(carbonTable: CarbonTable, datamap: DataMapSchema): List[String] = {
    val segments = CarbonDataMergerUtil
      .getValidSegmentList(carbonTable.getAbsoluteTableIdentifier).asScala

    // Generate shard Path for the datamap
    val shardPaths = segments.flatMap {
      segment =>
        BloomCoarseGrainDataMapFactory.getAllShardPaths(carbonTable.getTablePath,
          segment.getSegmentNo, datamap.getDataMapName).asScala
    }

    // get index columns
    val indexColumns = carbonTable.getIndexedColumns(datamap).asScala.map {
      entry =>
        entry.getColName
    }

    // generate cache key using shard path and index columns on which bloom was created.
    val datamapKeys = shardPaths.flatMap {
      shardPath =>
        indexColumns.map {
          indexCol =>
            new BloomCacheKeyValue.CacheKey(shardPath, indexCol).toString
      }
    }
    datamapKeys.toList
  }

}
