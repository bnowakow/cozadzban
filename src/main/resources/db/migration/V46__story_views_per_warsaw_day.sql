-- Story views are counted per Europe/Warsaw calendar day from now on, matching the UI.
-- Existing rows were counted per UTC day; per-hour data was never stored, so they can't be
-- re-bucketed and keep their dates (at most 1-2 hours off at each day boundary).
ALTER TABLE article_story_view_daily RENAME COLUMN view_date_utc TO view_date;
ALTER INDEX article_story_view_daily_date_idx RENAME TO article_story_view_daily_view_date_idx;
COMMENT ON COLUMN article_story_view_daily.view_date IS
    'Europe/Warsaw calendar day of the views (rows before V46 used UTC days)';
