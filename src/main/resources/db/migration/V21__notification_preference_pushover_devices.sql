ALTER TABLE notification_preference
    ADD COLUMN pushover_devices TEXT;

UPDATE notification_preference
   SET pushover_devices = pushover_device
 WHERE pushover_device IS NOT NULL
   AND btrim(pushover_device) <> '';

ALTER TABLE notification_preference
    DROP COLUMN pushover_device;
