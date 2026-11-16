package com.reconflow.paymentreconflow.reliability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.reconflow.paymentreconflow.shared.context.Role;
import com.reconflow.paymentreconflow.shared.id.Ids;
import com.reconflow.paymentreconflow.shared.jobs.JobProperties;
import com.reconflow.paymentreconflow.shared.jobs.JobQueue;
import com.reconflow.paymentreconflow.shared.ratelimit.RateLimitExceededException;
import com.reconflow.paymentreconflow.shared.ratelimit.RateLimitProperties;
import com.reconflow.paymentreconflow.support.AbstractIntegrationTest;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * M10.4: the two layers of rate limiting on the endpoints that create background work. Layer 1 is
 * a per-user, per-organization token bucket; layer 2 an exact cap on an organization's pending jobs,
 * enforced in the database.
 */
class RateLimitIT extends AbstractIntegrationTest {

    @Autowired
    RateLimitProperties limits;

    @Autowired
    JobProperties jobProperties;

    @Autowired
    JobQueue jobQueue;

    @Autowired
    TransactionTemplate tx;

    @Test
    void oneUploadOverTheBucketIs429WithRetryAfterAndAnotherUserIsUnaffected() throws Exception {
        UUID org = data().newOrganization();
        UUID greedy = data().newMember(org, Role.UPLOADER).id();
        UUID colleague = data().newMember(org, Role.UPLOADER).id();
        int capacity = limits.importUpload().capacity();

        List<MockHttpServletResponse> responses = new ArrayList<>();
        for (int i = 0; i <= capacity; i++) {
            responses.add(upload(org, greedy, i));
        }

        List<MockHttpServletResponse> limited =
                responses.stream().filter(r -> r.getStatus() == 429).toList();
        assertThat(limited).as("exactly the request past the capacity").hasSize(1);
        assertThat(responses.getLast().getStatus()).isEqualTo(429);
        MockHttpServletResponse rejected = limited.getFirst();
        assertThat(rejected.getContentType()).startsWith("application/problem+json");
        assertThat(rejected.getContentAsString()).contains("\"code\":\"rate-limited\"");
        assertThat(Long.parseLong(rejected.getHeader("Retry-After"))).isPositive();
        assertThat(responses.getFirst().getHeader("RateLimit-Remaining")).isEqualTo(Integer.toString(capacity - 1));
        // The bucket is the user's, not the organization's.
        assertThat(upload(org, colleague, 99).getStatus()).isEqualTo(202);
        assertThat(count("SELECT count(*) FROM import_batch WHERE organization_id = ?", org))
                .isEqualTo(capacity + 1);
    }

    @Test
    void runRequestsHaveTheirOwnBucket() throws Exception {
        UUID org = data().newOrganization();
        UUID investigator = data().newMember(org, Role.INVESTIGATOR).id();
        int capacity = limits.runCreate().capacity();

        int limitedCount = 0;
        for (int i = 0; i <= capacity; i++) {
            // An invalid body still spends a token: the limit is checked before the request is read.
            int status = mockMvc.perform(post("/api/v1/reconciliation-runs")
                            .with(as(investigator))
                            .with(org(org))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andReturn()
                    .getResponse()
                    .getStatus();
            if (status == 429) {
                limitedCount++;
            } else {
                assertThat(status).isEqualTo(400);
            }
        }
        assertThat(limitedCount).isEqualTo(1);
    }

    @Test
    void theOrganizationsPendingJobCapIsExactUnderConcurrency() throws Exception {
        UUID org = data().newOrganization();
        int cap = jobProperties.maxPendingPerOrg();
        for (int i = 0; i < cap - 1; i++) {
            jdbc.update(
                    "INSERT INTO job (id, organization_id, type, payload) VALUES (?, ?, 'TEST', '{}'::jsonb)",
                    Ids.newId(),
                    org);
        }

        int threads = 10;
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> results = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            for (int i = 0; i < threads; i++) {
                results.add(pool.submit(() -> {
                    start.await();
                    try {
                        asTenant(
                                org,
                                () -> tx.execute(s -> jobQueue.enqueue(org, "TEST", Map.of("behavior", "succeed"))));
                        return true;
                    } catch (RateLimitExceededException e) {
                        return false;
                    }
                }));
            }
            start.countDown();
            int enqueued = 0;
            for (Future<Boolean> result : results) {
                enqueued += result.get() ? 1 : 0;
            }
            assertThat(enqueued).as("only one fits under the cap").isEqualTo(1);
        }
        assertThat(count("SELECT count(*) FROM job WHERE organization_id = ? AND status IN ('QUEUED', 'RUNNING')", org))
                .isEqualTo(cap);
    }

    @Test
    void anUploadOverThePendingCapIs429AndLeavesNoBatchBehind() throws Exception {
        UUID org = data().newOrganization();
        UUID uploader = data().newMember(org, Role.UPLOADER).id();
        for (int i = 0; i < jobProperties.maxPendingPerOrg(); i++) {
            jdbc.update(
                    "INSERT INTO job (id, organization_id, type, payload) VALUES (?, ?, 'TEST', '{}'::jsonb)",
                    Ids.newId(),
                    org);
        }

        MockHttpServletResponse res = upload(org, uploader, 1);

        assertThat(res.getStatus()).as(res.getContentAsString()).isEqualTo(429);
        assertThat(res.getHeader("Retry-After")).isEqualTo("30");
        // The batch row was in the same transaction as the job: rolled back together.
        assertThat(count("SELECT count(*) FROM import_batch WHERE organization_id = ?", org))
                .isZero();
    }

    private MockHttpServletResponse upload(UUID org, UUID user, int n) throws Exception {
        String csv = "reference,amount,currency,status,transaction_at,transaction_type\n" + "PAY-" + n + "-"
                + UUID.randomUUID() + ",10.00,AED,SUCCESS,2026-10-01T09:15:00Z,PAYMENT\n";
        return mockMvc.perform(multipart("/api/v1/imports")
                        .file(new MockMultipartFile(
                                "file", "ledger-" + n + ".csv", "text/csv", csv.getBytes(StandardCharsets.UTF_8)))
                        .param("sourceType", "INTERNAL_LEDGER")
                        .with(as(user))
                        .with(org(org)))
                .andReturn()
                .getResponse();
    }

    private long count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }
}
