ALTER TABLE facebook_article_proposal
    ADD COLUMN accepted_by TEXT,
    ADD COLUMN accepted_at TIMESTAMPTZ,
    ADD COLUMN accepted_reason TEXT;
