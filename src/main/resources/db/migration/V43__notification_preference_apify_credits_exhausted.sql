ALTER TABLE notification_preference
    ADD COLUMN apify_credits_exhausted_enabled BOOLEAN NOT NULL DEFAULT false;

CREATE INDEX notification_preference_apify_credits_exhausted_idx
    ON notification_preference(apify_credits_exhausted_enabled)
    WHERE apify_credits_exhausted_enabled = true;
