package com.reconflow.paymentreconflow.security.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.reconflow.paymentreconflow.shared.error.ValidationFailedException;
import java.util.List;
import org.junit.jupiter.api.Test;

class MemberQueriesTest {

    @Test
    void defaultOrderIsNewestFirstWithATiebreaker() {
        assertThat(MemberQueries.orderBy(List.of())).isEqualTo("m.created_at DESC, u.id DESC");
    }

    @Test
    void acceptsSplitAndUnsplitSortParameters() {
        // ?sort=role,desc&sort=email arrives as [role, desc, email] or [role,desc, email].
        assertThat(MemberQueries.orderBy(List.of("role", "desc", "email")))
                .isEqualTo("m.role DESC, lower(u.email) ASC, u.id DESC");
        assertThat(MemberQueries.orderBy(List.of("role,desc", "email")))
                .isEqualTo("m.role DESC, lower(u.email) ASC, u.id DESC");
    }

    @Test
    void unknownFieldsNeverReachSql() {
        assertThatThrownBy(() -> MemberQueries.orderBy(List.of("password_hash; drop table app_user")))
                .isInstanceOf(ValidationFailedException.class);
    }
}
