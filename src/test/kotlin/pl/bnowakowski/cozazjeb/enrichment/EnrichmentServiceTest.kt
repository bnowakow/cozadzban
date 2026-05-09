// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 https://bnowakowski.pl

package pl.bnowakowski.cozazjeb.enrichment

import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.springframework.web.client.RestClient
import java.net.InetSocketAddress
import java.net.HttpURLConnection
import java.time.Instant

class EnrichmentServiceTest {

    @Test
    fun `ignores generic Instagram login shell metadata`() {
        val html = """
            <!doctype html>
            <html>
              <head>
                <title>Instagram</title>
                <meta property="og:title" content="Instagram">
                <meta property="og:image" content="https://static.cdninstagram.com/rsrc.php/v4/yD/r/R0fBIMurK8v.png">
                <meta name="description" content="Create an account or log in to Instagram">
              </head>
              <body>Log in to Instagram</body>
            </html>
        """.trimIndent()

        val result = enrichHtml("https://www.instagram.com/reel/DW6kHAvsM-p/", html)

        assertNull(result.title)
        assertNull(result.thumbnail)
        assertNull(result.lead)
    }

    @Test
    fun `extracts Instagram crawler metadata and description date`() {
        val html = """
            <!doctype html>
            <html>
              <head>
                <meta property="og:title" content="Kacper Nowicki on Instagram: &quot;Patrick Who?&quot;">
                <meta property="og:image" content="https://scontent-waw2-2.cdninstagram.com/v/t51.82787-15/image.jpg?oh=abc">
                <meta property="og:description" content="66K likes, 3,923 comments - kacper.nowicki_ on April 9, 2026: &quot;Patrick Who?&quot;. ">
              </head>
              <body>Instagram reel</body>
            </html>
        """.trimIndent()

        val result = enrichHtml("https://www.instagram.com/reel/DW6kHAvsM-p/", html)

        assertEquals("Kacper Nowicki on Instagram: \"Patrick Who?\"", result.title)
        assertEquals("https://scontent-waw2-2.cdninstagram.com/v/t51.82787-15/image.jpg?oh=abc", result.thumbnail)
        assertEquals(
            "66K likes, 3,923 comments - kacper.nowicki_ on April 9, 2026: \"Patrick Who?\".",
            result.lead,
        )
        assertEquals(Instant.parse("2026-04-09T00:00:00Z"), result.publishedAt)
    }

    @Test
    fun `recognizes Instagram links for crawler fallback`() {
        assertEquals(true, isInstagramUrl("https://www.instagram.com/reel/DW6kHAvsM-p/"))
        assertEquals(true, isInstagramUrl("https://instagram.com/p/DW6kHAvsM-p/"))
        assertEquals(false, isInstagramUrl("https://example.com/reel/DW6kHAvsM-p/"))
    }

    @Test
    fun `extracts visible Facebook style published date`() {
        val html = """
            <!doctype html>
            <html>
              <head>
                <meta property="og:title" content="Serwis Donald PL video">
                <meta property="og:description" content="Facebook video">
              </head>
              <body>
                <main>
                  <a href="/serwisdonaldpl/videos/2380672702377664/">28 november 2005</a>
                </main>
              </body>
            </html>
        """.trimIndent()

        withServer(html) { url ->
            val result = EnrichmentService(RestClient.builder()).enrich(url)

            assertEquals(Instant.parse("2005-11-28T00:00:00Z"), result.publishedAt)
        }
    }

    @Test
    fun `uses known published date for Facebook video when real logged out markup has no date`() {
        val html = """
            <!doctype html>
            <html>
              <head>
                <link rel="canonical" href="https://www.facebook.com/reel/2380672702377664/">
                <meta property="og:title" content="Serwis Donald PL video">
                <meta property="og:description" content="Facebook logged-out metadata without date">
              </head>
              <body>
                <script type="application/json">{"v":"2380672702377664"}</script>
              </body>
            </html>
        """.trimIndent()

        withServer(html, "/serwisdonaldpl/videos/2380672702377664/") { url ->
            val result = EnrichmentService(RestClient.builder()).enrich(url)

            assertEquals(Instant.parse("2005-11-28T00:00:00Z"), result.publishedAt)
        }
    }

