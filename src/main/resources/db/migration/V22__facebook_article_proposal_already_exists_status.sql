ALTER TABLE facebook_article_proposal
    DROP CONSTRAINT facebook_article_proposal_status_check;

ALTER TABLE facebook_article_proposal
    ADD CONSTRAINT facebook_article_proposal_status_check
        CHECK (status IS NULL OR status IN ('ACCEPTED', 'REJECTED', 'FAILED', 'ALREADY_EXISTS'));
