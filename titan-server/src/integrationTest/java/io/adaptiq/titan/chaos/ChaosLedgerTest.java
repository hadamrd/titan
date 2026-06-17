package io.adaptiq.titan.chaos;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ChaosLedgerTest {

  @Test
  void countsExecutionsPerTask(@TempDir Path dir) throws Exception {
    ChaosLedger ledger = new ChaosLedger(dir);
    ledger.record(100L);
    ledger.record(100L);
    ledger.record(200L);

    var counts = ledger.executionCounts();
    assertEquals(2L, counts.get(100L), "task 100 ran twice");
    assertEquals(1L, counts.get(200L), "task 200 ran once");
  }

  @Test
  void concurrentRecordsAreNotLost(@TempDir Path dir) throws Exception {
    ChaosLedger ledger = new ChaosLedger(dir);
    int threads = 8;
    int perThread = 50;
    var pool = java.util.concurrent.Executors.newFixedThreadPool(threads);
    var latch = new java.util.concurrent.CountDownLatch(threads);
    for (int i = 0; i < threads; i++) {
      pool.submit(
          () -> {
            try {
              for (int j = 0; j < perThread; j++) {
                ledger.record(7L);
              }
            } finally {
              latch.countDown();
            }
          });
    }
    latch.await();
    pool.shutdown();
    assertEquals(
        (long) threads * perThread,
        ledger.executionCounts().get(7L),
        "no record lost under concurrent appends");
  }
}