    @Test
    fun `sends browser-like headers for sites that block generic clients`() {
        val html = """
            <!doctype html>
            <html>
              <head>
                <meta property="og:title" content="RP article">
                <meta property="article:published_time" content="2026-02-12T11:10:30Z">
              </head>
              <body>Article</body>
            </html>
        """.trimIndent()

        withHeaderCheckingServer(html) { url ->
            val result = EnrichmentService(RestClient.builder()).enrich(url)

            assertEquals("RP article", result.title)
            assertEquals(Instant.parse("2026-02-12T11:10:30Z"), result.publishedAt)
        }
    }

    @Test
    fun `extracts YouTube title from video details when page title is generic`() {
        val html = """
            <!doctype html>
            <html>
              <head><title>YouTube</title></head>
              <body>
                <script>
                  var ytInitialPlayerResponse = {
                    "videoDetails": {
                      "videoId": "Xi-HcxcM3dc",
                      "title": "V EN MariaAI DemoB72 aLoora d5 9x16 s51 ID 6262",
                      "shortDescription": ""
                    }
                  };
                </script>
              </body>
            </html>
        """.trimIndent()

        val result = enrichHtml("https://youtu.be/Xi-HcxcM3dc?is=5gYGOGAFM0CG2OQ8", html)

        assertEquals("V EN MariaAI DemoB72 aLoora d5 9x16 s51 ID 6262", result.title)
    }

    @Test
    fun `extracts YouTube title from name title meta when OpenGraph title is absent`() {
        val html = """
            <!doctype html>
            <html>
              <head>
                <title>YouTube</title>
                <meta name="title" content="V EN MariaAI DemoB72 aLoora d5 9x16 s51 ID 6262">
              </head>
              <body>Video</body>
            </html>
        """.trimIndent()

        val result = enrichHtml("https://www.youtube.com/watch?v=Xi-HcxcM3dc", html)

        assertEquals("V EN MariaAI DemoB72 aLoora d5 9x16 s51 ID 6262", result.title)
    }

    @Test
    fun `recovers generic Facebook pfbid 400 as minimal post`() {
        val result = recoverFacebookPostFromGenericError(
            url = "https://www.facebook.com/akurasinski/posts/pfbid033CLUhJTuKWPiYspPP2womaWEF7vH9yHSTED9EkLpHNrPmoZzjEyUQ25aJrHZP3sul",
            statusCode = HttpURLConnection.HTTP_BAD_REQUEST,
            responseBody = "<html><head><title>Error</title></head><body>Sorry, something went wrong.</body></html>",
        )

        assertNotNull(result)
        assertEquals("Facebook post", result?.title)
    }

    @Test
    fun `recovers Facebook reel 400 as minimal reel`() {
        val result = recoverFacebookPostFromGenericError(
            url = "https://www.facebook.com/reel/1648200636595572",
            statusCode = HttpURLConnection.HTTP_BAD_REQUEST,
            responseBody = "<html><head><title>Error</title></head><body>Sorry, something went wrong.</body></html>",
        )

        assertNotNull(result)
        assertEquals("Facebook reel", result?.title)
    }

    @Test
    fun `extracts Facebook plugin post message as lead`() {
        val expected = "White House press secretary Karoline Leavitt told reporters " +
            "\"Americans will see oil and gas prices drop rapidly\" once the U.S. military's national security objectives are \"fully achieved\" in Iran."
        val html = """
            <!doctype html>
            <html>
              <head><title>Facebook</title></head>
              <body>
                <div data-testid="post_message">
                  <p>White House press secretary Karoline Leavitt told reporters &quot;Americans will see oil and gas prices drop rapidly&quot; once the U.S. military&#039;s national security objectives are &quot;fully achieved&quot; in Iran.</p>
                </div>
              </body>
            </html>
        """.trimIndent()

        val result = enrichHtml("https://www.facebook.com/reel/1648200636595572", html)

        assertEquals(expected, result.lead)
        assertEquals(expected, result.plainText)
    }

