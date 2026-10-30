-- SPDX-License-Identifier: GPL-3.0-or-later

-- A Facebook post can point to a verified publisher article already represented
-- by another imported post. Keep the unique source URL and store that link separately.
ALTER TABLE article ADD COLUMN publisher_url TEXT;
