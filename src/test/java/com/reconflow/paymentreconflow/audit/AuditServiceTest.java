package com.reconflow.paymentreconflow.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.reconflow.paymentreconflow.security.tenant.ScopedTenantHolder;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class AuditServiceTest {

    private final EntityManager em = mock(EntityManager.class);
    private final ScopedTenantHolder holder = new ScopedTenantHolder();
    private final AuditService audit =
            new AuditService(em, holder, holder, JsonMapper.builder().build());

    @Test
    void payloadOverTheCapIsRejectedAndNothingIsPersisted() {
        var entry = AuditEntry.of(AuditAction.LOGOUT, AuditEntityType.USER, UUID.randomUUID())
                .with("blob", "x".repeat(AuditService.MAX_PAYLOAD_BYTES));

        assertThatIllegalArgumentException()
                .isThrownBy(() -> audit.record(entry))
                .withMessageContaining("limit");
        verify(em, never()).persist(any());
    }

    @Test
    void entriesAreImmutableAndMoneyBecomesAPlainString() {
        var base = AuditEntry.of(AuditAction.EXCEPTION_RESOLVED, AuditEntityType.EXCEPTION_CASE, null);
        var withAmount = base.with("amount", new BigDecimal("1E+3")).with("assignedTo", null);

        assertThat(base.payload()).isEmpty();
        assertThat(withAmount.payload()).containsEntry("amount", "1000").containsEntry("assignedTo", null);
    }
}