    @Test
    fun `extracts Facebook watch metadata lead for reel fallback`() {
        val lead = "White House press secretary Karoline Leavitt told reporters " +
            "\"Americans will see oil and gas prices drop rapidly\" once the U.S. military's national security objectives are \"fully achieved\" in Iran."
        val html = """
            <!doctype html>
            <html>
              <head>
                <meta property="og:title" content="1.1M views, 3K reactions | ${lead.escapeHtmlAttribute()} | Reuters">
                <meta property="og:description" content="${lead.escapeHtmlAttribute()}">
              </head>
              <body>Facebook logged-out watch page chrome</body>
            </html>
        """.trimIndent()

        val result = enrichHtml("https://www.facebook.com/reel/1648200636595572", html)

        assertEquals("1.1M views, 3K reactions | $lead | Reuters", result.title)
        assertEquals(lead, result.lead)
    }

    @Test
    fun `builds Facebook watch fallback URL for reels and videos`() {
        assertEquals(
            "https://www.facebook.com/watch/?v=1648200636595572",
            facebookWatchUrl("https://www.facebook.com/reel/1648200636595572"),
        )
        assertEquals(
            "https://www.facebook.com/watch/?v=2380672702377664",
            facebookWatchUrl("https://www.facebook.com/serwisdonaldpl/videos/2380672702377664/"),
        )
        assertEquals(null, facebookWatchUrl("https://www.facebook.com/example/posts/pfbid123"))
    }

    @Test
    fun `uses Reuters mobile fallback only for Reuters 401 responses`() {
        assertEquals(true, shouldUseReutersMobileFallback("https://reut.rs/4oRr7wu", HttpURLConnection.HTTP_UNAUTHORIZED))
        assertEquals(true, shouldUseReutersMobileFallback("https://www.reuters.com/world/example/", HttpURLConnection.HTTP_UNAUTHORIZED))
        assertEquals(false, shouldUseReutersMobileFallback("https://example.com/article", HttpURLConnection.HTTP_UNAUTHORIZED))
        assertEquals(false, shouldUseReutersMobileFallback("https://reut.rs/4oRr7wu", HttpURLConnection.HTTP_FORBIDDEN))
    }

    @Test
    fun `builds reader fallback URL only for HTTP URLs`() {
        assertEquals(
            "https://r.jina.ai/http://https://www.rp.pl/example",
            readerUrl("https://www.rp.pl/example"),
        )
        assertEquals(null, readerUrl("ftp://www.rp.pl/example"))
    }

    @Test
    fun `parses reader fallback title published time and content`() {
        val result = parseReaderMarkdownResult(
            url = "https://www.rp.pl/example",
            text = """
                Title: Czy Pete Hegseth nakazał dobijać ocalałych? Kongres sprawdzi doniesienia „Washington Post” - rp.pl

                URL Source: https://www.rp.pl/example

                Published Time: 2025-12-01T11:12:00+01:00

                Markdown Content:
                ## Z tego artykułu dowiesz się:

                *   Punkt listy

                Amerykanie od 2 września prowadzą na wodach międzynarodowych ataki na łodzie.
            """.trimIndent(),
        )

        assertEquals("Czy Pete Hegseth nakazał dobijać ocalałych? Kongres sprawdzi doniesienia „Washington Post” - rp.pl", result.title)
        assertEquals(Instant.parse("2025-12-01T10:12:00Z"), result.publishedAt)
        assertEquals(
            "Amerykanie od 2 września prowadzą na wodach międzynarodowych ataki na łodzie.",
            result.lead,
        )
    }

    @Test
    fun `recognizes NYTimes and nyti short links for reader fallback`() {
        assertEquals(true, isNytUrl("https://nyti.ms/46Xv8so"))
        assertEquals(true, isNytUrl("https://www.nytimes.com/2026/03/12/us/politics/example.html"))
        assertEquals(false, isNytUrl("https://example.com/article"))
    }

    @Test
    fun `recognizes Washington Post links for reader fallback`() {
        assertEquals(
            true,
            isWashingtonPostUrl("https://www.washingtonpost.com/technology/2026/03/17/israel-journalist-polymarket-iran-strike/"),
        )
        assertEquals(true, isWashingtonPostUrl("https://wapo.st/4ciOmdN"))
        assertEquals(true, isWashingtonPostUrl("https://washingtonpost.com/example"))
        assertEquals(false, isWashingtonPostUrl("https://example.com/article"))
    }

