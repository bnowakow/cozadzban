ALTER TABLE facebook_import_run
    ADD COLUMN import_type VARCHAR(32) NOT NULL DEFAULT 'SELENIUM',
    ADD CONSTRAINT facebook_import_run_import_type_check
        CHECK (import_type IN ('API', 'SELENIUM'));

ALTER TABLE facebook_article_proposal
    ADD COLUMN import_type VARCHAR(32) NOT NULL DEFAULT 'SELENIUM',
    ADD CONSTRAINT facebook_article_proposal_import_type_check
        CHECK (import_type IN ('API', 'SELENIUM'));

ALTER TABLE article
    ADD COLUMN source_import_type VARCHAR(32),
    ADD COLUMN source_import_run_id TEXT REFERENCES facebook_import_run(import_run_id) ON DELETE SET NULL,
    ADD COLUMN source_facebook_proposal_id BIGINT REFERENCES facebook_article_proposal(id) ON DELETE SET NULL,
    ADD CONSTRAINT article_source_import_type_check
        CHECK (source_import_type IS NULL OR source_import_type IN ('API', 'SELENIUM'));

CREATE INDEX article_source_import_run_id_idx
    ON article(source_import_run_id);

CREATE INDEX article_source_facebook_proposal_id_idx
    ON article(source_facebook_proposal_id);

CREATE INDEX facebook_article_proposal_import_type_idx
    ON facebook_article_proposal(import_type);
