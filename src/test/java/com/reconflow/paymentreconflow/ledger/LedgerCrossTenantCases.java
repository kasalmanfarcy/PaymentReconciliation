package com.reconflow.paymentreconflow.ledger;

import static com.reconflow.paymentreconflow.tenancy.CrossTenantCase.Expectation.LIST_EXCLUDES_B;

import com.reconflow.paymentreconflow.tenancy.CrossTenantCase;
import com.reconflow.paymentreconflow.tenancy.CrossTenantCases;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;

/** /transactions' cross-tenant cases (M3.9, M5.19). Org B's fixture record is dated 2026-10-01. */
@Component
class LedgerCrossTenantCases implements CrossTenantCases {

    @Override
    public List<CrossTenantCase> cases() {
        return List.of(
                new CrossTenantCase(
                        "search transactions",
                        HttpMethod.GET,
                        "/api/v1/transactions?transactionDateFrom=2026-10-01&transactionDateTo=2026-10-31",
                        f -> Map.of(),
                        null,
                        LIST_EXCLUDES_B),
                // A filter naming org B's batch matches nothing and doesn't confirm the batch exists.
                new CrossTenantCase(
                        "search transactions by org B's batch",
                        HttpMethod.GET,
                        "/api/v1/transactions?transactionDateFrom=2026-10-01&transactionDateTo=2026-10-31&batchId={batch}",
                        f -> Map.of("batch", f.orgBBatch()),
                        null,
                        LIST_EXCLUDES_B));
    }
}
