ALTER TABLE facebook_import_run
    ADD COLUMN current_pass_index INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN pass_count INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN phase TEXT,
    ADD COLUMN phase_index INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN phase_count INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN last_status_at TIMESTAMPTZ NOT NULL DEFAULT now();

UPDATE facebook_import_run
   SET phase = CASE
           WHEN status = 'RUNNING' THEN 'Running'
           ELSE initcap(lower(status))
       END,
       phase_index = CASE WHEN status = 'RUNNING' THEN 0 ELSE 8 END,
       phase_count = CASE WHEN status = 'RUNNING' THEN 0 ELSE 8 END,
       last_status_at = COALESCE(finished_at, login_required_last_at, started_at, now());

ALTER TABLE facebook_import_run
    ADD CONSTRAINT facebook_import_run_current_pass_index_check
        CHECK (current_pass_index >= 0),
    ADD CONSTRAINT facebook_import_run_pass_count_check
        CHECK (pass_count >= 0),
    ADD CONSTRAINT facebook_import_run_phase_index_check
        CHECK (phase_index >= 0),
    ADD CONSTRAINT facebook_import_run_phase_count_check
        CHECK (phase_count >= 0);
