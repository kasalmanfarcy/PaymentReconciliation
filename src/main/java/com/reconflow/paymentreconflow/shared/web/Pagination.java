package com.reconflow.paymentreconflow.shared.web;

import com.reconflow.paymentreconflow.shared.error.ValidationFailedException;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The paging rules of api-design.md §4 for every list endpoint: 0-based {@code page}, {@code size}
 * defaulting to 50 and <em>clamped</em> to 200 (a client asking for "everything" gets a full page,
 * not an error), so no endpoint can be asked for a million rows.
 */
public final class Pagination {

    public static final int DEFAULT_SIZE = 50;
    public static final int MAX_SIZE = 200;

    private Pagination() {}

    public static int page(int page) {
        return Math.max(page, 0);
    }

    public static int size(int size) {
        return size < 1 ? DEFAULT_SIZE : Math.min(size, MAX_SIZE);
    }

    /**
     * For lists with one fixed order (a batch's errors by row, a run's matches, a timeline by time):
     * api-design.md §4 makes every unknown sort field a 400, so a {@code sort} the endpoint can't honour
     * is refused rather than silently ignored (M9.8).
     */
    public static void requireNoSort(@Nullable List<String> sort, String order) {
        if (sort != null && !sort.isEmpty()) {
            throw new ValidationFailedException(
                    "sort", "unsupported-sort-field", "This list has a fixed order: " + order);
        }
    }
}
