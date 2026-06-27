ALTER TABLE facebook_import_run
    DROP CONSTRAINT IF EXISTS facebook_import_run_import_type_check,
    ADD CONSTRAINT facebook_import_run_import_type_check
        CHECK (import_type IN ('API', 'APIFY', 'SELENIUM'));

ALTER TABLE facebook_article_proposal
    DROP CONSTRAINT IF EXISTS facebook_article_proposal_import_type_check,
    ADD CONSTRAINT facebook_article_proposal_import_type_check
        CHECK (import_type IN ('API', 'APIFY', 'SELENIUM'));

ALTER TABLE article
    DROP CONSTRAINT IF EXISTS article_source_import_type_check,
    ADD CONSTRAINT article_source_import_type_check
        CHECK (source_import_type IS NULL OR source_import_type IN ('API', 'APIFY', 'SELENIUM'));
