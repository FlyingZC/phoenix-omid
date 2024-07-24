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
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.omid.examples;

import org.apache.commons.lang3.StringUtils;
import org.apache.hadoop.hbase.client.ConnectionFactory;
import org.apache.hadoop.hbase.client.Get;
import org.apache.hadoop.hbase.client.Put;
import org.apache.hadoop.hbase.client.Result;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.omid.transaction.HBaseTransactionManager;
import org.apache.omid.transaction.RollbackException;
import org.apache.omid.transaction.TTable;
import org.apache.omid.transaction.Transaction;
import org.apache.omid.transaction.TransactionManager;
import org.apache.phoenix.thirdparty.com.google.common.base.Preconditions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Arrays;

public class OldestTransactionExample {

    private static final Logger LOG = LoggerFactory.getLogger(OldestTransactionExample.class);
    private final byte[] qualifier;
    private final byte[] initialData;
    private final byte[] dataValue1;
    private final byte[] dataValue2;
    private RowIdGenerator rowIdGenerator = new StaticRowIdGenerator();
    private String userTableName;
    private byte[] family;
    private TransactionManager tm;
    private TTable txTable;

    public static void main(String[] args) throws Exception {
        OldestTransactionExample example = new OldestTransactionExample(args);
        example.execute();
        example.close();
    }

    OldestTransactionExample(String[] args) throws IOException, InterruptedException {
        LOG.info("Parsing the command line arguments");
        userTableName = "MY_TX_TABLE";
        if (args != null && args.length > 0 && StringUtils.isNotEmpty(args[0])) {
            userTableName = args[0];
        }
        family = Bytes.toBytes("MY_CF");
        if (args != null && args.length > 1 && StringUtils.isNotEmpty(args[1])) {
            family = Bytes.toBytes(args[1]);
        }
        LOG.info("Table '{}', column family '{}'", userTableName, Bytes.toString(family));

        qualifier = Bytes.toBytes("MY_Q");
        initialData = Bytes.toBytes("initialVal");
        dataValue1 = Bytes.toBytes("val1");
        dataValue2 = Bytes.toBytes("val2");

        LOG.info("--------");
        LOG.info("NOTE: All Transactions in the Example access column {}:{}/{}/{} [TABLE:ROW/CF/Q]",
                 userTableName, Bytes.toString(rowIdGenerator.getRowId()), Bytes.toString(family),
                 Bytes.toString(qualifier));
        LOG.info("--------");

        LOG.info("Creating access to Omid Transaction Manager & Transactional Table '{}'", userTableName);
        tm = HBaseTransactionManager.newInstance();
        txTable = new TTable(ConnectionFactory.createConnection(), userTableName);
    }

    void execute() throws IOException, RollbackException {

        // A transaction Tx0 sets an initial value to a particular column in an specific row
        Transaction tx0 = tm.begin();
        byte[] rowId = rowIdGenerator.getRowId();
        Put initialPut = new Put(rowId);
        initialPut.addColumn(family, qualifier, initialData);
        txTable.put(tx0, initialPut); // 事务性插入数据
        
        // tm.commit(tx0);
//        LOG.info("Initial Transaction {} COMMITTED. Base value written in {}:{}/{}/{} = {}",
//                 tx0, userTableName, Bytes.toString(rowId), Bytes.toString(family),
//                 Bytes.toString(qualifier), Bytes.toString(initialData));

        // Transaction Tx1 starts, creates its own snapshot of the current data in HBase and writes new data
        for (int i = 0; i < 1000; i++) {
            Transaction tx1 = tm.begin();
            LOG.info("Transaction {} STARTED", tx1);
            Put tx1Put = new Put(rowId);
            tx1Put.addColumn(family, qualifier, Bytes.toBytes("val" + i));
            txTable.put(tx1, tx1Put);
            LOG.info("Transaction {} updates base value in {}:{}/{}/{} = {} in its own Snapshot",
                     tx1, userTableName, Bytes.toString(rowId), Bytes.toString(family),
                     Bytes.toString(qualifier), Bytes.toString(dataValue1));
    
            // Transaction Tx1 tries to commit and as there're no conflicting changes, persists the new value in HBase
            tm.commit(tx1);
        }
        tm.commit(tx0);
    }

    private void close() throws IOException {
        tm.close();
        txTable.close();
    }


    void setRowIdGenerator(RowIdGenerator rowIdGenerator) {
        this.rowIdGenerator = rowIdGenerator;
    }

    private class StaticRowIdGenerator implements RowIdGenerator {

        @Override
        public byte[] getRowId() {
            return Bytes.toBytes("EXAMPLE_ROW");
        }
    }
}

