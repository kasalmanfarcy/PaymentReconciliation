package com.reconflow.paymentreconflow.ledger.internal;

import com.reconflow.paymentreconflow.ledger.SourceType;
import com.reconflow.paymentreconflow.shared.context.Permission;
import com.reconflow.paymentreconflow.shared.context.RequiresPermission;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /api/v1/transactions} (api-design.md §4, §8.7). Range filters follow the API's
 * {@code <field>From}/{@code <field>To} naming: {@code transactionDateFrom}, {@code transactionDateTo}.
 */
@RestController
@RequestMapping("/api/v1/transactions")
class TransactionController {

    private final TransactionSearch search;

    TransactionController(TransactionSearch search) {
        this.search = search;
    }

    @GetMapping
    @RequiresPermission(Permission.TRANSACTION_READ)
    SliceView<TransactionView> search(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate transactionDateFrom,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate transactionDateTo,
            @RequestParam(required = false) @Nullable SourceType sourceType,
            @RequestParam(required = false) @Nullable String reference,
            @RequestParam(required = false) @Nullable String currency,
            @RequestParam(required = false) @Nullable UUID batchId,
            @RequestParam(required = false) @Nullable List<String> sort,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return search.search(new TransactionSearch.Criteria(
                transactionDateFrom, transactionDateTo, sourceType, reference, currency, batchId, sort, page, size));
    }
}
