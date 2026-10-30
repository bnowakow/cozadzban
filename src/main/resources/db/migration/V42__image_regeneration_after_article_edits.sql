-- SPDX-License-Identifier: GPL-3.0-or-later

-- Preserve an edit that arrives while the image worker is already generating.
ALTER TABLE facebook_article_image_generation
    ADD COLUMN regenerate_requested BOOLEAN NOT NULL DEFAULT false;
