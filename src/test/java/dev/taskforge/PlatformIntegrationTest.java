package dev.taskforge;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.utility.DockerImageName;

@Tag("integration")
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PlatformIntegrationTest {
  @Container
  static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

  @Container
  static final GenericContainer<?> redis =
      new GenericContainer<>("redis:7.4.2-alpine").withExposedPorts(6379);

  @Container
  static final GenericContainer<?> minio =
      new GenericContainer<>(DockerImageName.parse("taskforge-minio:2025-10-15"))
          .withEnv("MINIO_ROOT_USER", "taskforge-test")
          .withEnv("MINIO_ROOT_PASSWORD", "taskforge-test-password")
          .withCommand("server", "/data")
          .withExposedPorts(9000)
          .waitingFor(
              org.testcontainers.containers.wait.strategy.Wait.forHttp("/minio/health/ready")
                  .forPort(9000));

  // Stable mapped ports model service restarts at a stable endpoint. Docker Desktop may
  // reassign automatically published host ports across explicit stop/start operations.
  static int freePort() {
    try (var socket = new java.net.ServerSocket(0)) {
      return socket.getLocalPort();
    } catch (java.io.IOException e) {
      throw new IllegalStateException(e);
    }
  }

  static {
    redis.setPortBindings(List.of(freePort() + ":6379"));
    postgres.setPortBindings(List.of(freePort() + ":5432"));
  }

  static final String ADMIN = "test-admin-token-more-than-24-chars";
  static final String ALICE = "test-alice-token-more-than-24-chars",
      BOB = "test-bob-token-more-than-24-chars";

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry r) {
    r.add("spring.datasource.url", postgres::getJdbcUrl);
    r.add("spring.datasource.username", postgres::getUsername);
    r.add("spring.datasource.password", postgres::getPassword);
    r.add("spring.data.redis.host", redis::getHost);
    r.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
    r.add(
        "taskforge.storage-endpoint",
        () -> "http://" + minio.getHost() + ":" + minio.getMappedPort(9000));
    r.add("taskforge.storage-access-key", () -> "taskforge-test");
    r.add("taskforge.storage-secret-key", () -> "taskforge-test-password");
    r.add("taskforge.owner-storage-bytes", () -> 65536);
    r.add("taskforge.max-active-jobs", () -> 4);
    r.add("taskforge.per-owner-concurrency", () -> 1);
    r.add("taskforge.auth-tokens", () -> ALICE + "=alice," + BOB + "=bob," + ADMIN + "=admin");
  }

  @LocalServerPort int port;
  @Autowired JobStore jobs;
  @Autowired org.springframework.jdbc.core.JdbcTemplate db;
  @Autowired ObjectStorage storage;
  @Autowired ObjectMapper json;
  @Autowired Settings settings;
  @Autowired JobRunner runner;
  @Autowired RedisQueue queue;
  @Autowired StorageCleanup cleanup;
  @Autowired ApiAuthentication authFilter;
  @Autowired TaskMetrics metrics;
  @Autowired io.micrometer.core.instrument.MeterRegistry meters;
  @Autowired org.springframework.data.redis.core.StringRedisTemplate redisTemplate;
  final HttpClient http = HttpClient.newHttpClient();

  HttpResponse<String> request(String method, String path, String token, String body)
      throws Exception {
    var b =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api" + path))
            .header("Content-Type", "application/json");
    if (token != null) b.header("Authorization", "Bearer " + token);
    return http.send(
        b.method(method, HttpRequest.BodyPublishers.ofString(body == null ? "" : body)).build(),
        HttpResponse.BodyHandlers.ofString());
  }

  UUID upload(String csv) throws Exception {
    return upload(csv, ALICE);
  }

  UUID upload(String csv, String token) throws Exception {
    return upload(csv, token, "sample.csv");
  }

  UUID upload(String csv, String token, String filename) throws Exception {
    String boundary = "taskforge-boundary";
    String body =
        "--"
            + boundary
            + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\""
            + filename
            + "\"\r\nContent-Type: text/csv\r\n\r\n"
            + csv
            + "\r\n--"
            + boundary
            + "--\r\n";
    var response =
        http.send(
            HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/datasets"))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
    return UUID.fromString(json.readTree(response.body()).get("datasetId").asText());
  }

  @BeforeEach
  void resetDatabase() {
    db.execute("TRUNCATE datasets CASCADE");
    redisTemplate.delete(RedisQueue.STREAM);
    queue.initialized = false;
    queue.pendingCursor = null;
  }

  @Test
  void competingWorkersGetOnlyOneClaimAndResultCommit() throws Exception {
    UUID id = jobs.create("alice", upload("a\n1")).id();
    var start = new java.util.concurrent.CountDownLatch(1);
    try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
      var first =
          executor.submit(
              () -> {
                start.await();
                return jobs.claimNext("first");
              });
      var second =
          executor.submit(
              () -> {
                start.await();
                return jobs.claimNext("second");
              });
      start.countDown();
      var a = first.get(10, java.util.concurrent.TimeUnit.SECONDS);
      var b = second.get(10, java.util.concurrent.TimeUnit.SECONDS);
      assertThat((a == null ? 0 : 1) + (b == null ? 0 : 1)).isEqualTo(1);
      var winner = a == null ? b : a;
      String worker = a == null ? "second" : "first";
      assertThat(jobs.complete(winner, worker, "report/first")).isTrue();
      assertThat(jobs.complete(winner, worker, "report/duplicate")).isFalse();
      assertThat(jobs.get("alice", id).outputKey()).isEqualTo("report/first");
    }
  }

  @Test
  void expiredOwnerCannotFinalizeAnOutput() throws Exception {
    UUID id = jobs.create("alice", upload("a\n1")).id();
    var claim = jobs.claimNext("expired");
    db.update(
        "UPDATE jobs SET lease_expires_at=clock_timestamp()-interval '1 second' WHERE id=?", id);
    assertThat(jobs.complete(claim, "expired", "report/stale")).isFalse();
    assertThat(jobs.get("alice", id).outputKey()).isNull();
  }

  @Test
  void redisGroupDistributesNotificationsAndAcknowledgesDurableCompletion() throws Exception {
    queue.initialize();
    UUID a = jobs.create("alice", upload("n\n2\n4")).id();
    UUID b = jobs.create("alice", upload("n\n5\n7")).id();
    new Dispatcher(jobs, queue).tick();
    var first = queue.receive("worker-one");
    var second = queue.receive("worker-two");
    assertThat(first).isNotNull();
    assertThat(second).isNotNull();
    assertThat(first.jobId()).isNotEqualTo(second.jobId());
    runner.run(jobs.claim(first.jobId(), "worker-one"), "worker-one");
    runner.run(jobs.claim(second.jobId(), "worker-two"), "worker-two");
    queue.acknowledge(first.messageId());
    queue.acknowledge(second.messageId());
    assertThat(jobs.getInternal(a).status()).isEqualTo("SUCCEEDED");
    assertThat(jobs.getInternal(b).status()).isEqualTo("SUCCEEDED");
    assertThat(db.queryForList("SELECT DISTINCT worker_id FROM job_attempts", String.class))
        .contains("worker-one", "worker-two");
  }

  @Test
  void durableUploadJobAndIndependentHandCalculatedReport() throws Exception {
    UUID dataset = upload("name,n\nAlice,2\nBob,4\n");
    var accepted = request("POST", "/jobs", ALICE, "{\"datasetId\":\"" + dataset + "\"}");
    assertThat(accepted.statusCode()).as(accepted.body()).isEqualTo(202);
    UUID id = UUID.fromString(json.readTree(accepted.body()).get("jobId").asText());
    assertThat(request("GET", "/jobs/" + id + "/result", ALICE, null).statusCode()).isEqualTo(409);
    for (int n = 0; n < 10 && !jobs.get("alice", id).status().equals("SUCCEEDED"); n++)
      new LocalWorker(jobs, runner, settings).tick();
    assertThat(jobs.get("alice", id).status()).isEqualTo("SUCCEEDED");
    var result = request("GET", "/jobs/" + id + "/result", ALICE, null);
    assertThat(result.statusCode()).isEqualTo(200);
    var report = json.readTree(result.body());
    assertThat(report.get("rowCount").asInt()).isEqualTo(2);
    assertThat(report.get("columns").get(1).get("numeric").get("sum").asInt()).isEqualTo(6);
    assertThat(report.get("columns").get(1).get("numeric").get("mean").asInt()).isEqualTo(3);
    assertThat(
            db.queryForObject(
                "SELECT count(*) FROM job_attempts WHERE job_id=?", Integer.class, id))
        .isEqualTo(1);
  }

  @Test
  void ownershipAndAuthenticationAreCheckedOnEveryResource() throws Exception {
    UUID dataset = upload("a\n1");
    assertThat(request("POST", "/jobs", BOB, "{\"datasetId\":\"" + dataset + "\"}").statusCode())
        .isEqualTo(404);
    UUID job = jobs.create("alice", dataset).id();
    assertThat(request("GET", "/jobs/" + job, null, null).statusCode()).isEqualTo(401);
    for (String tail : List.of("", "/attempts", "/result"))
      assertThat(request("GET", "/jobs/" + job + tail, BOB, null).statusCode()).isEqualTo(404);
  }

  @Test
  void malformedCsvBecomesDiagnosablePermanentFailure() throws Exception {
    UUID job = jobs.create("alice", upload("a,a\n1,2")).id();
    // Other tests can leave queued jobs; drain deterministically to this one.
    for (int n = 0; n < 10 && !jobs.get("alice", job).status().equals("FAILED"); n++)
      new LocalWorker(jobs, runner, settings).tick();
    assertThat(jobs.get("alice", job).errorCode()).isEqualTo("INVALID_CSV");
  }

  void due(UUID id) {
    db.update(
        "UPDATE jobs SET next_attempt_at=clock_timestamp()-interval '1 second' WHERE id=?", id);
    db.update(
        "UPDATE outbox_events SET available_at=clock_timestamp()-interval '1 second' WHERE job_id=? AND published_at IS NULL",
        id);
  }

  @Test
  void concurrentRequestIdempotencyHasOneJobAndOneOutboxEvent() throws Exception {
    UUID dataset = upload("a\n1");
    var barrier = new java.util.concurrent.CountDownLatch(1);
    try (var pool = java.util.concurrent.Executors.newFixedThreadPool(8)) {
      var calls = new ArrayList<java.util.concurrent.Future<UUID>>();
      for (int i = 0; i < 8; i++)
        calls.add(
            pool.submit(
                () -> {
                  barrier.await();
                  return jobs.create("alice", dataset, "same-request").id();
                }));
      barrier.countDown();
      var ids = new HashSet<UUID>();
      for (var call : calls) ids.add(call.get(10, java.util.concurrent.TimeUnit.SECONDS));
      assertThat(ids).hasSize(1);
      assertThat(db.queryForObject("SELECT count(*) FROM outbox_events", Integer.class))
          .isEqualTo(1);
    }
    UUID different = upload("a\n2");
    assertThatThrownBy(() -> jobs.create("alice", different, "same-request"))
        .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
        .extracting(
            e ->
                ((org.springframework.web.server.ResponseStatusException) e)
                    .getStatusCode()
                    .value())
        .isEqualTo(409);
  }

  @Test
  void outboxInsertFailureRollsBackAcceptance() throws Exception {
    UUID dataset = upload("a\n1");
    db.execute(
        "CREATE FUNCTION reject_test_event() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'injected'; END $$");
    db.execute(
        "CREATE TRIGGER reject_test_event BEFORE INSERT ON outbox_events FOR EACH ROW EXECUTE FUNCTION reject_test_event()");
    try {
      assertThatThrownBy(() -> jobs.create("alice", dataset, "atomic"))
          .isInstanceOf(org.springframework.dao.DataAccessException.class);
      assertThat(db.queryForObject("SELECT count(*) FROM jobs", Integer.class)).isZero();
    } finally {
      db.execute("DROP TRIGGER reject_test_event ON outbox_events");
      db.execute("DROP FUNCTION reject_test_event()");
    }
  }

  @Test
  void dispatcherCrashAfterPublishCreatesDuplicateDeliveryButOneResult() throws Exception {
    UUID id = jobs.create("alice", upload("a\n1")).id();
    var dispatcher = new Dispatcher(jobs, queue);
    assertThatThrownBy(
            () ->
                dispatcher.publishOne(
                    () -> {
                      throw new IllegalStateException("injected crash");
                    }))
        .hasMessage("injected crash");
    assertThat(
            db.queryForObject(
                "SELECT count(*) FROM outbox_events WHERE published_at IS NULL", Integer.class))
        .isEqualTo(1);
    db.update("UPDATE outbox_events SET lease_until=clock_timestamp()-interval '1 second'");
    assertThat(dispatcher.publishOne(() -> {})).isTrue();
    var one = queue.receive("one");
    var two = queue.receive("two");
    assertThat(one.jobId()).isEqualTo(id);
    assertThat(two.jobId()).isEqualTo(id);
    runner.run(jobs.claim(id, "one"), "one");
    assertThat(jobs.claim(id, "two")).isNull();
    queue.acknowledge(one.messageId());
    queue.acknowledge(two.messageId());
    assertThat(
            db.queryForObject(
                "SELECT count(*) FROM job_attempts WHERE job_id=?", Integer.class, id))
        .isEqualTo(1);
    assertThat(jobs.getInternal(id).status()).isEqualTo("SUCCEEDED");
  }

  @Test
  void recoveredGenerationRejectsResumedWorkerAndItsObjectCannotOverwriteResult() throws Exception {
    UUID id = jobs.create("alice", upload("a\n2\n4")).id();
    var old = jobs.claim(id, "old");
    String staleKey = "reports/" + id + "/old.json";
    assertThat(jobs.candidateOutput(old, "old", staleKey)).isTrue();
    db.update(
        "UPDATE jobs SET lease_expires_at=clock_timestamp()-interval '1 second' WHERE id=?", id);
    assertThat(jobs.recoverExpired()).isEqualTo(1);
    assertThat(jobs.getInternal(id).status()).isEqualTo("RETRY_WAIT");
    due(id);
    var newer = jobs.claim(id, "new");
    assertThat(newer.generation()).isEqualTo(old.generation() + 1);
    assertThat(jobs.heartbeat(old, "old")).isFalse();
    runner.run(newer, "new");
    storage.putReport(staleKey, "{\"stale\":true}".getBytes());
    assertThat(jobs.complete(old, "old", staleKey)).isFalse();
    var authoritative = jobs.getInternal(id).outputKey();
    assertThat(authoritative).isNotEqualTo(staleKey);
    try (var input = storage.read(authoritative)) {
      assertThat(json.readTree(input).get("rowCount").asInt()).isEqualTo(2);
    }
  }

  @Test
  void heartbeatProtectsLongRunningPendingDeliveryAndCompletionBeforeAckIsRecovered()
      throws Exception {
    UUID id = jobs.create("alice", upload("a\n1")).id();
    new Dispatcher(jobs, queue).tick();
    var message = queue.receive("original");
    var job = jobs.claim(id, "original");
    db.update(
        "UPDATE jobs SET lease_expires_at=clock_timestamp()+interval '2 seconds' WHERE id=?", id);
    assertThat(jobs.heartbeat(job, "original")).isTrue();
    assertThat(queue.recoverPending(jobs, "other")).isNull();
    assertThat(queue.streams.pending(RedisQueue.STREAM, RedisQueue.GROUP).getTotalPendingMessages())
        .isEqualTo(1);
    assertThat(jobs.claim(id, "other")).isNull();
    runner.run(job, "original"); // deliberately omit acknowledgement, as if the worker crashed now
    queue.pendingCursor = null;
    assertThat(queue.recoverPending(jobs, "other")).isNull();
    assertThat(queue.streams.pending(RedisQueue.STREAM, RedisQueue.GROUP).getTotalPendingMessages())
        .isZero();
    assertThat(jobs.getInternal(id).status()).isEqualTo("SUCCEEDED");
  }

  @Test
  void retriesAreDurablyDelayedAndStopAtMaximumAttempts() throws Exception {
    UUID id = jobs.create("alice", upload("a\n1")).id();
    for (int i = 1; i <= 3; i++) {
      due(id);
      var claim = jobs.claim(id, "retry");
      assertThat(claim.attemptCount()).isEqualTo(i);
      jobs.fail(claim, "retry", "STORAGE_UNAVAILABLE", false);
      if (i < 3) {
        assertThat(jobs.getInternal(id).status()).isEqualTo("RETRY_WAIT");
        assertThat(jobs.claim(id, "too-early")).isNull();
      }
    }
    assertThat(jobs.getInternal(id).status()).isEqualTo("FAILED");
    assertThat(
            db.queryForObject(
                "SELECT count(*) FROM job_attempts WHERE job_id=? AND finished_at IS NOT NULL",
                Integer.class,
                id))
        .isEqualTo(3);
  }

  @Test
  void redisLossIsReconciledFromDurableQueuedState() throws Exception {
    UUID id = jobs.create("alice", upload("a\n1")).id();
    var dispatcher = new Dispatcher(jobs, queue);
    dispatcher.tick();
    redisTemplate.delete(RedisQueue.STREAM);
    queue.initialized = false;
    db.update("UPDATE jobs SET notified_at=clock_timestamp()-interval '31 seconds' WHERE id=?", id);
    dispatcher.tick();
    var delivery = queue.receive("after-loss");
    assertThat(delivery.jobId()).isEqualTo(id);
    runner.run(jobs.claim(id, "after-loss"), "after-loss");
    queue.acknowledge(delivery.messageId());
    assertThat(jobs.getInternal(id).status()).isEqualTo("SUCCEEDED");
  }

  @Test
  void simultaneousDispatchersCannotLeaseSameEventAndExpiredPublisherCannotMarkIt()
      throws Exception {
    UUID id = jobs.create("alice", upload("a\n1")).id();
    var first = jobs.leaseOutbox();
    assertThat(first.jobId()).isEqualTo(id);
    assertThat(jobs.leaseOutbox()).isNull();
    db.update("UPDATE outbox_events SET lease_until=clock_timestamp()-interval '1 second'");
    var replacement = jobs.leaseOutbox();
    assertThat(jobs.published(first)).isFalse();
    assertThat(jobs.published(replacement)).isTrue();
  }

  static void awaitHealthy(java.util.concurrent.Callable<Boolean> condition) throws Exception {
    long deadline = System.nanoTime() + java.time.Duration.ofSeconds(20).toNanos();
    while (System.nanoTime() < deadline) {
      try {
        if (condition.call()) return;
      } catch (Exception retry) {
        /* readiness polling after a controlled restart */
      }
      Thread.sleep(100);
    }
    throw new AssertionError("Service did not recover within 20 seconds");
  }

  @Test
  void redisOutageDoesNotPreventDurableAcceptanceAndDispatcherRecovers() throws Exception {
    UUID dataset = upload("a\n3");
    redis.getDockerClient().stopContainerCmd(redis.getContainerId()).withTimeout(0).exec();
    UUID id;
    try {
      var readiness =
          http.send(
              HttpRequest.newBuilder(
                      URI.create("http://localhost:" + port + "/actuator/health/readiness"))
                  .GET()
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertThat(readiness.statusCode()).isEqualTo(200);

      var response = request("POST", "/jobs", ALICE, "{\"datasetId\":\"" + dataset + "\"}");
      assertThat(response.statusCode()).isEqualTo(202);
      id = UUID.fromString(json.readTree(response.body()).get("jobId").asText());
      assertThatThrownBy(() -> new Dispatcher(jobs, queue).publishOne(() -> {}))
          .isInstanceOf(RuntimeException.class);
      assertThat(
              db.queryForObject(
                  "SELECT count(*) FROM outbox_events WHERE published_at IS NULL", Integer.class))
          .isEqualTo(1);
    } finally {
      redis.getDockerClient().startContainerCmd(redis.getContainerId()).exec();
    }
    awaitHealthy(
        () -> {
          try (var connection = redisTemplate.getConnectionFactory().getConnection()) {
            return "PONG".equals(connection.ping());
          }
        });
    db.update("UPDATE outbox_events SET lease_until=clock_timestamp()-interval '1 second'");
    new Dispatcher(jobs, queue).tick();
    queue.initialized = false;
    var message = queue.receive("restarted-redis");
    assertThat(message.jobId()).isEqualTo(id);
    runner.run(jobs.claim(id, "restarted-redis"), "restarted-redis");
    queue.acknowledge(message.messageId());
    assertThat(jobs.getInternal(id).status()).isEqualTo("SUCCEEDED");
  }

  @Test
  void postgresRestartPreservesAcceptedStateAndFailedHeartbeatCannotAuthorizeCompletion()
      throws Exception {
    UUID id = jobs.create("alice", upload("a\n1")).id();
    var old = jobs.claim(id, "before-db-outage");
    postgres.getDockerClient().stopContainerCmd(postgres.getContainerId()).withTimeout(0).exec();
    try {
      assertThatThrownBy(() -> jobs.heartbeat(old, "before-db-outage"))
          .isInstanceOf(RuntimeException.class);
    } finally {
      postgres.getDockerClient().startContainerCmd(postgres.getContainerId()).exec();
    }
    awaitHealthy(() -> db.queryForObject("SELECT 1", Integer.class) == 1);
    assertThat(jobs.getInternal(id).status()).isEqualTo("RUNNING");
    db.update(
        "UPDATE jobs SET lease_expires_at=clock_timestamp()-interval '1 second' WHERE id=?", id);
    jobs.recoverExpired();
    due(id);
    var replacement = jobs.claim(id, "after-db-outage");
    assertThat(jobs.complete(old, "before-db-outage", "stale-object")).isFalse();
    runner.run(replacement, "after-db-outage");
    assertThat(jobs.getInternal(id).status()).isEqualTo("SUCCEEDED");
  }

  @Test
  void replayRequiresAdminAndPreservesMonotonicAttemptHistory() throws Exception {
    UUID id = jobs.create("alice", upload("a\n1")).id();
    db.update("UPDATE jobs SET max_attempts=1 WHERE id=?", id);
    var first = jobs.claim(id, "failed-worker");
    jobs.fail(first, "failed-worker", "TRANSIENT_FAILURE", false);
    assertThat(jobs.getInternal(id).status()).isEqualTo("FAILED");
    assertThat(request("POST", "/admin/jobs/" + id + "/replay", ALICE, "{}").statusCode())
        .isEqualTo(403);
    assertThat(request("POST", "/admin/jobs/" + id + "/replay", ADMIN, "{}").statusCode())
        .isEqualTo(200);
    var retry = jobs.claim(id, "replayed-worker");
    assertThat(retry.generation()).isGreaterThan(first.generation());
    runner.run(retry, "replayed-worker");
    assertThat(jobs.getInternal(id).status()).isEqualTo("SUCCEEDED");
    assertThat(
            db.queryForObject(
                "SELECT count(*) FROM job_attempts WHERE job_id=?", Integer.class, id))
        .isEqualTo(2);
  }

  @Test
  void capacityRejectsNewWorkButAllowsIdempotentReplayOfAnAcceptedRequest() throws Exception {
    UUID dataset = upload("a\n1");
    UUID original = jobs.create("alice", dataset, "accepted-key").id();
    for (int i = 1; i < settings.maxActiveJobs(); i++) jobs.create("alice", dataset);
    assertThat(jobs.create("alice", dataset, "accepted-key").id()).isEqualTo(original);
    var rejected = request("POST", "/jobs", ALICE, "{\"datasetId\":\"" + dataset + "\"}");
    assertThat(rejected.statusCode()).isEqualTo(429);
    assertThat(rejected.headers().firstValue("Retry-After")).contains("5");
    assertThat(db.queryForObject("SELECT count(*) FROM jobs", Integer.class))
        .isEqualTo(settings.maxActiveJobs());
  }

  @Test
  void concurrentAdmissionCannotExceedCapacity() throws Exception {
    UUID dataset = upload("a\n1");
    for (int i = 0; i < settings.maxActiveJobs() - 1; i++) jobs.create("alice", dataset);
    var start = new java.util.concurrent.CountDownLatch(1);
    try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
      var calls = new ArrayList<java.util.concurrent.Future<Integer>>();
      for (int i = 0; i < 2; i++)
        calls.add(
            pool.submit(
                () -> {
                  start.await();
                  try {
                    jobs.create("alice", dataset);
                    return 202;
                  } catch (org.springframework.web.server.ResponseStatusException e) {
                    return e.getStatusCode().value();
                  }
                }));
      start.countDown();
      assertThat(List.of(calls.get(0).get(), calls.get(1).get()))
          .containsExactlyInAnyOrder(202, 429);
    }
  }

  @Test
  void ownerConcurrencyLimitDoesNotBlockAnUnrelatedOwner() throws Exception {
    UUID a = upload("a\n1"), b = upload("a\n2", BOB);
    var first = jobs.create("alice", a);
    var second = jobs.create("alice", a);
    var other = jobs.create("bob", b);
    var claim = jobs.claim(first.id(), "first");
    assertThat(claim).isNotNull();
    assertThat(jobs.claim(second.id(), "second")).isNull();
    assertThat(jobs.claim(other.id(), "bob-worker")).isNotNull();
    assertThat(jobs.complete(claim, "first", "one-output")).isTrue();
    assertThat(jobs.claim(second.id(), "second")).isNotNull();
  }

  @Test
  void queuedCancellationIsOwnedIdempotentAndCannotRun() throws Exception {
    UUID id = jobs.create("alice", upload("a\n1")).id();
    assertThat(request("POST", "/jobs/" + id + "/cancel", BOB, "{}").statusCode()).isEqualTo(404);
    assertThat(request("POST", "/jobs/" + id + "/cancel", ALICE, "{}").statusCode()).isEqualTo(200);
    assertThat(jobs.cancel("alice", id).status()).isEqualTo("CANCELLED");
    assertThat(jobs.claim(id, "worker")).isNull();
    assertThat(request("GET", "/jobs/" + id + "/result", ALICE, null).statusCode()).isEqualTo(409);
  }

  @Test
  void cancellationVersusCompletionHasOneConsistentTerminalOutcome() throws Exception {
    UUID dataset = upload("a\n1");
    for (int round = 0; round < 20; round++) {
      UUID id = jobs.create("alice", dataset).id();
      var claim = jobs.claim(id, "worker");
      var start = new java.util.concurrent.CountDownLatch(1);
      try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
        var complete =
            pool.submit(
                () -> {
                  start.await();
                  return jobs.complete(claim, "worker", "output/" + id);
                });
        var cancel =
            pool.submit(
                () -> {
                  start.await();
                  return jobs.cancel("alice", id);
                });
        start.countDown();
        boolean committed = complete.get();
        cancel.get();
        var state = jobs.getInternal(id);
        assertThat(state.status()).isIn("SUCCEEDED", "CANCELLED");
        assertThat(committed).isEqualTo(state.status().equals("SUCCEEDED"));
        if (state.status().equals("CANCELLED")) {
          assertThat(state.outputKey()).isNull();
          assertThat(jobs.heartbeat(claim, "worker")).isFalse();
        }
      }
    }
  }

  @Test
  void totalDeadlineTerminatesQueuedRunningAndRetryingJobs() throws Exception {
    UUID dataset = upload("a\n1");
    UUID queued = jobs.create("alice", dataset).id(),
        running = jobs.create("alice", dataset).id(),
        retry = jobs.create("alice", dataset).id();
    var old = jobs.claim(retry, "retry-worker");
    jobs.fail(old, "retry-worker", "TRANSIENT", false);
    var active = jobs.claim(running, "active-worker");
    db.update("UPDATE jobs SET deadline_at=clock_timestamp()-interval '1 second'");
    assertThat(jobs.expireDeadlines()).isEqualTo(3);
    for (UUID id : List.of(queued, running, retry)) {
      assertThat(jobs.getInternal(id).status()).isEqualTo("FAILED");
      assertThat(jobs.getInternal(id).errorCode()).isEqualTo("DEADLINE_EXCEEDED");
    }
    assertThat(jobs.complete(active, "active-worker", "late-output")).isFalse();
  }

  @Test
  void authenticationRunsBeforeMultipartParsingAndUnknownFieldsAreRejected() throws Exception {
    var response =
        http.send(
            HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/datasets"))
                .header("Content-Type", "multipart/form-data; boundary=broken")
                .POST(HttpRequest.BodyPublishers.ofString("not a valid multipart body"))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(response.statusCode()).isEqualTo(401);
    UUID dataset = upload("a\n1");
    assertThat(
            request(
                    "POST",
                    "/jobs",
                    ALICE,
                    "{\"datasetId\":\"" + dataset + "\",\"ownerId\":\"bob\"}")
                .statusCode())
        .isEqualTo(400);
    assertThat(db.queryForObject("SELECT count(*) FROM jobs", Integer.class)).isZero();
  }

  @Test
  void filenamesDoNotControlObjectPathsAndUnreadyInputsCannotBeSubmitted() throws Exception {
    UUID dataset = upload("a\n1", ALICE, "../../outside.csv");
    String key =
        db.queryForObject("SELECT object_key FROM datasets WHERE id=?", String.class, dataset);
    assertThat(key).startsWith("inputs/").doesNotContain("..").doesNotContain("outside");
    UUID pending = UUID.randomUUID();
    jobs.reserveDataset(pending, "alice", "inputs/" + pending, "0".repeat(64), 12);
    assertThatThrownBy(() -> jobs.create("alice", pending))
        .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
  }

  @Test
  void competingStorageReservationsRespectOwnerBudget() throws Exception {
    var start = new java.util.concurrent.CountDownLatch(1);
    try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
      var calls = new ArrayList<java.util.concurrent.Future<Integer>>();
      for (int i = 0; i < 2; i++)
        calls.add(
            pool.submit(
                () -> {
                  start.await();
                  UUID id = UUID.randomUUID();
                  try {
                    jobs.reserveDataset(id, "alice", "inputs/" + id, "0".repeat(64), 40_000);
                    return 201;
                  } catch (org.springframework.web.server.ResponseStatusException e) {
                    return e.getStatusCode().value();
                  }
                }));
      start.countDown();
      assertThat(List.of(calls.get(0).get(), calls.get(1).get()))
          .containsExactlyInAnyOrder(201, 429);
    }
  }

  @Test
  void cleanupPreservesLiveAndAuthoritativeObjectsThenExpiresReports() throws Exception {
    UUID dataset = upload("a\n2\n4");
    UUID id = jobs.create("alice", dataset).id();
    var claim = jobs.claim(id, "live");
    String key = "reports/" + id + "/candidate.json";
    jobs.candidateOutput(claim, "live", key);
    storage.putReport(key, "{}".getBytes());
    db.update(
        "UPDATE datasets SET created_at=clock_timestamp()-interval '8 days' WHERE id=?", dataset);
    cleanup.cleanOnce();
    assertThat(
            db.queryForObject(
                "SELECT expired_at IS NULL FROM datasets WHERE id=?", Boolean.class, dataset))
        .isTrue();
    try (var input = storage.read(key)) {
      assertThat(input.read()).isNotEqualTo(-1);
    }
    runner.run(claim, "live");
    String canonical = jobs.getInternal(id).outputKey();
    cleanup.cleanOnce();
    try (var input = storage.read(canonical)) {
      assertThat(json.readTree(input).get("rowCount").asInt()).isEqualTo(2);
    }
    db.update("UPDATE jobs SET finished_at=clock_timestamp()-interval '8 days' WHERE id=?", id);
    cleanup.cleanOnce();
    assertThat(request("GET", "/jobs/" + id + "/result", ALICE, null).statusCode()).isEqualTo(410);
    assertThatThrownBy(() -> storage.read(canonical)).isInstanceOf(Exception.class);
  }

  @Test
  void abandonedAttemptsAndFailedUploadsAreEventuallyDeleted() throws Exception {
    UUID dataset = upload("a\n1"), id = jobs.create("alice", dataset).id();
    var old = jobs.claim(id, "old");
    String orphan = "reports/" + id + "/orphan.json";
    jobs.candidateOutput(old, "old", orphan);
    storage.putReport(orphan, "{}".getBytes());
    db.update(
        "UPDATE jobs SET lease_expires_at=clock_timestamp()-interval '1 second' WHERE id=?", id);
    jobs.recoverExpired();
    db.update(
        "UPDATE job_attempts SET finished_at=clock_timestamp()-interval '2 hours' WHERE job_id=?",
        id);
    cleanup.cleanOnce();
    assertThatThrownBy(() -> storage.read(orphan)).isInstanceOf(Exception.class);
    // A very late stale upload is not authoritative; periodic rechecking removes it again.
    storage.putReport(orphan, "{}".getBytes());
    db.update(
        "UPDATE job_attempts SET last_cleanup_at=clock_timestamp()-interval '2 hours' WHERE job_id=?",
        id);
    cleanup.cleanOnce();
    assertThatThrownBy(() -> storage.read(orphan)).isInstanceOf(Exception.class);
    UUID reserved = UUID.randomUUID();
    String inputKey = "inputs/" + reserved;
    jobs.reserveDataset(reserved, "alice", inputKey, "0".repeat(64), 4);
    storage.putReport(inputKey, "{}".getBytes());
    db.update(
        "UPDATE datasets SET created_at=clock_timestamp()-interval '2 hours' WHERE id=?", reserved);
    cleanup.cleanOnce();
    assertThatThrownBy(() -> storage.read(inputKey)).isInstanceOf(Exception.class);
  }

  @Test
  void modifiedInputIsDetectedBeforeAnyReportCanCommit() throws Exception {
    UUID id = jobs.create("alice", upload("a\n1")).id();
    storage.putReport(jobs.getInternal(id).inputKey(), "a\n9".getBytes());
    runner.run(jobs.claim(id, "worker"), "worker");
    assertThat(jobs.getInternal(id).status()).isEqualTo("FAILED");
    assertThat(jobs.getInternal(id).outputKey()).isNull();
  }

  @Test
  void oversizedUploadIsRejectedWithoutHoldingTheEntireFileInClientMemory() throws Exception {
    byte[] header =
        "--limit-test\r\nContent-Disposition: form-data; name=\"file\"; filename=\"large.csv\"\r\nContent-Type: text/csv\r\n\r\n"
            .getBytes();
    byte[] footer = "\r\n--limit-test--\r\n".getBytes();
    var publisher =
        HttpRequest.BodyPublishers.ofInputStream(
            () ->
                new java.io.SequenceInputStream(
                    java.util.Collections.enumeration(
                        List.of(
                            new java.io.ByteArrayInputStream(header),
                            new java.io.InputStream() {
                              long remaining = 52_428_801L;

                              @Override
                              public int read() {
                                if (remaining == 0) return -1;
                                remaining--;
                                return 'a';
                              }

                              @Override
                              public int read(byte[] b, int offset, int length) {
                                if (remaining == 0) return -1;
                                int n = (int) Math.min(length, remaining);
                                java.util.Arrays.fill(b, offset, offset + n, (byte) 'a');
                                remaining -= n;
                                return n;
                              }
                            },
                            new java.io.ByteArrayInputStream(footer)))));
    var response =
        http.send(
            HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/datasets"))
                .header("Authorization", "Bearer " + ALICE)
                .header("Content-Type", "multipart/form-data; boundary=limit-test")
                .POST(publisher)
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(response.statusCode()).isEqualTo(413);
    assertThat(db.queryForObject("SELECT count(*) FROM datasets", Integer.class)).isZero();
  }

  @Test
  void metricsDistinguishQueueExecutionAndDurableHistoryWithoutJobLabels() throws Exception {
    UUID id = jobs.create("alice", upload("a\n2\n4")).id();
    metrics.refresh();
    assertThat(metrics.value("queued")).isEqualTo(1);
    long before = metrics.execution.count();
    runner.run(jobs.claim(id, "metric-worker"), "metric-worker");
    metrics.refresh();
    assertThat(metrics.value("queued")).isZero();
    assertThat(metrics.value("succeeded_total")).isEqualTo(1);
    assertThat(metrics.execution.count()).isEqualTo(before + 1);
    assertThat(metrics.active.get()).isZero();
    for (var meter : meters.getMeters())
      if (meter.getId().getName().startsWith("taskforge"))
        for (var tag : meter.getId().getTags())
          assertThat(tag.getKey()).isNotIn("job_id", "owner_id", "user_id");
  }
}
