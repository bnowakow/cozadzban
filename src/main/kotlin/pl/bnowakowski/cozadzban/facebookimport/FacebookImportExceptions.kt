// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 https://bnowakowski.pl

package pl.bnowakowski.cozadzban.facebookimport

class FacebookImportAlreadyRunningException(
    importType: FacebookImportType? = null,
) : IllegalStateException(
    if (importType == null) {
        "Facebook import is already running"
    } else {
        "Facebook import is already running (type: ${importType.name})"
    },
)

class FacebookImportNotRunningException :
    IllegalStateException("No Facebook import job is currently running")
