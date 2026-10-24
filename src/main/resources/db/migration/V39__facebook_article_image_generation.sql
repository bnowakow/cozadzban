-- SPDX-License-Identifier: GPL-3.0-or-later
CREATE TABLE facebook_article_image_generation (
    article_id BIGINT PRIMARY KEY REFERENCES article(id) ON DELETE CASCADE,
    status VARCHAR(24) NOT NULL
        CHECK (status IN ('MISSING_THUMBNAIL', 'GENERATING', 'GENERATED', 'FAILED')),
    attempt_count INTEGER NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_error TEXT,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX facebook_article_image_generation_due_idx
    ON facebook_article_image_generation(status, next_attempt_at, article_id);

-- One global slot, shared by blue and green app instances.
CREATE TABLE facebook_article_image_generation_pace (
    id INTEGER PRIMARY KEY CHECK (id = 1),
    next_claim_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
INSERT INTO facebook_article_image_generation_pace(id) VALUES (1);
