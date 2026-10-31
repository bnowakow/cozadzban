// SPDX-License-Identifier: GPL-3.0-or-later
package pl.bnowakowski.cozadzban.facebookpublish

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClientResponseException
import pl.bnowakowski.cozadzban.article.ArticleRepository
import java.time.Duration

@Configuration
@EnableScheduling
class FacebookPublicationScheduling

@Component
class FacebookPublicationWorker(
    private val queue: FacebookPublicationQueue,
    private val articles: ArticleRepository,
    private val pages: FacebookPageConnectionService,
    private val imageProvider: FacebookPostImageProvider,
    private val imageJobs: FacebookArticleImageGenerationRepository,
    private val imageGenerator: CodexArticleImageGenerator,
    private val publisher: FacebookPhotoPublisher,
    @Value("\${app.facebook-image.publication-wait-max:24h}") private val imageWaitMax: Duration = DEFAULT_IMAGE_WAIT,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(initialDelay = 15_000, fixedDelay = 15_000)
    fun publishPending() {
        queue.recoverInterruptedClaims()
        repeat(10) {
            val job = queue.claim(imageGenerator.isEnabled(), imageWaitMax) ?: return
            publish(job)
        }
    }

    private fun publish(job: FacebookPublicationJob) {
        val article = articles.findById(job.articleId).orElse(null)
        val page = pages.all().firstOrNull { it.language == job.language && it.enabled && it.pageId == job.pageId }
        val token = try {
            page?.let { pages.tokenForEnabledLanguage(job.language) }
        } catch (_: Exception) {
            queue.markFailed(job.id, "Stored Page access could not be read; reconnect the Page.")
            return
        }
        if (article == null || page == null || token == null) {
            queue.markFailed(job.id, "Article or enabled Page connection is no longer available.")
            return
        }
        var image = try {
            imageProvider.forArticle(article)
        } catch (ex: Exception) {
            queue.markFailed(job.id, "Could not select a Page image (${ex.javaClass.simpleName}).")
            return
        }
        try {
            val result = try {
                publisher.publish(page.pageId, token, FacebookPostCaption.forArticle(article, image.source), image)
            } catch (ex: RestClientResponseException) {
                if (ex.statusCode.value() != 400) throw ex
                if (image is FacebookPostImage.Thumbnail && imageGenerator.isEnabled()) {
                    // The explicit rejection happened before Meta created a post. Retry with a generated image later.
                    imageJobs.sourceThumbnailRejected(article.id!!)
                    queue.deferForImage(job.id)
                    return
                }
                if (image is FacebookPostImage.Generated) imageJobs.rejectGenerated(article.id!!)
                if (image is FacebookPostImage.Placeholder) throw ex
                image = imageProvider.placeholder(article.language)
                publisher.publish(page.pageId, token, FacebookPostCaption.forArticle(article, image.source), image)
            }
            val postId = result.postId
            if (postId == null) {
                queue.markUncertain(job.id, "Meta accepted the image but did not return a Page post ID; reconcile manually.",
                    image.source, result.photoId)
                return
            }
            queue.markPublished(job.id, postId, result.permalink!!, image.source, result.photoId)
            log.info("Published Facebook Page post for article {} on Page {}", article.id, page.pageId)
        } catch (ex: RestClientResponseException) {
            if (ex.statusCode.is4xxClientError && ex.statusCode.value() != 429) {
                queue.markFailed(job.id, "Meta rejected the post (HTTP ${ex.statusCode.value()}).", image.source)
            } else {
                queue.markUncertain(job.id, "Meta response was uncertain (HTTP ${ex.statusCode.value()}); verify the Page.", image.source)
            }
        } catch (ex: Exception) {
            log.warn("Facebook Page publication outcome needs reconciliation for article {}: {}", article.id, ex.javaClass.simpleName)
            queue.markUncertain(job.id, "Publication result is uncertain (${ex.javaClass.simpleName}); verify the Page.", image.source)
        }
    }

}
