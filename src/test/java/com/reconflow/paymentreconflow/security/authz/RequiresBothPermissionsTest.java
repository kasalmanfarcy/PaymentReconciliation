package com.reconflow.paymentreconflow.security.authz;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.reconflow.paymentreconflow.shared.context.Permission;
import com.reconflow.paymentreconflow.shared.context.RequiresBothPermissions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.annotation.AnnotationTemplateExpressionDefaults;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

/**
 * M8: {@code @RequiresBothPermissions} really needs both. Every role holds REPORT_READ and
 * EXCEPTION_READ today, so AuthorizationMatrixIT can't show a caller with only one of them being
 * refused; this proves the expression itself, with the same template defaults SecurityConfig
 * registers.
 */
@SpringJUnitConfig(RequiresBothPermissionsTest.Config.class)
class RequiresBothPermissionsTest {

    @Configuration
    @EnableMethodSecurity
    static class Config {

        @Bean
        static AnnotationTemplateExpressionDefaults annotationTemplateExpressionDefaults() {
            return new AnnotationTemplateExpressionDefaults();
        }

        @Bean
        Guarded guarded() {
            return new Guarded();
        }
    }

    static class Guarded {

        @RequiresBothPermissions(value = Permission.REPORT_READ, and = Permission.EXCEPTION_READ)
        public String report() {
            return "report";
        }
    }

    @Autowired
    Guarded guarded;

    @Test
    @WithMockUser(authorities = {"REPORT_READ", "EXCEPTION_READ"})
    void bothPermissionsAllow() {
        assertThat(guarded.report()).isEqualTo("report");
    }

    @Test
    @WithMockUser(authorities = "REPORT_READ")
    void reportReadAloneIsRefused() {
        assertThatThrownBy(guarded::report).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @WithMockUser(authorities = "EXCEPTION_READ")
    void exceptionReadAloneIsRefused() {
        assertThatThrownBy(guarded::report).isInstanceOf(AccessDeniedException.class);
    }
}
