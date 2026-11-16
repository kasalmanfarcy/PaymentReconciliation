package com.reconflow.paymentreconflow.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

/** M5.9 / M5.23: the same file twice follows ADR 0005. */
class DuplicateImportIT extends AbstractImportIT {

    @Test
    void theSecondUploadOfTheSameFileIsRejectedWithTheFirstBatchId() throws Exception {
        Tenant t = newTenant();
        UUID first = uploadOk(t, "gateway-basic.csv", sample("gateway-basic.csv"), "GATEWAY");

        MockHttpServletResponse second = upload(t, "renamed.csv", sample("gateway-basic.csv"), "GATEWAY");

        assertThat(second.getStatus()).isEqualTo(409);
        assertThat(second.getContentType()).isEqualTo("application/problem+json");
        Map<String, Object> problem = json(second);
        assertThat(problem)
                .containsEntry("type", "https://reconflow.dev/problems/duplicate-file")
                .containsEntry("code", "duplicate-file")
                .containsEntry("status", 409)
                .containsEntry("existingBatchId", first.toString())
                .containsEntry("requestId", second.getHeader("X-Request-Id"));
        assertThat(count("SELECT count(*) FROM import_batch WHERE organization_id = ?", t.org()))
                .isEqualTo(1);
        assertThat(count("SELECT count(*) FROM job WHERE organization_id = ?", t.org()))
                .isEqualTo(1);
        assertThat(objects(t)).isEqualTo(1);
    }

    @Test
    void twoConcurrentUploadsOfTheSameFileCreateOneBatchAndLeaveNoOrphan() throws Exception {
        // A few rounds, each in a fresh organization: both requests usually pass the fast path
        // and race on the unique constraint, which is the path this test is about.
        for (int round = 0; round < 3; round++) {
            Tenant t = newTenant();
            byte[] file = utf8("reference,amount,currency,status,transaction_date\nPAY-" + round
                    + ",1.00,AED,SUCCESS,2026-10-01\n");
            CountDownLatch start = new CountDownLatch(1);
            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                List<Future<MockHttpServletResponse>> calls = new ArrayList<>();
                for (int i = 0; i < 2; i++) {
                    calls.add(pool.submit(() -> {
                        start.await();
                        return upload(t, "same.csv", file, "INTERNAL_LEDGER");
                    }));
                }
                start.countDown();
                List<MockHttpServletResponse> responses = new ArrayList<>();
                for (Future<MockHttpServletResponse> c : calls) {
                    responses.add(c.get());
                }

                assertThat(responses)
                        .extracting(MockHttpServletResponse::getStatus)
                        .containsExactlyInAnyOrder(202, 409);
                MockHttpServletResponse winner = responses.stream()
                        .filter(r -> r.getStatus() == 202)
                        .findFirst()
                        .orElseThrow();
                MockHttpServletResponse loser = responses.stream()
                        .filter(r -> r.getStatus() == 409)
                        .findFirst()
                        .orElseThrow();
                assertThat(json(loser))
                        .containsEntry("existingBatchId", json(winner).get("id"));
                assertThat(count("SELECT count(*) FROM import_batch WHERE organization_id = ?", t.org()))
                        .isEqualTo(1);
                assertThat(count("SELECT count(*) FROM job WHERE organization_id = ?", t.org()))
                        .isEqualTo(1);
                assertThat(objects(t))
                        .as("the loser's stored object is deleted")
                        .isEqualTo(1);
            } finally {
                pool.shutdownNow();
            }
        }
    }

    @Test
    void theSameBytesAsTheOtherSourceTypeAreADifferentImport() throws Exception {
        Tenant t = newTenant();
        // Valid under both contracts: a ledger reference and a gateway merchant_reference.
        byte[] both = utf8("reference,merchant_reference,amount,currency,status,transaction_at\n"
                + "PAY-1,PAY-1,1.00,AED,SUCCESS,2026-10-01T05:15:00Z\n");

        uploadOk(t, "both.csv", both, "INTERNAL_LEDGER");
        MockHttpServletResponse asGateway = upload(t, "both.csv", both, "GATEWAY");

        assertThat(asGateway.getStatus()).isEqualTo(202);
        assertThat(count("SELECT count(*) FROM import_batch WHERE organization_id = ?", t.org()))
                .isEqualTo(2);
    }

    @Test
    void theSameFileInAnotherOrganizationIsAccepted() throws Exception {
        uploadOk(newTenant(), "gateway-basic.csv", sample("gateway-basic.csv"), "GATEWAY");

        assertThat(upload(newTenant(), "gateway-basic.csv", sample("gateway-basic.csv"), "GATEWAY")
                        .getStatus())
                .isEqualTo(202);
    }

    protected long objects(Tenant t) {
        long[] n = {0};
        storage.forEach(t.org() + "/imports/", o -> n[0]++);
        return n[0];
    }
}
