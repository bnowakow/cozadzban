ALTER TABLE facebook_import_run
    ADD COLUMN import_trigger VARCHAR(32) NOT NULL DEFAULT 'MANUAL',
    ADD CONSTRAINT facebook_import_run_import_trigger_check
        CHECK (import_trigger IN ('MANUAL', 'WORKER_STARTUP', 'SCHEDULED'));
