ALTER TABLE notification_preference
    ADD COLUMN facebook_proposals_auto_approved_enabled BOOLEAN NOT NULL DEFAULT false;

CREATE INDEX notification_preference_proposals_auto_approved_idx
    ON notification_preference(facebook_proposals_auto_approved_enabled)
    WHERE facebook_proposals_auto_approved_enabled = true;
