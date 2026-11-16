package com.reconflow.paymentreconflow.reporting.export;

import java.util.UUID;
import org.springframework.stereotype.Component;

/** {@link ExportLimit} from {@code reconflow.export.sync-max-rows}, the same for every organization. */
@Component
class PropertiesExportLimit implements ExportLimit {

    private final ExportProperties props;

    PropertiesExportLimit(ExportProperties props) {
        this.props = props;
    }

    @Override
    public long syncMaxRows(UUID organizationId) {
        return props.syncMaxRows();
    }
}
