package com.reconflow.paymentreconflow.reporting;

import com.reconflow.paymentreconflow.reporting.export.ExportLimit;
import com.reconflow.paymentreconflow.reporting.export.ExportProperties;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * Replaces the production limit in every test context (a component, not a per-test override, so no
 * extra Spring context): the property's value, except for organizations a test lowered it for.
 * Per organization, so a lowered limit can't leak into another test's exports.
 */
@Primary
@Component
class AdjustableExportLimit implements ExportLimit {

    private final ExportProperties props;
    private final Map<UUID, Long> overrides = new ConcurrentHashMap<>();

    AdjustableExportLimit(ExportProperties props) {
        this.props = props;
    }

    void set(UUID organizationId, long syncMaxRows) {
        overrides.put(organizationId, syncMaxRows);
    }

    void reset(UUID organizationId) {
        overrides.remove(organizationId);
    }

    @Override
    public long syncMaxRows(UUID organizationId) {
        return overrides.getOrDefault(organizationId, props.syncMaxRows());
    }
}
