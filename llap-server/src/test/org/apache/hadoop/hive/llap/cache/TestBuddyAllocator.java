/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hadoop.hive.llap.cache;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hive.common.io.Allocator.AllocatorOutOfMemoryException;
import org.apache.hadoop.hive.common.io.encoded.MemoryBuffer;
import org.apache.hadoop.hive.conf.HiveConf.ConfVars;
import org.apache.hadoop.hive.llap.metrics.LlapDaemonCacheMetrics;
import org.junit.Test;

public class TestBuddyAllocator {
  private static final Log LOG = LogFactory.getLog(TestBuddyAllocator.class);
  private final Random rdm = new Random(2284);

  private static class DummyMemoryManager implements MemoryManager {
    @Override
    public boolean reserveMemory(long memoryToReserve, boolean waitForEviction) {
      return true;
    }

    @Override
    public void releaseMemory(long memUsage) {
    }

    @Override
    public String debugDumpForOom() {
      return "";
    }

    @Override
    public void updateMaxSize(long maxSize) {
    }
  }

  @Test
  public void testVariableSizeAllocs() throws Exception {
    testVariableSizeInternal(1, 2, 1);
  }

  @Test
  public void testVariableSizeMultiAllocs() throws Exception {
    testVariableSizeInternal(3, 2, 3);
    testVariableSizeInternal(5, 2, 5);
  }

  @Test
  public void testSameSizes() throws Exception {
    int min = 3, max = 8, maxAlloc = 1 << max;
    Configuration conf = createConf(1 << min, maxAlloc, maxAlloc, maxAlloc);
    BuddyAllocator a = new BuddyAllocator(conf, new DummyMemoryManager(),
        LlapDaemonCacheMetrics.create("test", "1"));
    for (int i = max; i >= min; --i) {
      allocSameSize(a, 1 << (max - i), i);
    }
  }

  @Test
  public void testMultipleArenas() throws Exception {
    int max = 8, maxAlloc = 1 << max, allocLog2 = max - 1, arenaCount = 5;
    Configuration conf = createConf(1 << 3, maxAlloc, maxAlloc, maxAlloc * arenaCount);
    BuddyAllocator a = new BuddyAllocator(conf, new DummyMemoryManager(),
        LlapDaemonCacheMetrics.create("test", "1"));
    allocSameSize(a, arenaCount * 2, allocLog2);
  }

  @Test
  public void testMTT() {
    final int min = 3, max = 8, maxAlloc = 1 << max, allocsPerSize = 3;
    Configuration conf = createConf(1 << min, maxAlloc, maxAlloc * 8, maxAlloc * 24);
    final BuddyAllocator a = new BuddyAllocator(conf, new DummyMemoryManager(),
        LlapDaemonCacheMetrics.create("test", "1"));
    ExecutorService executor = Executors.newFixedThreadPool(3);
    final CountDownLatch cdlIn = new CountDownLatch(3), cdlOut = new CountDownLatch(1);
    FutureTask<Void> upTask = new FutureTask<Void>(new Callable<Void>() {
      public Void call() throws Exception {
        syncThreadStart(cdlIn, cdlOut);
        allocateUp(a, min, max, allocsPerSize, false);
        allocateUp(a, min, max, allocsPerSize, true);
        return null;
      }
    }), downTask = new FutureTask<Void>(new Callable<Void>() {
      public Void call() throws Exception {
        syncThreadStart(cdlIn, cdlOut);
        allocateDown(a, min, max, allocsPerSize, false);
        allocateDown(a, min, max, allocsPerSize, true);
        return null;
      }
    }), sameTask = new FutureTask<Void>(new Callable<Void>() {
      public Void call() throws Exception {
        syncThreadStart(cdlIn, cdlOut);
        for (int i = min; i <= max; ++i) {
          allocSameSize(a, (1 << (max - i)) * allocsPerSize, i);
        }
        return null;
      }
    });
    executor.execute(sameTask);
    executor.execute(upTask);
    executor.execute(downTask);
    try {
      cdlIn.await(); // Wait for all threads to be ready.
      cdlOut.countDown(); // Release them at the same time.
      upTask.get();
      downTask.get();
      sameTask.get();
    } catch (Throwable t) {
      throw new RuntimeException(t);
    }
  }

