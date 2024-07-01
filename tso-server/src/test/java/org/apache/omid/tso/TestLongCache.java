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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.annotations.Test;

import java.util.Random;
import java.util.Set;
import java.util.TreeSet;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

public class TestLongCache {

    private static final Logger LOG = LoggerFactory.getLogger(TestLongCache.class);

    private static final long TEST_VALUE = 1000;

    private Random random = new Random(System.currentTimeMillis());

    @Test(timeOut = 10_000)
    public void testAddAndGetElems() {

        // Cache configuration
        final int CACHE_SIZE = 10_000_000;
        final int CACHE_ASSOCIATIVITY = 32;
        LongCache cache = new LongCache(CACHE_SIZE, CACHE_ASSOCIATIVITY);

        // After creation, cache values should be the default
        for (int i = 0; i < 1000; i++) {
            long position = random.nextLong();
            assertEquals(cache.get(position), 0L);
        }

        Set<Long> testedKeys = new TreeSet<>();
        // Populate some of the values
        for (int i = 0; i < 1000; i++) {
            long position = random.nextLong();
            cache.set(position, TEST_VALUE);
            testedKeys.add(position);
        }

        // Get the values and check them
        for (long key : testedKeys) {
            assertEquals(cache.get(key), TEST_VALUE);
        }

    }

    @Test(timeOut = 10_000)
    public void testEntriesAge() {

        final int entries = 1000;

        LongCache cache = new LongCache(entries, 16);

        int removals = 0;
        long totalAge = 0;
        double tempStdDev = 0;
        double tempAvg = 0;

        int i = 0;
        int largestDeletedTimestamp = 0;
        for (; i < entries * 10; ++i) {
            long removed = cache.set(random.nextLong(), i);
            if (removed > largestDeletedTimestamp) {
                largestDeletedTimestamp = (int) removed;
            }
        }

        long time = System.nanoTime();
        for (; i < entries * 100; ++i) {
            long removed = cache.set(random.nextLong(), i);
            if (removed > largestDeletedTimestamp) {
                largestDeletedTimestamp = (int) removed;
            }
            int gap = i - largestDeletedTimestamp;
            removals++;
            totalAge += gap;
            double oldAvg = tempAvg;
            tempAvg += (gap - tempAvg) / removals;
            tempStdDev += (gap - oldAvg) * (gap - tempAvg);
        }
        long elapsed = System.nanoTime() - time;
        LOG.info("Elapsed (ms): " + (elapsed / (double) 1000));

        double avgGap = totalAge / (double) removals;
        LOG.info("Avg gap: " + (tempAvg));
        LOG.info("Std dev gap: " + Math.sqrt((tempStdDev / entries)));
        assertTrue(avgGap > entries * 0.6, "avgGap should be greater than entries * 0.6");

    }
    
    @Test
    public void testPutOneKey() {
        LongCache cache = new LongCache(100, 4);
        for (int i = 0; i < 20; i++) {
            cache.set(1, i + 1000);
            cache.set(101, i + 1000);
            cache.set(201, i + 1000);
        }
    }
}