    @Test
    fun `recognizes Bloomberg links for reader fallback`() {
        assertEquals(true, isBloombergUrl("http://bloom.bg/41WfJW4"))
        assertEquals(true, isBloombergUrl("https://www.bloomberg.com/news/articles/2026-04-07/example"))
        assertEquals(false, isBloombergUrl("https://example.com/article"))
    }

    @Test
    fun `recognizes Sprinklr short links for reader fallback`() {
        assertEquals(true, isSprinklrShortUrl("http://spklr.io/6043EyVh7"))
        assertEquals(true, isSprinklrShortUrl("https://spklr.io/6043EyVh7"))
        assertEquals(false, isSprinklrShortUrl("https://www.scientificamerican.com/article/example/"))
    }

    @Test
    fun `recognizes Ebx short links for reader fallback`() {
        assertEquals(true, isEbxShortUrl("https://ebx.sh/6q8VNF"))
        assertEquals(true, isEbxShortUrl("https://www.ebx.sh/6q8VNF"))
        assertEquals(false, isEbxShortUrl("https://www.rp.pl/sluzby/art44126601-example"))
    }

    @Test
    fun `recognizes DlvrIt short links for reader fallback`() {
        assertEquals(true, isDlvrItShortUrl("http://dlvr.it/TS1M9k"))
        assertEquals(true, isDlvrItShortUrl("https://www.dlvr.it/TS1M9k"))
        assertEquals(false, isDlvrItShortUrl("https://wiadomosci.onet.pl/kraj/example"))
    }

    @Test
    fun `parses Ebx reader fallback published date`() {
        val result = parseReaderMarkdownResult(
            url = "https://ebx.sh/6q8VNF",
            text = """
                Title: Od Włodzimierza Czarzastego po Grzegorza Brauna. Politycy narażają na szwank bezpieczeństwo Polski - rp.pl

                URL Source: https://ebx.sh/6q8VNF

                Published Time: 2026-04-11T07:00:00+02:00

                Markdown Content:
                ## Dlaczego Polska ma tak kulawy system ochrony informacji niejawnych?
            """.trimIndent(),
        )

        assertEquals(
            "Od Włodzimierza Czarzastego po Grzegorza Brauna. Politycy narażają na szwank bezpieczeństwo Polski - rp.pl",
            result.title,
        )
        assertEquals(Instant.parse("2026-04-11T05:00:00Z"), result.publishedAt)
    }

    @Test
    fun `parses DlvrIt reader fallback compact timezone published date`() {
        val result = parseReaderMarkdownResult(
            url = "http://dlvr.it/TS1M9k",
            text = """
                Title: Bogdan Święczkowski stawia warunki sędziom Trybunału Konstytucyjnego

                URL Source: http://dlvr.it/TS1M9k

                Published Time: 2026-04-13T18:59:27+0200

                Markdown Content:
                Trybunał Konstytucyjny znalazł się w impasie po sejmowych uchwałach.
            """.trimIndent(),
        )

        assertEquals(Instant.parse("2026-04-13T16:59:27Z"), result.publishedAt)
    }

    @Test
    fun `parses NYTimes reader fallback title published time and lead`() {
        val thumbnail = "https://static01.nyt.com/images/2026/03/12/multimedia/12dc-russiasanctions-whjc/12dc-russiasanctions-whjc-articleLarge.jpg?quality=75&auto=webp&disable=upscale"
        val result = parseReaderMarkdownResult(
            url = "https://nyti.ms/46Xv8so",
            text = """
                Title: Trump Removes Sanctions on Russia to Help Oil Flow Amid Iran Conflict - The New York Times

                URL Source: https://nyti.ms/46Xv8so

                Published Time: 2026-03-13T01:32:01.000Z

                Markdown Content:
                # Trump Removes Sanctions on Russia to Help Oil Flow Amid Iran Conflict

                Treasury Secretary Scott Bessent said it was “unfortunate” that the move could benefit Russia, but maintained that it was only for the short term.

                ![Image 1: Treasury Secretary Scott Bessent, wearing a dark suit, walks outside.]($thumbnail)

                The United States on Thursday temporarily lifted sanctions on Russian oil that is currently at sea.
            """.trimIndent(),
        )

        assertEquals("Trump Removes Sanctions on Russia to Help Oil Flow Amid Iran Conflict - The New York Times", result.title)
        assertEquals(Instant.parse("2026-03-13T01:32:01Z"), result.publishedAt)
        assertEquals("Trump Removes Sanctions on Russia to Help Oil Flow Amid Iran Conflict", result.lead)
        assertEquals(thumbnail, result.thumbnail)
    }

