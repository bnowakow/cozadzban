CREATE TABLE facebook_source_post_reaction (
    article_id BIGINT PRIMARY KEY REFERENCES article(id) ON DELETE CASCADE,
    source_url TEXT NOT NULL,
    graph_post_id TEXT,
    reaction_count BIGINT CHECK (reaction_count >= 0),
    status VARCHAR(16) NOT NULL CHECK (status IN ('SUCCESS', 'FAILED', 'UNSUPPORTED')),
    error_detail TEXT,
    measured_at TIMESTAMPTZ,
    last_attempt_at TIMESTAMPTZ NOT NULL,
    next_attempt_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX facebook_source_post_reaction_due_idx
    ON facebook_source_post_reaction(next_attempt_at);
