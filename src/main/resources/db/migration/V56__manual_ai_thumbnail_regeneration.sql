-- Keep the old generated image available while an administrator requests a replacement.
ALTER TABLE facebook_article_image_generation
    ADD COLUMN prompt_note VARCHAR(500),
    ADD COLUMN previous_generated BOOLEAN NOT NULL DEFAULT FALSE;