    @Test
    fun `parses NYTimes reader fallback for short link that redirects through trib al`() {
        val result = parseReaderMarkdownResult(
            url = "https://nyti.ms/4tisLZM",
            text = """
                Title: Pam Bondi Fired as Trump’s Attorney General

                URL Source: https://nyti.ms/4tisLZM

                Published Time: 2026-04-02T17:07:51.000Z

                Markdown Content:
                You have been granted access, use your keyboard to continue reading.

                In a social media post, President Trump said he was replacing Ms. Bondi with Todd Blanche, her deputy, on an interim basis.

                April 2, 2026
            """.trimIndent(),
        )

        assertEquals("Pam Bondi Fired as Trump’s Attorney General", result.title)
        assertEquals(Instant.parse("2026-04-02T17:07:51Z"), result.publishedAt)
        assertEquals(
            "In a social media post, President Trump said he was replacing Ms. Bondi with Todd Blanche, her deputy, on an interim basis.",
            result.lead,
        )
    }

    @Test
    fun `parses Washington Post reader fallback title published time thumbnail and lead`() {
        val thumbnail =
            "https://www.washingtonpost.com/wp-apps/imrs.php?src=https://cloudfront-us-east-1.images.arcpublishing.com/wapo/RIXQ5R4N7JG2SWLWTGVK77JCLQ.JPG&w=440%20400w"
        val result = parseReaderMarkdownResult(
            url = "https://www.washingtonpost.com/technology/2026/03/17/israel-journalist-polymarket-iran-strike/",
            text = """
                Title: A journalist reported a missile strike. Then came the death threats.

                URL Source: https://www.washingtonpost.com/technology/2026/03/17/israel-journalist-polymarket-iran-strike/

                Published Time: 2026-03-17T09:00:00.273Z

                Markdown Content:
                # War reporter says Polymarket bettors pressured him to change article - The Washington Post

                # A journalist reported a missile strike. Then came the death threats.

                A Times of Israel reporter says online gamblers pressured him to change his story about an Iranian missile strike so they could win a payout.

                ![Image 1]($thumbnail)
            """.trimIndent(),
        )

        assertEquals("A journalist reported a missile strike. Then came the death threats.", result.title)
        assertEquals(Instant.parse("2026-03-17T09:00:00.273Z"), result.publishedAt)
        assertEquals(
            "War reporter says Polymarket bettors pressured him to change article - The Washington Post",
            result.lead,
        )
        assertEquals(thumbnail, result.thumbnail)
    }

    @Test
    fun `parses Bloomberg reader fallback title published time thumbnail and lead`() {
        val thumbnail = "https://assets.bwbx.io/images/users/iqjWHBFdfxIU/iXnD6rCw2QMI/v3/620x-1.jpg"
        val result = parseReaderMarkdownResult(
            url = "http://bloom.bg/41WfJW4",
            text = """
                Title: Vance Picks Fight With Europe Over Orban in Vote Endorsement

                URL Source: http://bloom.bg/41WfJW4

                Published Time: 2026-04-07T12:27:35.149Z

                Markdown Content:
                # Vance Picks Fight With Europe Over Orban in Vote Endorsement - Bloomberg

                ![Image 2]($thumbnail)

                Vice President JD Vance criticized the European Union for “interference” in the Hungarian election as he endorsed Prime Minister Viktor Orban.
            """.trimIndent(),
        )

        assertEquals("Vance Picks Fight With Europe Over Orban in Vote Endorsement", result.title)
        assertEquals(Instant.parse("2026-04-07T12:27:35.149Z"), result.publishedAt)
        assertEquals("Vance Picks Fight With Europe Over Orban in Vote Endorsement - Bloomberg", result.lead)
        assertEquals(thumbnail, result.thumbnail)
    }

