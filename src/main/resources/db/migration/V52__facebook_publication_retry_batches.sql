CREATE TABLE facebook_publication_retry_batch (
    id BIGSERIAL PRIMARY KEY,
    total INTEGER NOT NULL CHECK (total > 0),
    next_claim_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

ALTER TABLE facebook_page_publication
    ADD COLUMN retry_batch_id BIGINT REFERENCES facebook_publication_retry_batch(id);

CREATE INDEX facebook_page_publication_retry_batch_idx
    ON facebook_page_publication(retry_batch_id, status)
    WHERE retry_batch_id IS NOT NULL;
