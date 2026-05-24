CREATE TABLE notification_preference (
    app_user_id BIGINT PRIMARY KEY REFERENCES app_user(id) ON DELETE CASCADE,
    provider VARCHAR(32) NOT NULL DEFAULT 'PUSHOVER',
    pushover_user_key_encrypted TEXT NOT NULL,
    pushover_user_key_suffix VARCHAR(12) NOT NULL,
    pushover_device VARCHAR(128),
    facebook_login_required_enabled BOOLEAN NOT NULL DEFAULT false,
    facebook_proposals_submitted_enabled BOOLEAN NOT NULL DEFAULT false,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT notification_preference_provider_check
        CHECK (provider IN ('PUSHOVER'))
);

CREATE INDEX notification_preference_login_required_idx
    ON notification_preference(facebook_login_required_enabled)
    WHERE facebook_login_required_enabled = true;

CREATE INDEX notification_preference_proposals_submitted_idx
    ON notification_preference(facebook_proposals_submitted_enabled)
    WHERE facebook_proposals_submitted_enabled = true;

ALTER TABLE facebook_import_run
    ADD COLUMN login_required_first_at TIMESTAMPTZ,
    ADD COLUMN login_required_last_at TIMESTAMPTZ,
    ADD COLUMN login_required_count INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN login_required_trigger VARCHAR(32),
    ADD COLUMN login_required_profile_url TEXT,
    ADD CONSTRAINT facebook_import_run_login_required_trigger_check
        CHECK (login_required_trigger IS NULL OR login_required_trigger IN ('MANUAL', 'SCHEDULED'));
