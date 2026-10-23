ALTER TABLE facebook_page_publication ADD COLUMN photo_id TEXT;

CREATE TABLE facebook_page_sync (
    id BIGSERIAL PRIMARY KEY,
    publication_id BIGINT NOT NULL REFERENCES facebook_page_publication(id) ON DELETE CASCADE,
    action VARCHAR(16) NOT NULL CHECK (action IN ('UPDATE', 'DELETE')),
    status VARCHAR(32) NOT NULL CHECK (status IN ('PENDING', 'ATTEMPTING', 'CONFIRMED', 'FAILED', 'NEEDS_RECONCILIATION', 'REPLACEMENT_REQUIRED', 'CANCELLED')),
    attempt_count INTEGER NOT NULL DEFAULT 0,
    dirty BOOLEAN NOT NULL DEFAULT false,
    replacement_required BOOLEAN NOT NULL DEFAULT false,
    requested_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    attempted_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    last_error TEXT,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (publication_id, action)
);
CREATE INDEX facebook_page_sync_pending_idx ON facebook_page_sync(status, requested_at);
