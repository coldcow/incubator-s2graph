/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * 
 *   http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.s2graph.core.storage.rocks

import com.typesafe.config.Config
import org.apache.s2graph.core.storage.StorageManagement
import org.rocksdb.RocksDB

class RocksStorageManagement(val config: Config,
                             val vdb: RocksDB,
                             val db: RocksDB) extends StorageManagement {
  val path =RocksStorage.getFilePath(config)


  override def flush(): Unit = {
    vdb.close()
    db.close()
    RocksStorage.dbPool.asMap().remove(path)
  }

  override def createTable(config: Config, tableNameStr: String): Unit = {}

  override def truncateTable(config: Config, tableNameStr: String): Unit = {}

  override def deleteTable(config: Config, tableNameStr: String): Unit = {}

  override def shutdown(): Unit = flush()
}
