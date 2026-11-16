-- =====================================================================
-- ReconFlow — archival of old transaction_record partitions (M9.16)
--
-- docs/scalability.md §5.3: 13 months stay hot in PostgreSQL; an older
-- month is exported to object storage, verified, and its partition
-- detached and dropped. Each exported file gets one manifest row: what
-- was archived, where it is, and the figures it was verified against
-- (computed in the database before the export, recomputed from the file
-- after the upload). The manifest is the record that the data still
-- exists; it outlives the partition.
--
-- One row per (month, organization, table): transaction_record and its
-- dependents (reconciliation_match, exception_case_record), which must
-- be archived and deleted first, because their foreign keys into the
-- partition would refuse the drop.
--
-- Tenant-owned (organization_id), so ENABLE + FORCE row-level security
-- like every other tenant table (V4, CONTRIBUTING.md); the archival job
-- binds each organization in turn. A new table: no backfill.
-- =====================================================================

CREATE TABLE archive_manifest (
    id               UUID          PRIMARY KEY,
    organization_id  UUID          NOT NULL REFERENCES organization (id),
    month            DATE          NOT NULL CHECK (month = date_trunc('month', month)::date),
    table_name       TEXT          NOT NULL
                                   CHECK (table_name IN ('transaction_record', 'reconciliation_match',
                                                         'exception_case_record')),
    object_key       TEXT          NOT NULL,                -- gzipped CSV in object storage
    row_count        BIGINT        NOT NULL CHECK (row_count >= 0),
    amount_sum       NUMERIC(19,4),                         -- transaction_record only
    content_sha256   CHAR(64)      NOT NULL,                -- of the uncompressed CSV
    created_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    UNIQUE (organization_id, month, table_name)
);

ALTER TABLE archive_manifest ENABLE ROW LEVEL SECURITY;
ALTER TABLE archive_manifest FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON archive_manifest
    USING (organization_id = app_current_org())
    WITH CHECK (organization_id = app_current_org());
