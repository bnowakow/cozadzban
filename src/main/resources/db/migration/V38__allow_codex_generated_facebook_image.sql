-- SPDX-License-Identifier: GPL-3.0-or-later
ALTER TABLE facebook_page_publication
    DROP CONSTRAINT facebook_page_publication_image_source_check;

ALTER TABLE facebook_page_publication
    ADD CONSTRAINT facebook_page_publication_image_source_check
    CHECK (image_source IN ('THUMBNAIL', 'PLACEHOLDER', 'CODEX_GENERATED'));
