ALTER TABLE notification_preference
    ADD COLUMN apify_credits_low_enabled BOOLEAN NOT NULL DEFAULT false;

CREATE INDEX notification_preference_apify_credits_low_idx
    ON notification_preference(apify_credits_low_enabled)
    WHERE apify_credits_low_enabled = true;

-- One row per forecast alert actually claimed in an Apify billing cycle. The primary key
-- makes each alert (3 days before, 1 day before) fire at most once per cycle, even when
-- the blue and green application containers both run the forecast check.
CREATE TABLE apify_credit_alert (
    cycle_start_at          TIMESTAMPTZ   NOT NULL,
    days_before             INTEGER       NOT NULL CHECK (days_before > 0),
    predicted_exhaustion_at TIMESTAMPTZ   NOT NULL,
    remaining_usd           NUMERIC(12,4) NOT NULL CHECK (remaining_usd >= 0),
    daily_usage_usd         NUMERIC(12,4) NOT NULL CHECK (daily_usage_usd > 0),
    claimed_at              TIMESTAMPTZ   NOT NULL DEFAULT now(),
    PRIMARY KEY (cycle_start_at, days_before)
);