  @Test
  public void testMTTArenas() {
    final int min = 3, max = 4, maxAlloc = 1 << max, minAllocCount = 2048, threadCount = 4;
    Configuration conf = createConf(1 << min, maxAlloc, maxAlloc, (1 << min) * minAllocCount);
    final BuddyAllocator a = new BuddyAllocator(conf, new DummyMemoryManager(),
        LlapDaemonCacheMetrics.create("test", "1"));
    ExecutorService executor = Executors.newFixedThreadPool(threadCount);
    final CountDownLatch cdlIn = new CountDownLatch(threadCount), cdlOut = new CountDownLatch(1);
    Callable<Void> testCallable = new Callable<Void>() {
      public Void call() throws Exception {
        syncThreadStart(cdlIn, cdlOut);
        allocSameSize(a, minAllocCount / threadCount, min);
        return null;
      }
    };
    @SuppressWarnings("unchecked")
    FutureTask<Void>[] allocTasks = new FutureTask[threadCount];
    for (int i = 0; i < threadCount; ++i) {
      allocTasks[i] = new FutureTask<>(testCallable);
      executor.execute(allocTasks[i]);
    }
    try {
      cdlIn.await(); // Wait for all threads to be ready.
      cdlOut.countDown(); // Release them at the same time.
      for (int i = 0; i < threadCount; ++i) {
        allocTasks[i].get();
      }
    } catch (Throwable t) {
      throw new RuntimeException(t);
    }
  }
  private void syncThreadStart(final CountDownLatch cdlIn, final CountDownLatch cdlOut) {
    cdlIn.countDown();
    try {
      cdlOut.await();
    } catch (InterruptedException e) {
      throw new RuntimeException(e);
    }
  }

  private void testVariableSizeInternal(
      int allocCount, int arenaSizeMult, int arenaCount) throws Exception {
    int min = 3, max = 8, maxAlloc = 1 << max, arenaSize = maxAlloc * arenaSizeMult;
    Configuration conf = createConf(1 << min, maxAlloc, arenaSize, arenaSize * arenaCount);
    BuddyAllocator a = new BuddyAllocator(conf, new DummyMemoryManager(),
        LlapDaemonCacheMetrics.create("test", "1"));
    allocateUp(a, min, max, allocCount, true);
    allocateDown(a, min, max, allocCount, true);
    allocateDown(a, min, max, allocCount, false);
    allocateUp(a, min, max, allocCount, true);
    allocateUp(a, min, max, allocCount, false);
    allocateDown(a, min, max, allocCount, true);
  }

  private void allocSameSize(BuddyAllocator a, int allocCount, int sizeLog2) throws Exception {
    MemoryBuffer[][] allocs = new MemoryBuffer[allocCount][];
    long[][] testValues = new long[allocCount][];
    for (int j = 0; j < allocCount; ++j) {
      allocateAndUseBuffer(a, allocs, testValues, 1, j, sizeLog2);
    }
    deallocUpOrDown(a, false, allocs, testValues);
  }

  private void allocateUp(BuddyAllocator a, int min, int max, int allocPerSize,
      boolean isSameOrderDealloc) throws Exception {
    int sizes = max - min + 1;
    MemoryBuffer[][] allocs = new MemoryBuffer[sizes][];
    // Put in the beginning; relies on the knowledge of internal implementation. Pave?
    long[][] testValues = new long[sizes][];
    for (int i = min; i <= max; ++i) {
      allocateAndUseBuffer(a, allocs, testValues, allocPerSize, i - min, i);
    }
    deallocUpOrDown(a, isSameOrderDealloc, allocs, testValues);
  }

