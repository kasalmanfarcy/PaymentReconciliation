package com.reconflow.paymentreconflow.support;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Counts the SQL statements the database executed (M9.7's N+1 checks), from PostgreSQL's
 * {@code pg_stat_statements} in the test container. Integration tests run one at a time and the job
 * worker is off in tests, so between {@link #reset()} and {@link #statements()} the only statements
 * are the request's: its queries, its {@code set_config} for row-level security, and nothing else.
 */
public final class QueryCounter {

    private final JdbcTemplate jdbc;

    public QueryCounter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void reset() {
        jdbc.execute("SELECT pg_stat_statements_reset()");
    }

    /** Statements executed since {@link #reset()}, not counting the counter's own. */
    public long statements() {
        Long n = jdbc.queryForObject(
                "SELECT coalesce(sum(calls), 0) FROM pg_stat_statements"
                        + " WHERE dbid = (SELECT oid FROM pg_database WHERE datname = current_database())"
                        + " AND query NOT ILIKE '%pg_stat_statements%'",
                Long.class);
        return n == null ? 0 : n;
    }
}