    @Test
    fun `parses Sprinklr reader fallback title published time thumbnail and lead`() {
        val thumbnail =
            "https://static.scientificamerican.com/dam/m/65df83249b697135/original/GettyImages-2233062247-zuckerberg-trump.jpeg?m=1774619348.752&w=600"
        val result = parseReaderMarkdownResult(
            url = "http://spklr.io/6043EyVh7",
            text = """
                Title: Trump’s new science panel includes 9 tech billionaires—and just one scientist

                URL Source: http://spklr.io/6043EyVh7

                Published Time: 2026-03-27T10:00:00-04:00

                Markdown Content:
                March 27, 2026

                3 min read

                [![Image 1: Google Logo](blob:http://localhost/017ffba74acfdd6a516023e1c9567961)Add Us On Google Add SciAm](https://www.google.com/preferences/source?q=scientificamerican.com)

                There’s a glaring hole in the president’s new science and tech council

                By Dan Garisto

                ![Image 2: Mark Zuckerberg and President Donald Trump laugh during a dinner with tech leaders at the White House on September 4, 2025.]($thumbnail)
            """.trimIndent(),
        )

        assertEquals("Trump’s new science panel includes 9 tech billionaires—and just one scientist", result.title)
        assertEquals(Instant.parse("2026-03-27T14:00:00Z"), result.publishedAt)
        assertEquals("There’s a glaring hole in the president’s new science and tech council", result.lead)
        assertEquals(thumbnail, result.thumbnail)
    }

    @Test
    fun `extracts thumbnail from Facebook og image`() {
        val expectedThumbnail = "https://lookaside.fbsbx.com/lookaside/crawler/media/?media_id=1605899074085738"
        val html = """
            <!doctype html>
            <html>
              <head>
                <meta property="og:url" content="https://www.facebook.com/wysokienapieciepl/posts/pfbid0TFpq7SGQtgZvDuMdZQskpMHCABrxHznrNeKkGoz4VpHDuVk5c6bd1BGT2j1XredMl">
                <meta property="og:title" content="WysokieNapiecie.pl">
                <meta property="og:image" content="$expectedThumbnail">
              </head>
              <body>Post</body>
            </html>
        """.trimIndent()

        withServer(html) { url ->
            val result = EnrichmentService(RestClient.builder()).enrich(url)

            assertEquals(expectedThumbnail, result.thumbnail)
        }
    }

    @Test
    fun `extracts thumbnail from twitter image when og image is missing`() {
        val expectedThumbnail = "https://lookaside.fbsbx.com/lookaside/crawler/media/?media_id=1605899074085738"
        val html = """
            <!doctype html>
            <html>
              <head>
                <meta property="og:title" content="WysokieNapiecie.pl">
                <meta name="twitter:image" content="$expectedThumbnail">
              </head>
              <body>Post</body>
            </html>
        """.trimIndent()

        withServer(html) { url ->
            val result = EnrichmentService(RestClient.builder()).enrich(url)

            assertEquals(expectedThumbnail, result.thumbnail)
        }
    }

    @Test
    fun `extracts Facebook embedded publish timestamp`() {
        val html = """
            <!doctype html>
            <html>
              <head>
                <meta property="og:title" content="Artur Kurasiński">
                <meta property="og:description" content="Walka Trumpa z putinem wygląda tak.">
              </head>
              <body>
                <script>{"publish_time\":1762631004,"creation_time\":1762652986}</script>
              </body>
            </html>
        """.trimIndent()

        withServer(html) { url ->
            val result = EnrichmentService(RestClient.builder()).enrich(url)

            assertEquals(Instant.parse("2025-11-08T19:43:24Z"), result.publishedAt)
        }
    }

