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
package org.apache.omid.tso;

import org.apache.phoenix.thirdparty.com.google.common.annotations.VisibleForTesting;
import org.apache.phoenix.thirdparty.com.google.common.util.concurrent.ThreadFactoryBuilder;

import org.apache.omid.committable.CommitTable;
import org.apache.omid.metrics.Gauge;
import org.apache.omid.metrics.MetricsRegistry;
import org.apache.omid.timestamp.storage.TimestampStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.inject.Singleton;

import java.io.IOException;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import static org.apache.omid.metrics.MetricsUtils.name;

/**
 * The Timestamp Oracle that gives monotonically increasing timestamps.
 */
@Singleton
public class TimestampOracleImpl implements TimestampOracle {

    private static final Logger LOG = LoggerFactory.getLogger(TimestampOracleImpl.class);

    @VisibleForTesting
    static class InMemoryTimestampStorage implements TimestampStorage {

        long maxTimestamp = 0;

        @Override
        public void updateMaxTimestamp(long previousMaxTimestamp, long nextMaxTimestamp) {
            maxTimestamp = nextMaxTimestamp;
            LOG.info("Updating max timestamp: (previous:{}, new:{})", previousMaxTimestamp, nextMaxTimestamp);
        }

        @Override
        public long getMaxTimestamp() {
            return maxTimestamp;
        }

    }

    private class AllocateTimestampBatchTask implements Runnable {
        long previousMaxTimestamp;

        AllocateTimestampBatchTask(long previousMaxTimestamp) {
            this.previousMaxTimestamp = previousMaxTimestamp;
        }

        @Override
        public void run() {
            long newMaxTimestamp = previousMaxTimestamp + TIMESTAMP_BATCH;
            try {
                storage.updateMaxTimestamp(previousMaxTimestamp, newMaxTimestamp);
                maxAllocatedTimestamp = newMaxTimestamp;
                previousMaxTimestamp = newMaxTimestamp;
            } catch (Throwable e) {
                panicker.panic("Can't store the new max timestamp", e);
            }
        }

    }

    static final long TIMESTAMP_BATCH = 10_000_000 * CommitTable.MAX_CHECKPOINTS_PER_TXN; // 10 million
    private static final long TIMESTAMP_REMAINING_THRESHOLD = 1_000_000 * CommitTable.MAX_CHECKPOINTS_PER_TXN; // 1 million

    private long lastTimestamp;

    private long maxTimestamp;

    private TimestampStorage storage;
    private Panicker panicker;

    private long nextAllocationThreshold;
    private volatile long maxAllocatedTimestamp;

    private Executor executor = Executors.newSingleThreadExecutor(
            new ThreadFactoryBuilder().setNameFormat("ts-persist-%d").build());

    private Runnable allocateTimestampsBatchTask;

    @Inject
    public TimestampOracleImpl(MetricsRegistry metrics,
                               TimestampStorage tsStorage,
                               Panicker panicker) throws IOException {

        this.storage = tsStorage;
        this.panicker = panicker;

        metrics.gauge(name("tso", "maxTimestamp"), new Gauge<Long>() {
            @Override
            public Long getValue() {
                return maxTimestamp;
            }
        });

    }

    @Override
    public void initialize() throws IOException {

        this.lastTimestamp = this.maxTimestamp = storage.getMaxTimestamp(); // 从存储中获取 maxTimestamp

        this.allocateTimestampsBatchTask = new AllocateTimestampBatchTask(lastTimestamp);

        // Trigger first allocation of timestamps
        executor.execute(allocateTimestampsBatchTask);

        LOG.info("Initializing timestamp oracle with timestamp {}", this.lastTimestamp);
    }

    /**
     * Returns the next timestamp if available. Otherwise spins till the ts-persist thread allocates a new timestamp.
     */
    @SuppressWarnings("StatementWithEmptyBody")
    @Override
    public long next() {
        lastTimestamp += CommitTable.MAX_CHECKPOINTS_PER_TXN; // 新的时间戳

        if (lastTimestamp >= nextAllocationThreshold) { // 当前时间戳 超过 分配阈值时 ,触发 batch 分配新时间戳
            // set the nextAllocationThread to max value of long in order to
            // make sure only one call to this function will execute a thread to extend the timestamp batch.
            nextAllocationThreshold = Long.MAX_VALUE; 
            executor.execute(allocateTimestampsBatchTask);
        }

        if (lastTimestamp >= maxTimestamp) { // 当 lastTimestamp 超过 当前已分配的最大时间戳 时，等待新一批时间戳被分配
            assert (maxTimestamp <= maxAllocatedTimestamp);
            while (maxAllocatedTimestamp == maxTimestamp) { // 自旋等待，直到已分配的最大时间戳被更新. maxAllocatedTimestamp 会在 batch 执行完毕时更新
                // spin
            }
            assert (maxAllocatedTimestamp > maxTimestamp);
            maxTimestamp = maxAllocatedTimestamp; // 更新当前 max timestamp = 本次 batch 预分配的最大时间戳
            nextAllocationThreshold = maxTimestamp - TIMESTAMP_REMAINING_THRESHOLD; // 计算下次分配的阈值
            assert (nextAllocationThreshold > lastTimestamp && nextAllocationThreshold < maxTimestamp);
            assert (lastTimestamp < maxTimestamp);
        }

        return lastTimestamp;
    }

    @Override
    public long getLast() {
        return lastTimestamp;
    }

    @Override
    public String toString() {
        return String.format("TimestampOracle -> LastTimestamp: %d, MaxTimestamp: %d", lastTimestamp, maxTimestamp);
    }

}
