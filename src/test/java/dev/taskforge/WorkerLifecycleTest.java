package dev.taskforge;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.UUID;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

class WorkerLifecycleTest {
  @Test
  void gracefulStopDrainsCurrentAttemptAndStopsReading() throws Exception {
    var queue = mock(RedisQueue.class);
    var jobs = mock(JobStore.class);
    var runner = mock(JobRunner.class);
    var settings = mock(Settings.class);
    when(settings.workerConcurrency()).thenReturn(1);
    when(settings.instanceId()).thenReturn("process");
    when(settings.shutdownSeconds()).thenReturn(2);
    UUID id = UUID.randomUUID();
    var job = mock(JobStore.Job.class);
    when(queue.receive("process-0")).thenReturn(new RedisQueue.Delivery("1-0", id));
    when(jobs.claim(id, "process-0")).thenReturn(job);
    var entered = new CountDownLatch(1);
    var finish = new CountDownLatch(1);
    doAnswer(
            invocation -> {
              entered.countDown();
              assertThat(finish.await(2, TimeUnit.SECONDS)).isTrue();
              return null;
            })
        .when(runner)
        .run(job, "process-0");
    var worker = new StreamWorker(queue, jobs, runner, settings);
    try (var stopThread = Executors.newSingleThreadExecutor()) {
      worker.start();
      assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
      var stopped =
          stopThread.submit(
              () -> {
                worker.stop();
              });
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
      while (worker.isRunning() && System.nanoTime() < deadline) Thread.onSpinWait();
      assertThat(worker.isRunning()).isFalse();
      assertThat(stopped.isDone()).isFalse();
      finish.countDown();
      stopped.get(2, TimeUnit.SECONDS);
      verify(queue, times(1)).receive("process-0");
      verify(queue).acknowledge("1-0");
    } finally {
      finish.countDown();
      worker.stop();
    }
  }
}
