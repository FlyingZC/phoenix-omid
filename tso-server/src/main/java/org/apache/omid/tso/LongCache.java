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

public class LongCache {

    private final long[] cache;
    private final int size;
    private final int associativity;

    public LongCache(int size, int associativity) {
        this.size = size;
        this.cache = new long[2 * (size + associativity)];
        this.associativity = associativity;
    }

    public long set(long key, long value) {
        final int index = index(key);
        int oldestIndex = 0;
        long oldestValue = Long.MAX_VALUE;
        for (int i = 0; i < associativity; ++i) {
            int currIndex = 2 * (index + i);
            if (cache[currIndex] == key) { // key的存储位置.如果找到key一样的,直接替换数组里原有的值
                oldestValue = 0;
                oldestIndex = currIndex;
                break;
            }
            if (cache[currIndex + 1] <= oldestValue) { // value的存储位置(currIndex + 1)
                oldestValue = cache[currIndex + 1];
                oldestIndex = currIndex;
            }
        }
        cache[oldestIndex] = key;
        cache[oldestIndex + 1] = value;
        return oldestValue;
    }

    public long get(long key) {
        final int index = index(key);
        for (int i = 0; i < associativity; ++i) { // associativity 里存储的元素key应该是相同的
            int currIndex = 2 * (index + i);
            if (cache[currIndex] == key) {
                return cache[currIndex + 1];
            }
        }
        return 0;
    }

    private int index(long hash) {
        return (int) (Math.abs(hash) % size);
    }

}
