package com.reconflow.paymentreconflow.reliability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.reconflow.paymentreconflow.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.health.actuate.endpoint.HealthEndpointGroups;

/**
 * M9.11: liveness checks only the application itself; readiness also checks the database and object
 * storage. (Stopping the database made readiness DOWN and left liveness UP on the benchmark stack:
 * docs/benchmarks.md §5. Doing it here would take the shared test database away from every other
 * test.)
 */
class HealthGroupsIT extends AbstractIntegrationTest {

    @Autowired
    HealthEndpointGroups groups;

    @Test
    void livenessNeverDependsOnADependency() {
        var liveness = groups.get("liveness");
        assertThat(liveness.isMember("livenessState")).isTrue();
        assertThat(liveness.isMember("db")).isFalse();
        assertThat(liveness.isMember("storage")).isFalse();
    }

    @Test
    void readinessChecksTheDatabaseAndStorage() {
        var readiness = groups.get("readiness");
        assertThat(readiness.isMember("readinessState")).isTrue();
        assertThat(readiness.isMember("db")).isTrue();
        assertThat(readiness.isMember("storage")).isTrue();
    }

    @Test
    void bothProbesAreUpWhenEverythingIs() throws Exception {
        mockMvc.perform(get("/actuator/health/liveness")).andExpect(status().isOk());
        mockMvc.perform(get("/actuator/health/readiness")).andExpect(status().isOk());
    }
}
