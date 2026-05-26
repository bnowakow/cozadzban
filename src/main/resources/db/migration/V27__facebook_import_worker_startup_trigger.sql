ALTER TABLE facebook_import_run
    DROP CONSTRAINT facebook_import_run_login_required_trigger_check,
    ADD CONSTRAINT facebook_import_run_login_required_trigger_check
        CHECK (login_required_trigger IS NULL OR login_required_trigger IN ('MANUAL', 'WORKER_STARTUP', 'SCHEDULED'));
