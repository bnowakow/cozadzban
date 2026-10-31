-- SPDX-License-Identifier: GPL-3.0-or-later
-- Over-long titles (usually whole Facebook posts) are rewritten by Codex, or Claude as a fallback.
-- The original title is kept here so the change can be audited or reverted, and so a re-import that
-- restores the same long title gets the stored short one back without another model call.
CREATE TABLE article_title_shortening (
    article_id      BIGINT       PRIMARY KEY REFERENCES article (id) ON DELETE CASCADE,
    original_title  TEXT         NOT NULL,
    shortened_title TEXT,
    status          VARCHAR(20)  NOT NULL CHECK (status IN ('PENDING', 'SHORTENED', 'FAILED')),
    attempt_count   INT          NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    last_error      TEXT,
    tool            VARCHAR(20),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);
