package dev.taskforge;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

@Tag("integration")
@Testcontainers
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
class PlatformIntegrationTest {
    @Container static final PostgreSQLContainer<?> postgres=new PostgreSQLContainer<>("postgres:17-alpine");
    @Container static final GenericContainer<?> redis=new GenericContainer<>("redis:7.4.2-alpine").withExposedPorts(6379);
    @Container static final GenericContainer<?> minio=new GenericContainer<>(DockerImageName.parse("taskforge-minio:2025-10-15"))
            .withEnv("MINIO_ROOT_USER","taskforge-test").withEnv("MINIO_ROOT_PASSWORD","taskforge-test-password")
            .withCommand("server","/data").withExposedPorts(9000)
            .waitingFor(org.testcontainers.containers.wait.strategy.Wait.forHttp("/minio/health/ready").forPort(9000));
    static final String ALICE="test-alice-token-more-than-24-chars", BOB="test-bob-token-more-than-24-chars";
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",postgres::getJdbcUrl);r.add("spring.datasource.username",postgres::getUsername);
        r.add("spring.datasource.password",postgres::getPassword);
        r.add("spring.data.redis.host",redis::getHost);r.add("spring.data.redis.port",()->redis.getMappedPort(6379));
        r.add("taskforge.storage-endpoint",()->"http://"+minio.getHost()+":"+minio.getMappedPort(9000));
        r.add("taskforge.storage-access-key",()->"taskforge-test");r.add("taskforge.storage-secret-key",()->"taskforge-test-password");
        r.add("taskforge.auth-tokens",()->ALICE+"=alice,"+BOB+"=bob");
    }
    @LocalServerPort int port;
    @Autowired JobStore jobs;
    @Autowired org.springframework.jdbc.core.JdbcTemplate db;
    @Autowired ObjectStorage storage;
    @Autowired ObjectMapper json;
    @Autowired Settings settings;
    @Autowired JobRunner runner;
    @Autowired RedisQueue queue;
    final HttpClient http=HttpClient.newHttpClient();
    HttpResponse<String> request(String method,String path,String token,String body) throws Exception {
        var b=HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/api"+path)).header("Content-Type","application/json");
        if(token!=null)b.header("Authorization","Bearer "+token);
        return http.send(b.method(method,HttpRequest.BodyPublishers.ofString(body==null?"":body)).build(),HttpResponse.BodyHandlers.ofString());
    }
    UUID upload(String csv) throws Exception {
        String boundary="taskforge-boundary";
        String body="--"+boundary+"\r\nContent-Disposition: form-data; name=\"file\"; filename=\"sample.csv\"\r\nContent-Type: text/csv\r\n\r\n"+csv+"\r\n--"+boundary+"--\r\n";
        var response=http.send(HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/api/datasets"))
                .header("Authorization","Bearer "+ALICE).header("Content-Type","multipart/form-data; boundary="+boundary)
                .POST(HttpRequest.BodyPublishers.ofString(body,StandardCharsets.UTF_8)).build(),HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        return UUID.fromString(json.readTree(response.body()).get("datasetId").asText());
    }
    @BeforeEach void resetDatabase() { db.execute("TRUNCATE datasets CASCADE"); }
    @Test void competingWorkersGetOnlyOneClaimAndResultCommit() throws Exception {
        UUID id=jobs.create("alice",upload("a\n1")).id();
        var start=new java.util.concurrent.CountDownLatch(1);
        try(var executor=java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var first=executor.submit(() -> { start.await(); return jobs.claimNext("first"); });
            var second=executor.submit(() -> { start.await(); return jobs.claimNext("second"); });
            start.countDown();
            var a=first.get(10,java.util.concurrent.TimeUnit.SECONDS);
            var b=second.get(10,java.util.concurrent.TimeUnit.SECONDS);
            assertThat((a==null?0:1)+(b==null?0:1)).isEqualTo(1);
            var winner=a==null?b:a;
            String worker=a==null?"second":"first";
            assertThat(jobs.complete(winner,worker,"report/first")).isTrue();
            assertThat(jobs.complete(winner,worker,"report/duplicate")).isFalse();
            assertThat(jobs.get("alice",id).outputKey()).isEqualTo("report/first");
        }
    }
    @Test void expiredOwnerCannotFinalizeAnOutput() throws Exception {
        UUID id=jobs.create("alice",upload("a\n1")).id();
        var claim=jobs.claimNext("expired");
        db.update("UPDATE jobs SET lease_expires_at=clock_timestamp()-interval '1 second' WHERE id=?",id);
        assertThat(jobs.complete(claim,"expired","report/stale")).isFalse();
        assertThat(jobs.get("alice",id).outputKey()).isNull();
    }
    @Test void redisGroupDistributesNotificationsAndAcknowledgesDurableCompletion() throws Exception {
        queue.initialize();
        UUID a=jobs.create("alice",upload("n\n2\n4")).id();
        UUID b=jobs.create("alice",upload("n\n5\n7")).id();
        new Dispatcher(jobs,queue).tick();
        var first=queue.receive("worker-one");
        var second=queue.receive("worker-two");
        assertThat(first).isNotNull();assertThat(second).isNotNull();
        assertThat(first.jobId()).isNotEqualTo(second.jobId());
        runner.run(jobs.claim(first.jobId(),"worker-one"),"worker-one");
        runner.run(jobs.claim(second.jobId(),"worker-two"),"worker-two");
        queue.acknowledge(first.messageId());queue.acknowledge(second.messageId());
        assertThat(jobs.getInternal(a).status()).isEqualTo("SUCCEEDED");
        assertThat(jobs.getInternal(b).status()).isEqualTo("SUCCEEDED");
        assertThat(db.queryForList("SELECT DISTINCT worker_id FROM job_attempts",String.class)).contains("worker-one","worker-two");
    }
    @Test void durableUploadJobAndIndependentHandCalculatedReport() throws Exception {
        UUID dataset=upload("name,n\nAlice,2\nBob,4\n");
        var accepted=request("POST","/jobs",ALICE,"{\"datasetId\":\""+dataset+"\"}");
        assertThat(accepted.statusCode()).as(accepted.body()).isEqualTo(202);
        UUID id=UUID.fromString(json.readTree(accepted.body()).get("jobId").asText());
        assertThat(request("GET","/jobs/"+id+"/result",ALICE,null).statusCode()).isEqualTo(409);
        for(int n=0;n<10&&!jobs.get("alice",id).status().equals("SUCCEEDED");n++) new LocalWorker(jobs,runner,settings).tick();
        assertThat(jobs.get("alice",id).status()).isEqualTo("SUCCEEDED");
        var result=request("GET","/jobs/"+id+"/result",ALICE,null);
        assertThat(result.statusCode()).isEqualTo(200);
        var report=json.readTree(result.body());
        assertThat(report.get("rowCount").asInt()).isEqualTo(2);
        assertThat(report.get("columns").get(1).get("numeric").get("sum").asInt()).isEqualTo(6);
        assertThat(report.get("columns").get(1).get("numeric").get("mean").asInt()).isEqualTo(3);
        assertThat(db.queryForObject("SELECT count(*) FROM job_attempts WHERE job_id=?",Integer.class,id)).isEqualTo(1);
    }
    @Test void ownershipAndAuthenticationAreCheckedOnEveryResource() throws Exception {
        UUID dataset=upload("a\n1");
        assertThat(request("POST","/jobs",BOB,"{\"datasetId\":\""+dataset+"\"}").statusCode()).isEqualTo(404);
        UUID job=jobs.create("alice",dataset).id();
        assertThat(request("GET","/jobs/"+job,null,null).statusCode()).isEqualTo(401);
        for(String tail:List.of("","/attempts","/result"))
            assertThat(request("GET","/jobs/"+job+tail,BOB,null).statusCode()).isEqualTo(404);
    }
    @Test void malformedCsvBecomesDiagnosablePermanentFailure() throws Exception {
        UUID job=jobs.create("alice",upload("a,a\n1,2")).id();
        // Other tests can leave queued jobs; drain deterministically to this one.
        for(int n=0;n<10&&!jobs.get("alice",job).status().equals("FAILED");n++) new LocalWorker(jobs,runner,settings).tick();
        assertThat(jobs.get("alice",job).errorCode()).isEqualTo("INVALID_CSV");
    }
}
