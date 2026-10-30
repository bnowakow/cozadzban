// SPDX-License-Identifier: GPL-3.0-or-later
package pl.bnowakowski.cozadzban.ui

/** A standalone error page works even when the interactive application cannot load. */
object PublicNotFoundPage {
    fun content(): String = """
        <main style="box-sizing:border-box;max-width:42rem;margin:10vh auto;padding:2rem;font:1rem/1.6 system-ui,sans-serif">
          <a href="/">Co za dzban</a>
          <h1>Page not found</h1>
          <p>The page may have moved or the link may be incorrect.</p>
          <h2>Continue exploring</h2>
          <nav aria-label="Useful links">
            <ul>
              <li><a href="/">Latest news and stories</a></li>
              <li><a href="/?lang=pl">Wiadomości po polsku</a></li>
              <li><a href="/sitemap.xml">Sitemap</a></li>
              <li><a href="/rss">RSS feed</a></li>
            </ul>
          </nav>
        </main>
    """.trimIndent()

    fun html(): String = """
        <!doctype html>
        <html lang="en"><head>
          <meta charset="UTF-8">
          <meta name="viewport" content="width=device-width, initial-scale=1">
          <meta name="robots" content="noindex">
          <title>Page not found | Co za dzban</title>
          <style>:root{color-scheme:light dark}body{margin:0}a{color:light-dark(#a83d13,#ff9a64)}</style>
        </head><body>${content()}</body></html>
    """.trimIndent()
}
