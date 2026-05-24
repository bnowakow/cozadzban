CREATE TABLE facebook_import_run (
    import_run_id TEXT PRIMARY KEY,
    status VARCHAR(32) NOT NULL DEFAULT 'RUNNING',
    started_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    finished_at TIMESTAMPTZ,
    discovered_count INTEGER NOT NULL DEFAULT 0,
    submitted_count INTEGER NOT NULL DEFAULT 0,
    skipped_existing_count INTEGER NOT NULL DEFAULT 0,
    failed_count INTEGER NOT NULL DEFAULT 0,
    summary_logs_compressed BYTEA,
    CONSTRAINT facebook_import_run_status_check
        CHECK (status IN ('RUNNING', 'FINISHED', 'FAILED', 'TERMINATED'))
);

CREATE TABLE facebook_article_proposal (
    id BIGSERIAL PRIMARY KEY,
    candidate_id TEXT NOT NULL,
    import_run_id TEXT NOT NULL REFERENCES facebook_import_run(import_run_id) ON DELETE CASCADE,
    article_url TEXT NOT NULL,
    canonical_article_url TEXT NOT NULL UNIQUE,
    facebook_post_url TEXT,
    guessed_language VARCHAR(20) NOT NULL,
    corrected_language VARCHAR(20),
    status VARCHAR(32),
    article_id BIGINT REFERENCES article(id) ON DELETE SET NULL,
    decided_by_user_id BIGINT REFERENCES app_user(id) ON DELETE SET NULL,
    decided_at TIMESTAMPTZ,
    submitted_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_seen_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    logs_compressed BYTEA,
    CONSTRAINT facebook_article_proposal_status_check
        CHECK (status IS NULL OR status IN ('ACCEPTED', 'REJECTED', 'FAILED'))
);

CREATE INDEX facebook_article_proposal_status_submitted_at_idx
    ON facebook_article_proposal(status, submitted_at DESC);

CREATE INDEX facebook_article_proposal_submitted_at_idx
    ON facebook_article_proposal(submitted_at DESC);

CREATE INDEX facebook_article_proposal_import_run_id_idx
    ON facebook_article_proposal(import_run_id);
