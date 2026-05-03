-- SPDX-License-Identifier: GPL-3.0-or-later
-- Copyright (C) 2026 https://bnowakowski.pl

CREATE TABLE article (
    id         BIGSERIAL    PRIMARY KEY,
    url        TEXT         NOT NULL UNIQUE,
    language   VARCHAR(20)  NOT NULL,
    title      TEXT,
    thumbnail  TEXT,
    lead       TEXT,
    quote      TEXT,
    ai_summary TEXT,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX article_language_idx   ON article (language);
CREATE INDEX article_created_at_idx ON article (created_at DESC);
