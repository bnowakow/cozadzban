<?xml version="1.0" encoding="UTF-8"?>
<xsl:stylesheet version="1.0"
    xmlns:xsl="http://www.w3.org/1999/XSL/Transform"
    xmlns:s="http://www.sitemaps.org/schemas/sitemap/0.9">
  <xsl:output method="html" encoding="UTF-8" indent="yes"/>
  <xsl:template match="/">
    <html lang="en" class="czj-system-theme">
      <head>
        <meta charset="UTF-8"/>
        <meta name="viewport" content="width=device-width, initial-scale=1"/>
        <title>Sitemap | Co za dzban</title>
        <link rel="icon" href="/favicon.png"/>
        <link rel="stylesheet" href="/branding/brand.css"/>
        <style>
          body { max-width: 64rem; margin: 0 auto; padding: 2rem 1rem; }
          h1 { margin-bottom: .25rem; }
          p { color: var(--czj-muted); }
          ul { list-style: none; padding: 0; border-top: 1px solid var(--czj-border); }
          li { padding: .7rem 0; border-bottom: 1px solid var(--czj-border); overflow-wrap: anywhere; }
        </style>
      </head>
      <body>
        <h1>Co za dzban sitemap</h1>
        <p>
          <xsl:value-of select="count(s:urlset/s:url)"/>
          public URLs. This view is for people; search engines read the XML sitemap.
        </p>
        <ul>
          <xsl:for-each select="s:urlset/s:url">
            <li><a href="{s:loc}"><xsl:value-of select="s:loc"/></a></li>
          </xsl:for-each>
        </ul>
      </body>
    </html>
  </xsl:template>
</xsl:stylesheet>
