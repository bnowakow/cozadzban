-- SPDX-License-Identifier: GPL-3.0-or-later
-- Copyright (C) 2026 https://bnowakowski.pl

CREATE TABLE app_user (
    id         BIGSERIAL    PRIMARY KEY,
    email      VARCHAR(254) NOT NULL UNIQUE,
    role       VARCHAR(16)  NOT NULL,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX app_user_email_idx ON app_user (email);
