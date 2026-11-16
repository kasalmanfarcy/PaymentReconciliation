package com.reconflow.paymentreconflow.ledger.internal;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * When partition maintenance runs: on worker nodes only, once at startup (so a fresh deploy or a
 * database restored from an old backup is covered at once) and daily at 02:15 UTC, away from the
 * morning upload burst, because creating a partition briefly locks the parent table. The logic is
 * in {@link PartitionMaintenance}, which tests call directly.
 */
@Component
@ConditionalOnBooleanProperty("reconflow.jobs.worker.enabled")
class PartitionMaintenanceSchedule {

    private final PartitionMaintenance maintenance;

    PartitionMaintenanceSchedule(PartitionMaintenance maintenance) {
        this.maintenance = maintenance;
    }

    @EventListener(ApplicationReadyEvent.class)
    void onStartup() {
        maintenance.maintain();
    }

    @Scheduled(cron = "${reconflow.partitions.maintenance-cron:0 15 2 * * *}", zone = "UTC")
    void daily() {
        maintenance.maintain();
    }
}