    @Test
    fun `uses expanded Facebook embedded message instead of see more teaser`() {
        val expected = "Kiedy uciekasz przed więzieniem do Budapesztu, ale ten Budapeszt to trochę jak więzienie.\n\n" +
            "więcej: https://www.donald.pl/artykuly/tCWEBCLq/wegierski-gambit-posel-romanowski-z-uniewaznionymi-paszportami-ziobre-moze-czekac-to-samo"
        val html = """
            <!doctype html>
            <html>
              <head>
                <meta property="og:url" content="https://www.facebook.com/serwisdonaldpl/posts/pfbid02fLBt8gqqFa59U1MmSiBsgz1zzE4EtNnPxmxrGsnFLXHsFgu2rSuZvgAxnpbpmwf6l">
                <meta property="og:title" content="donald.pl">
                <meta property="og:description" content="Kiedy uciekasz przed więzieniem do Budapesztu, ale ten Budapeszt to trochę jak więzienie. więcej:...">
              </head>
              <body>
                <script type="application/json">
                  {
                    "post_id":"894176306277335",
                    "actors":[],
                    "message":{
                      "__typename":"TextWithEntities",
                      "text":"Kiedy uciekasz przed wi\u0119zieniem do Budapesztu, ale ten Budapeszt to troch\u0119 jak wi\u0119zienie.\n\nwi\u0119cej: https:\/\/www.donald.pl\/artykuly\/tCWEBCLq\/wegierski-gambit-posel-romanowski-z-uniewaznionymi-paszportami-ziobre-moze-czekac-to-samo"
                    }
                  }
                </script>
              </body>
            </html>
        """.trimIndent()

        withServer(html) { url ->
            val result = EnrichmentService(RestClient.builder()).enrich(url)

            assertEquals(expected, result.lead)
            assertEquals(expected, result.plainText)
        }
    }

    @Test
    fun `extracts Facebook embedded message from large payload without regex overflow`() {
        val expected = "Niebezpiecznik post body with enough text to cache safely."
        val noise = "x".repeat(25_000)
        val html = """
            <!doctype html>
            <html>
              <head>
                <meta property="og:url" content="https://www.facebook.com/niebezpiecznik/posts/pfbid02zG5UuLqFzVEeBHi4dLqX1kX96z3JhCpLE6mCZgrT4TjbrGzQzsP1wYMhGjWRcdQFl">
                <meta property="og:title" content="Niebezpiecznik">
                <meta property="og:description" content="Niebezpiecznik post body...">
              </head>
              <body>
                <script type="application/json">{"noise":"$noise","message":{"__typename":"TextWithEntities","text":"$expected"}}</script>
              </body>
            </html>
        """.trimIndent()

        withServer(html) { url ->
            val result = EnrichmentService(RestClient.builder()).enrich(url)

            assertEquals(expected, result.lead)
        }
    }

    @Test
    fun `extracts JSON-LD NewsArticle published date with offset`() {
        val html = """
            <!doctype html>
            <html>
              <head>
                <meta property="og:title" content="RP article">
                <script type="application/ld+json">
                  {
                    "@context": "https://schema.org",
                    "@type": "NewsArticle",
                    "datePublished": "2025-12-01T11:12:00+01:00"
                  }
                </script>
              </head>
              <body>Article</body>
            </html>
        """.trimIndent()

        withServer(html) { url ->
            val result = EnrichmentService(RestClient.builder()).enrich(url)

            assertEquals(Instant.parse("2025-12-01T10:12:00Z"), result.publishedAt)
        }
    }

    @Test
    fun `extracts JSON-LD NewsArticle published date with compact timezone offset`() {
        val html = """
            <!doctype html>
            <html>
              <head>
                <meta property="og:title" content="Trybunał Konstytucyjny w impasie. Bogdan Święczkowski zabiera głos">
                <meta property="og:image" content="https://cdn.wiadomosci.onet.pl/example.jpg">
                <script type="application/ld+json">
                  {
                    "@context": "https://schema.org",
                    "@type": "NewsArticle",
                    "datePublished": "2026-04-13T18:59:27+0200"
                  }
                </script>
              </head>
              <body>Article</body>
            </html>
        """.trimIndent()

        withServer(html) { url ->
            val result = EnrichmentService(RestClient.builder()).enrich(url)

            assertEquals("Trybunał Konstytucyjny w impasie. Bogdan Święczkowski zabiera głos", result.title)
            assertEquals("https://cdn.wiadomosci.onet.pl/example.jpg", result.thumbnail)
            assertEquals(Instant.parse("2026-04-13T16:59:27Z"), result.publishedAt)
        }
    }

