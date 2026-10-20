CREATE TABLE facebook_page_connection (
    language VARCHAR(20) PRIMARY KEY,
    page_id TEXT NOT NULL UNIQUE,
    page_name TEXT NOT NULL,
    access_token_encrypted TEXT NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT false,
    time_zone TEXT NOT NULL DEFAULT 'Europe/Warsaw',
    monthly_link_limit INTEGER NOT NULL DEFAULT 5 CHECK (monthly_link_limit >= 0),
    minimum_post_age_hours INTEGER NOT NULL DEFAULT 24 CHECK (minimum_post_age_hours >= 0),
    scheduler_interval_minutes INTEGER NOT NULL DEFAULT 60 CHECK (scheduler_interval_minutes > 0),
    connected_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE facebook_page_publication (
    id BIGSERIAL PRIMARY KEY,
    article_id BIGINT UNIQUE REFERENCES article(id) ON DELETE SET NULL,
    article_id_snapshot BIGINT NOT NULL UNIQUE,
    language VARCHAR(20) NOT NULL,
    page_id TEXT,
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING', 'UNCONFIGURED', 'PUBLISHING', 'PUBLISHED', 'UPDATE_PENDING', 'DELETE_PENDING', 'DELETED', 'FAILED', 'NEEDS_RECONCILIATION')),
    post_id TEXT UNIQUE,
    post_permalink TEXT,
    image_source VARCHAR(32) CHECK (image_source IN ('THUMBNAIL', 'PLACEHOLDER')),
    attempt_count INTEGER NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
    last_error TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX facebook_page_publication_status_idx ON facebook_page_publication(status, created_at);
CREATE INDEX facebook_page_publication_page_idx ON facebook_page_publication(page_id, published_at);

CREATE TABLE facebook_post_reaction_snapshot (
    id BIGSERIAL PRIMARY KEY,
    publication_id BIGINT NOT NULL REFERENCES facebook_page_publication(id) ON DELETE CASCADE,
    reaction_count BIGINT CHECK (reaction_count >= 0),
    status VARCHAR(16) NOT NULL CHECK (status IN ('SUCCESS', 'FAILED')),
    error_detail TEXT,
    measured_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX facebook_post_reaction_snapshot_latest_idx
    ON facebook_post_reaction_snapshot(publication_id, measured_at DESC);

CREATE TABLE facebook_link_comment (
    id BIGSERIAL PRIMARY KEY,
    publication_id BIGINT NOT NULL UNIQUE REFERENCES facebook_page_publication(id) ON DELETE CASCADE,
    page_id TEXT NOT NULL,
    post_id TEXT NOT NULL,
    story_url TEXT NOT NULL,
    status VARCHAR(32) NOT NULL CHECK (status IN ('SELECTED', 'ATTEMPTING', 'CONFIRMED', 'FAILED', 'NEEDS_RECONCILIATION')),
    comment_id TEXT UNIQUE,
    attempted_at TIMESTAMPTZ,
    confirmed_at TIMESTAMPTZ,
    last_error TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX facebook_link_comment_page_month_idx ON facebook_link_comment(page_id, confirmed_at);
