package com.reconflow.paymentreconflow.security.token;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Deletes whole token families a month past their 30-day cap (authentication.plan §10), so the
 * table doesn't grow forever. By family cap, not by token expiry: a rotated token must survive as
 * long as its family is live, or replaying it would read as "unknown" instead of triggering reuse
 * detection. Served by refresh_token_family_expiry_idx (V3).
 *
 * <p>Worker nodes only, like every background job.
 */
@Component
@ConditionalOnBooleanProperty("reconflow.jobs.worker.enabled")
public class RefreshTokenSweeper {

    private static final Logger log = LoggerFactory.getLogger(RefreshTokenSweeper.class);

    private final JdbcClient jdbc;

    public RefreshTokenSweeper(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Scheduled(cron = "${reconflow.auth.refresh.sweep-cron:0 30 3 * * *}", zone = "UTC")
    public int sweep() {
        // One statement, so the replaced_by self-references inside a family are checked only at
        // the end of it, after every row of the family is gone.
        int deleted = jdbc.sql("DELETE FROM refresh_token WHERE family_expires_at < now() - interval '30 days'")
                .update();
        log.info("Refresh-token sweep deleted {} rows", deleted);
        return deleted;
    }
}
