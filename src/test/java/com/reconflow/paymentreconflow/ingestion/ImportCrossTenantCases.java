package com.reconflow.paymentreconflow.ingestion;

import static com.reconflow.paymentreconflow.tenancy.CrossTenantCase.Expectation.AFFECTS_ONLY_A;
import static com.reconflow.paymentreconflow.tenancy.CrossTenantCase.Expectation.LIST_EXCLUDES_B;
import static com.reconflow.paymentreconflow.tenancy.CrossTenantCase.Expectation.NOT_FOUND;

import com.reconflow.paymentreconflow.tenancy.CrossTenantCase;
import com.reconflow.paymentreconflow.tenancy.CrossTenantCases;
import com.reconflow.paymentreconflow.tenancy.MultipartBody;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;

/** The /imports endpoints' cross-tenant cases (M3.9, M5). */
@Component
class ImportCrossTenantCases implements CrossTenantCases {

    static final byte[] LEDGER = """
            reference,amount,currency,status,transaction_date
            PAY-X1,10.00,AED,SUCCESS,2026-10-01
            """.getBytes(StandardCharsets.UTF_8);

    @Override
    public List<CrossTenantCase> cases() {
        return List.of(
                // Uploads always land in the caller's organization; nothing of B changes, and the
                // same bytes B already imported are not a duplicate for A.
                new CrossTenantCase(
                        "upload a file",
                        HttpMethod.POST,
                        "/api/v1/imports",
                        f -> Map.of(),
                        f -> new MultipartBody(
                                "ledger.csv", "text/csv", LEDGER, Map.of("sourceType", "INTERNAL_LEDGER")),
                        AFFECTS_ONLY_A),
                new CrossTenantCase(
                        "list imports", HttpMethod.GET, "/api/v1/imports", f -> Map.of(), null, LIST_EXCLUDES_B),
                new CrossTenantCase(
                        "read org B's import",
                        HttpMethod.GET,
                        "/api/v1/imports/{id}",
                        f -> Map.of("id", f.orgBBatch()),
                        null,
                        NOT_FOUND),
                new CrossTenantCase(
                        "read org B's import errors",
                        HttpMethod.GET,
                        "/api/v1/imports/{id}/errors",
                        f -> Map.of("id", f.orgBBatch()),
                        null,
                        NOT_FOUND),
                // Org B's failed batch must stay FAILED, its job count unchanged (the snapshot).
                new CrossTenantCase(
                        "retry org B's failed import",
                        HttpMethod.POST,
                        "/api/v1/imports/{id}/retry",
                        f -> Map.of("id", f.orgBFailedBatch()),
                        f -> Map.of("reason", "hijack"),
                        NOT_FOUND));
    }
}