  private void allocateDown(BuddyAllocator a, int min, int max, int allocPerSize,
      boolean isSameOrderDealloc) throws Exception {
    int sizes = max - min + 1;
    MemoryBuffer[][] allocs = new MemoryBuffer[sizes][];
    // Put in the beginning; relies on the knowledge of internal implementation. Pave?
    long[][] testValues = new long[sizes][];
    for (int i = max; i >= min; --i) {
      allocateAndUseBuffer(a, allocs, testValues, allocPerSize, i - min, i);
    }
    deallocUpOrDown(a, isSameOrderDealloc, allocs, testValues);
  }

  private void allocateAndUseBuffer(BuddyAllocator a, MemoryBuffer[][] allocs,
      long[][] testValues, int allocCount, int index, int sizeLog2) throws Exception {
    allocs[index] = new MemoryBuffer[allocCount];
    testValues[index] = new long[allocCount];
    int size = (1 << sizeLog2) - 1;
    try {
      a.allocateMultiple(allocs[index], size);
    } catch (AllocatorOutOfMemoryException ex) {
      LOG.error("Failed to allocate " + allocCount + " of " + size + "; " + a.debugDump());
      throw ex;
    }
    // LOG.info("Allocated " + allocCount + " of " + size + "; " + a.debugDump());
    for (int j = 0; j < allocCount; ++j) {
      MemoryBuffer mem = allocs[index][j];
      long testValue = testValues[index][j] = rdm.nextLong();
      int pos = mem.getByteBufferRaw().position();
      mem.getByteBufferRaw().putLong(pos, testValue);
      int halfLength = mem.getByteBufferRaw().remaining() >> 1;
      if (halfLength + 8 <= mem.getByteBufferRaw().remaining()) {
        mem.getByteBufferRaw().putLong(pos + halfLength, testValue);
      }
    }
  }

  private void deallocUpOrDown(BuddyAllocator a, boolean isSameOrderDealloc,
      MemoryBuffer[][] allocs, long[][] testValues) {
    if (isSameOrderDealloc) {
      for (int i = 0; i < allocs.length; ++i) {
        deallocBuffers(a, allocs[i], testValues[i]);
      }
    } else {
      for (int i = allocs.length - 1; i >= 0; --i) {
        deallocBuffers(a, allocs[i], testValues[i]);
      }
    }
  }

  private void deallocBuffers(
      BuddyAllocator a, MemoryBuffer[] allocs, long[] testValues) {
    for (int j = 0; j < allocs.length; ++j) {
      LlapDataBuffer mem = (LlapDataBuffer)allocs[j];
      int pos = mem.getByteBufferRaw().position();
      assertEquals("Failed to match (" + pos + ") on " + j + "/" + allocs.length,
          testValues[j], mem.getByteBufferRaw().getLong(pos));
      int halfLength = mem.getByteBufferRaw().remaining() >> 1;
      if (halfLength + 8 <= mem.getByteBufferRaw().remaining()) {
        assertEquals("Failed to match half (" + (pos + halfLength) + ") on " + j + "/"
            + allocs.length, testValues[j], mem.getByteBufferRaw().getLong(pos + halfLength));
      }
      a.deallocate(mem);
    }
  }

  private Configuration createConf(int min, int max, int arena, int total) {
    Configuration conf = new Configuration();
    conf.setInt(ConfVars.LLAP_ORC_CACHE_MIN_ALLOC.varname, min);
    conf.setInt(ConfVars.LLAP_ORC_CACHE_MAX_ALLOC.varname, max);
    conf.setInt(ConfVars.LLAP_ORC_CACHE_ARENA_SIZE.varname, arena);
    conf.setLong(ConfVars.LLAP_ORC_CACHE_MAX_SIZE.varname, total);
    return conf;
  }
}
