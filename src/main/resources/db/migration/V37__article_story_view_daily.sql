CREATE TABLE article_story_view_daily (
    article_id BIGINT NOT NULL REFERENCES article(id) ON DELETE CASCADE,
    view_date_utc DATE NOT NULL,
    view_count BIGINT NOT NULL DEFAULT 0 CHECK (view_count >= 0),
    PRIMARY KEY (article_id, view_date_utc)
);

CREATE INDEX article_story_view_daily_date_idx ON article_story_view_daily(view_date_utc);
