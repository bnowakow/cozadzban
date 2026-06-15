ALTER TABLE facebook_article_proposal
    ADD COLUMN browser_enriched_title TEXT,
    ADD COLUMN browser_enriched_thumbnail TEXT,
    ADD COLUMN browser_enriched_lead TEXT,
    ADD COLUMN browser_enriched_favicon TEXT,
    ADD COLUMN browser_enriched_published_at TIMESTAMP WITH TIME ZONE,
    ADD COLUMN browser_enriched_plain_text TEXT;