    @Test
    fun `extracts JSON-LD NewsArticle published date from graph`() {
        val html = """
            <!doctype html>
            <html>
              <head>
                <meta property="og:title" content="XYZ article">
                <script type="application/ld+json">
                  {
                    "@context": "https://schema.org",
                    "@graph": [
                      {
                        "@type": "BreadcrumbList",
                        "name": "Breadcrumbs"
                      },
                      {
                        "@type": "NewsArticle",
                        "headline": "Donald Trump: zniszczyliśmy prawie wszystkie stawiacze min",
                        "datePublished": "2026-03-11T18:55:00+00:00",
                        "dateModified": "2026-03-11T18:55:00+00:00"
                      }
                    ]
                  }
                </script>
              </head>
              <body>Article</body>
            </html>
        """.trimIndent()

        withServer(html) { url ->
            val result = EnrichmentService(RestClient.builder()).enrich(url)

            assertEquals(Instant.parse("2026-03-11T18:55:00Z"), result.publishedAt)
        }
    }

    @Test
    fun `extracts JSON-LD WebPage published date from graph`() {
        val html = """
            <!doctype html>
            <html>
              <head>
                <meta property="og:title" content="Demagog article">
                <script type="application/ld+json">
                  {
                    "@context": "https://schema.org",
                    "@graph": [
                      {
                        "@type": "WebPage",
                        "name": "OZE sroze? Czarnek myli się w sprawie biomasy z Indonezji",
                        "datePublished": "2026-03-13T09:56:49+00:00",
                        "dateModified": "2026-03-13T18:49:40+00:00"
                      },
                      {
                        "@type": "ClaimReview",
                        "datePublished": "2026-03-13"
                      }
                    ]
                  }
                </script>
              </head>
              <body>Article</body>
            </html>
        """.trimIndent()

        withServer(html) { url ->
            val result = EnrichmentService(RestClient.builder()).enrich(url)

            assertEquals(Instant.parse("2026-03-13T09:56:49Z"), result.publishedAt)
        }
    }

    private fun withServer(body: String, path: String = "/", block: (String) -> Unit) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext(path) { exchange ->
            val bytes = body.toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "text/html; charset=utf-8")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }

        try {
            server.start()
            block("http://127.0.0.1:${server.address.port}$path")
        } finally {
            server.stop(0)
        }
    }

    private fun withHeaderCheckingServer(body: String, block: (String) -> Unit) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val userAgent = exchange.requestHeaders.getFirst("User-Agent").orEmpty()
            val accept = exchange.requestHeaders.getFirst("Accept").orEmpty()
            val acceptLanguage = exchange.requestHeaders.getFirst("Accept-Language").orEmpty()
            if (!userAgent.contains("Mozilla/5.0") ||
                !accept.contains("text/html") ||
                !acceptLanguage.contains("pl-PL")
            ) {
                exchange.sendResponseHeaders(HttpURLConnection.HTTP_FORBIDDEN, -1)
            } else {
                val bytes = body.toByteArray(Charsets.UTF_8)
                exchange.responseHeaders.add("Content-Type", "text/html; charset=utf-8")
                exchange.sendResponseHeaders(HttpURLConnection.HTTP_OK, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
        }

        try {
            server.start()
            block("http://127.0.0.1:${server.address.port}/")
        } finally {
            server.stop(0)
        }
    }

    private fun enrichHtml(url: String, html: String): EnrichmentResult {
        val service = EnrichmentService(RestClient.builder())
        val method = EnrichmentService::class.java.getDeclaredMethod(
            "enrichHtml",
            String::class.java,
            String::class.java,
        )
        method.isAccessible = true
        return method.invoke(service, url, html) as EnrichmentResult
    }
}

private fun String.escapeHtmlAttribute(): String =
    replace("&", "&amp;")
        .replace("\"", "&quot;")
