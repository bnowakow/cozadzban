ALTER TABLE facebook_import_run
    ADD COLUMN apify_actor_run_id TEXT,
    ADD COLUMN apify_cost_usd NUMERIC(18, 9),
    ADD CONSTRAINT facebook_import_run_apify_cost_nonnegative_check
        CHECK (apify_cost_usd IS NULL OR apify_cost_usd >= 0);

CREATE UNIQUE INDEX facebook_import_run_apify_actor_run_id_unique
    ON facebook_import_run (apify_actor_run_id)
    WHERE apify_actor_run_id IS NOT NULL;
