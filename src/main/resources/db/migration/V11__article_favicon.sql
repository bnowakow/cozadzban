-- Store the article source favicon discovered during URL enrichment.

ALTER TABLE article
    ADD COLUMN favicon TEXT;
