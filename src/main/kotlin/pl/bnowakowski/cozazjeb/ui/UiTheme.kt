// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 https://bnowakowski.pl

package pl.bnowakowski.cozazjeb.ui

import com.vaadin.flow.component.Component

fun Component.installCozazjebThemeBootstrap() {
    addAttachListener { event ->
        event.ui.page.executeJs(
            """
                (function() {
                    function applyTheme(mode) {
                        const dark = mode === 'dark';
                        const root = document.documentElement;
                        const body = document.body;
                        if (dark) {
                            root.setAttribute('theme', 'dark');
                            body.setAttribute('theme', 'dark');
                        } else {
                            root.removeAttribute('theme');
                            body.removeAttribute('theme');
                        }
                    }
                    let stored = null;
                    try {
                        stored = localStorage.getItem('cozazjeb-theme');
                    } catch (e) {
                        // Ignore storage failures and fall back to the system preference.
                    }
                    const dark = stored ? stored === 'dark' : window.matchMedia('(prefers-color-scheme: dark)').matches;
                    applyTheme(dark ? 'dark' : 'light');
                })();
            """.trimIndent(),
        )
    }
}
