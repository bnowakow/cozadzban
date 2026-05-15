-- SPDX-License-Identifier: GPL-3.0-or-later
-- Copyright (C) 2026 https://bnowakowski.pl

-- Remove duplicates created by Facebook/ad tracking parameters on external article URLs.
-- Keep the oldest article for each canonical URL, then persist that canonical URL so the
-- existing article_url_key constraint prevents future duplicates.

CREATE OR REPLACE FUNCTION pg_temp.canonical_article_url(raw_url TEXT)
RETURNS TEXT
LANGUAGE plpgsql
AS $$
DECLARE
    query_start INTEGER;
    base_url TEXT;
    query_string TEXT;
    raw_param TEXT;
    param_name TEXT;
    kept_params TEXT[] := ARRAY[]::TEXT[];
    lower_base TEXT;
BEGIN
    query_start := strpos(raw_url, '?');
    IF query_start = 0 THEN
        RETURN raw_url;
    END IF;

    base_url := substring(raw_url FROM 1 FOR query_start - 1);
    query_string := substring(raw_url FROM query_start + 1);
    lower_base := lower(base_url);

    IF lower_base ~ '^https?://([^/]+\.)?instagram\.com([/:]|$)' THEN
        RETURN base_url;
    END IF;

    FOREACH raw_param IN ARRAY regexp_split_to_array(query_string, '&')
    LOOP
        param_name := lower(split_part(raw_param, '=', 1));

        IF lower_base ~ '^https?://([^/]+\.)?facebook\.com([/:]|$)' THEN
            IF param_name IN ('fbid', 'set', 'story_fbid', 'id') THEN
                kept_params := array_append(kept_params, raw_param);
            END IF;
        ELSIF NOT (
            param_name = 'fbclid'
            OR param_name LIKE 'utm\_%' ESCAPE '\'
            OR param_name IN ('gclid', 'dclid', 'msclkid')
        ) THEN
            kept_params := array_append(kept_params, raw_param);
        END IF;
    END LOOP;

    IF array_length(kept_params, 1) IS NULL THEN
        RETURN base_url;
    END IF;

    RETURN base_url || '?' || array_to_string(kept_params, '&');
END;
$$;

WITH ranked AS (
    SELECT
        id,
        pg_temp.canonical_article_url(url) AS canonical_url,
        row_number() OVER (
            PARTITION BY pg_temp.canonical_article_url(url)
            ORDER BY created_at ASC, id ASC
        ) AS duplicate_rank
    FROM article
)
DELETE FROM article a
USING ranked r
WHERE a.id = r.id
  AND r.duplicate_rank > 1;

UPDATE article
SET url = pg_temp.canonical_article_url(url)
WHERE url <> pg_temp.canonical_article_url(url);
