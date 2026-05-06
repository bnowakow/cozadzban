-- SPDX-License-Identifier: GPL-3.0-or-later
-- Copyright (C) 2026 https://bnowakowski.pl

-- Phase 16: article ownership + soft-deleted users

-- ─── app_user.status ─────────────────────────────────────────────────────────

ALTER TABLE app_user
    ADD COLUMN status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE';

CREATE INDEX app_user_status_idx ON app_user (status);

-- ─── article.created_by_user_id ──────────────────────────────────────────────

-- Add nullable first so the backfill DO block can run before enforcing NOT NULL.
ALTER TABLE article
    ADD COLUMN created_by_user_id BIGINT REFERENCES app_user(id);

-- Backfill: assign every existing article to the oldest user.
-- Fail fast if articles exist but no user exists.
DO $$
DECLARE
    oldest_user_id BIGINT;
    article_count  BIGINT;
BEGIN
    SELECT COUNT(*) INTO article_count FROM article;

    IF article_count > 0 THEN
        SELECT id INTO oldest_user_id
          FROM app_user
         ORDER BY created_at ASC, id ASC
         LIMIT 1;

        IF oldest_user_id IS NULL THEN
            RAISE EXCEPTION
                'Migration failed: articles exist but no app_user rows found. '
                'Ensure at least one user exists before running this migration.';
        END IF;

        UPDATE article SET created_by_user_id = oldest_user_id;
    END IF;
END $$;

-- Enforce NOT NULL after backfill.
ALTER TABLE article
    ALTER COLUMN created_by_user_id SET NOT NULL;

CREATE INDEX article_created_by_user_idx ON article (created_by_user_id);
