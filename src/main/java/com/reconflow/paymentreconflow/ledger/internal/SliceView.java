package com.reconflow.paymentreconflow.ledger.internal;

import java.util.List;

/**
 * The slice envelope of api-design.md §4: like a page, but with {@code hasNext} instead of
 * {@code totalElements}/{@code totalPages}. Counting a month of a large tenant's records would scan
 * millions of index entries for a number nobody pages through; fetching one extra row costs nothing.
 */
public record SliceView<T>(List<T> content, PageInfo page) {

    public record PageInfo(int number, int size, boolean hasNext) {}
}
