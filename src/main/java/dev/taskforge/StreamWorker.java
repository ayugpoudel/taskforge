package dev.taskforge;

import java.util.concurrent.*;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "taskforge.role", havingValue = "worker")
class StreamWorker implements SmartLifecycle {
  final RedisQueue queue;
  final JobStore jobs;
  final JobRunner runner;
  final Settings settings;
  ExecutorService executor;
  volatile boolean running;
  final java.util.concurrent.atomic.AtomicInteger turns =
      new java.util.concurrent.atomic.AtomicInteger();

  StreamWorker(RedisQueue queue, JobStore jobs, JobRunner runner, Settings settings) {
    this.queue = queue;
    this.jobs = jobs;
    this.runner = runner;
    this.settings = settings;
  }

  boolean processOnce(String consumer) {
    var delivery = turns.incrementAndGet() % 10 == 0 ? queue.recoverPending(jobs, consumer) : null;
    if (delivery == null) delivery = queue.receive(consumer);
    if (delivery == null) delivery = queue.recoverPending(jobs, consumer);
    if (delivery == null) return false;
    var claim = jobs.claim(delivery.jobId(), consumer);
    if (claim != null) runner.run(claim, consumer);
    var state = jobs.getInternal(delivery.jobId());
    if (state == null
        || java.util.Set.of("SUCCEEDED", "FAILED", "CANCELLED").contains(state.status()))
      queue.acknowledge(delivery.messageId());
    return true;
  }

  @Override
  public void start() {
    running = true;
    executor = Executors.newFixedThreadPool(settings.workerConcurrency());
    for (int slot = 0; slot < settings.workerConcurrency(); slot++) {
      String consumer = settings.instanceId() + "-" + slot;
      executor.submit(
          () -> {
            while (running) {
              try {
                if (!processOnce(consumer)) Thread.sleep(200);
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
              } catch (Exception e) {
                LoggerFactory.getLogger(getClass())
                    .warn("event=worker_unavailable error={}", e.getClass().getSimpleName());
                try {
                  Thread.sleep(1000);
                } catch (InterruptedException interrupted) {
                  Thread.currentThread().interrupt();
                  break;
                }
              }
            }
          });
    }
  }

  @Override
  public void stop() {
    running = false;
    if (executor != null) {
      executor.shutdown();
      try {
        if (!executor.awaitTermination(settings.shutdownSeconds(), TimeUnit.SECONDS))
          executor.shutdownNow();
      } catch (InterruptedException e) {
        executor.shutdownNow();
        Thread.currentThread().interrupt();
      }
    }
  }

  @Override
  public void stop(Runnable callback) {
    try {
      stop();
    } finally {
      callback.run();
    }
  }

  @Override
  public boolean isRunning() {
    return running;
  }
}
