// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 https://bnowakowski.pl

package pl.bnowakowski.cozazjeb.facebookimport

class FacebookImportAlreadyRunningException :
    IllegalStateException("Facebook import is already running")

class FacebookImportNotRunningException :
    IllegalStateException("No Facebook import job is currently running")
