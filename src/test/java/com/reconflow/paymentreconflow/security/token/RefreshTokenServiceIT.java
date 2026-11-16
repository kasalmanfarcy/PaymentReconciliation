package com.reconflow.paymentreconflow.security.token;

import static org.assertj.core.api.Assertions.assertThat;

import com.reconflow.paymentreconflow.security.token.RefreshTokenService.RotationResult;
import com.reconflow.paymentreconflow.support.AbstractIntegrationTest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/** M3.3c: rotation, reuse detection and the family cap, against the real schema. */
class RefreshTokenServiceIT extends AbstractIntegrationTest {

    @Autowired
    RefreshTokenService tokens;

    @Autowired
    RefreshTokenRepository repository;

    @Autowired
    TransactionTemplate tx;

    @Autowired
    JdbcClient jdbcClient;

    private String issueFor(UUID userId) {
        return tx.execute(s -> tokens.issue(userId));
    }

    private RefreshToken row(String raw) {
        return repository.findByTokenHash(TokenHasher.sha256Hex(raw)).orElseThrow();
    }

    @Test
    void onlyTheHashIsStored() {
        var user = data().newUser();
        String raw = issueFor(user.id());

        assertThat(raw).hasSize(43); // 32 bytes, base64url, no padding
        RefreshToken stored = row(raw);
        assertThat(stored.getTokenHash()).hasSize(64).isNotEqualTo(raw);
        assertThat(stored.getFamilyExpiresAt()).isCloseTo(Instant.now().plus(30, ChronoUnit.DAYS), within());
        assertThat(stored.getExpiresAt()).isCloseTo(Instant.now().plus(7, ChronoUnit.DAYS), within());
    }

    @Test
    void rotationReturnsANewTokenAndMarksTheOldOneRotated() {
        var user = data().newUser();
        String first = issueFor(user.id());

        RotationResult result = tokens.rotate(first);

        assertThat(result).isInstanceOf(RotationResult.Rotated.class);
        String second = ((RotationResult.Rotated) result).newToken();
        RefreshToken old = row(first);
        RefreshToken replacement = row(second);
        assertThat(old.getRevokedReason()).isEqualTo(RevocationReason.ROTATED);
        assertThat(old.getReplacedBy()).isEqualTo(replacement.getId());
        assertThat(replacement.getFamilyId()).isEqualTo(old.getFamilyId());
        assertThat(replacement.getFamilyExpiresAt()).isEqualTo(old.getFamilyExpiresAt()); // copied, not reset
        assertThat(replacement.getRevokedAt()).isNull();
    }

    @Test
    void presentingARotatedTokenAgainRevokesTheWholeFamily() {
        var user = data().newUser();
        String first = issueFor(user.id());
        String second = ((RotationResult.Rotated) tokens.rotate(first)).newToken();

        // The thief (or the user) replays the old value.
        RotationResult replay = tokens.rotate(first);

        assertThat(replay).isInstanceOf(RotationResult.ReuseDetected.class);
        assertThat(row(second).getRevokedReason()).isEqualTo(RevocationReason.REUSE_DETECTED);
        assertThat(repository.findByFamilyId(row(first).getFamilyId()))
                .allSatisfy(t -> assertThat(t.getRevokedAt()).isNotNull());
        // The legitimate holder of the newest token is logged out too: both must log in again.
        assertThat(tokens.rotate(second)).isInstanceOf(RotationResult.Rejected.class);
    }

    @Test
    void aLogoutRevokedTokenIsRejectedWithoutRaisingReuse() {
        var user = data().newUser();
        String raw = issueFor(user.id());
        UUID family = row(raw).getFamilyId();
        tx.executeWithoutResult(s -> tokens.revokeFamily(family, RevocationReason.LOGOUT));

        assertThat(tokens.rotate(raw)).isInstanceOf(RotationResult.Rejected.class);
        assertThat(row(raw).getRevokedReason()).isEqualTo(RevocationReason.LOGOUT); // not REUSE_DETECTED
    }

    @Test
    void anUnknownOrExpiredTokenIsRejected() {
        var user = data().newUser();
        String raw = issueFor(user.id());
        jdbc.update(
                "update refresh_token set expires_at = now() - interval '1 second' where token_hash = ?",
                TokenHasher.sha256Hex(raw));

        assertThat(tokens.rotate(raw)).isInstanceOf(RotationResult.Rejected.class);
        assertThat(tokens.rotate(TokenHasher.newToken())).isInstanceOf(RotationResult.Rejected.class);
    }

    @Test
    void aRotationNearTheFamilyCapIsClampedToTheCap() {
        var user = data().newUser();
        String raw = issueFor(user.id());
        // Day 29 of the family: a full 7-day token would outlive the 30-day cap.
        jdbc.update(
                "update refresh_token set family_expires_at = now() + interval '1 day' where token_hash = ?",
                TokenHasher.sha256Hex(raw));

        String next = ((RotationResult.Rotated) tokens.rotate(raw)).newToken();

        RefreshToken rotated = row(next);
        assertThat(rotated.getExpiresAt()).isEqualTo(rotated.getFamilyExpiresAt());
    }

    @Test
    void twoConcurrentRotationsOfOneTokenProduceExactlyOneSuccess() throws Exception {
        var user = data().newUser();
        String raw = issueFor(user.id());
        var start = new CountDownLatch(1);
        List<Future<RotationResult>> results = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            for (int i = 0; i < 2; i++) {
                results.add(pool.submit(() -> {
                    start.await();
                    return tokens.rotate(raw);
                }));
            }
            start.countDown();
        }

        long successes = 0;
        for (Future<RotationResult> f : results) {
            if (f.get() instanceof RotationResult.Rotated) {
                successes++;
            }
        }
        assertThat(successes).isEqualTo(1);
        // The loser rolled its insert back: the family has the original plus one replacement.
        assertThat(repository.findByFamilyId(row(raw).getFamilyId())).hasSize(2);
    }

    @Test
    void theSweeperDeletesFamiliesPastTheirCap() {
        var user = data().newUser();
        String expired = issueFor(user.id());
        String live = issueFor(user.id());
        ((RotationResult.Rotated) tokens.rotate(expired)).newToken(); // a two-row family with replaced_by
        jdbc.update(
                "update refresh_token set family_expires_at = now() - interval '31 days' where family_id = ?",
                row(expired).getFamilyId());

        new RefreshTokenSweeper(jdbcClient).sweep();

        assertThat(repository.findByTokenHash(TokenHasher.sha256Hex(expired))).isEmpty();
        assertThat(repository.findByTokenHash(TokenHasher.sha256Hex(live))).isPresent();
    }

    private static org.assertj.core.data.TemporalUnitOffset within() {
        return org.assertj.core.api.Assertions.within(1, ChronoUnit.MINUTES);
    }
}
