// SPDX-License-Identifier: GPL-3.0-or-later
package pl.bnowakowski.cozadzban.facebookpublish

import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClientResponseException
import pl.bnowakowski.cozadzban.article.ArticleRepository

@Component
class FacebookPageSyncScheduler(
    private val repository: FacebookPageSyncRepository,
    private val pages: FacebookPageConnectionService,
    private val articles: ArticleRepository,
    private val graph: FacebookPageSyncGraphClient,
    private val images: FacebookArticleImageGenerationRepository,
    private val generator: CodexArticleImageGenerator,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val json = ObjectMapper()

    @Scheduled(initialDelay = 45_000, fixedDelay = 60_000)
    fun synchronize() {
        repository.recoverInterrupted()
        repeat(10) {
            val job = repository.claim() ?: return
            val token = try { pages.tokenForEnabledLanguage(job.language) } catch (_: Exception) { null }
            val connected = pages.all().any { it.language == job.language && it.pageId == job.pageId && it.enabled }
            if (token == null || !connected) {
                repository.fail(job.id, false, "The original Page is no longer connected and enabled.")
                return@repeat
            }
            try {
                var replacedPhotoId: String? = null
                when (job.action) {
                    "UPDATE" -> {
                        val article = job.articleId?.let { articles.findById(it).orElse(null) }
                        if (article == null) {
                            repository.fail(job.id, false, "The article was deleted before its post could be updated.")
                            return@repeat
                        }
                        graph.updatePostMessage(job.postId, token, FacebookPostCaption.forArticle(article, job.imageSource))
                        if (job.replacementRequired) {
                            val png = article.id?.takeIf { images.status(it) == "GENERATED" }
                                ?.let { generator.cached(it) }
                            if (png == null) {
                                repository.manualReplacementRequired(job.id,
                                    "A new generated illustration is not available for the Facebook photo.")
                                return@repeat
                            }
                            replacedPhotoId = try {
                                graph.replacePostPhoto(job.pageId, job.postId, token, png)
                            } catch (ex: RestClientResponseException) {
                                if (ex.statusCode.is4xxClientError && ex.statusCode.value() != 429) {
                                    val detail = metaError(ex)
                                    repository.manualReplacementRequired(job.id, detail)
                                    log.warn("Facebook Page image update rejected for post {}: {}", job.postId, detail)
                                    return@repeat
                                }
                                throw ex
                            }
                        }
                    }
                    "DELETE" -> graph.deletePost(job.postId, token)
                    else -> error("Unsupported Facebook sync action")
                }
                repository.confirm(job.id, job.action, replacedPhotoId)
            } catch (ex: RestClientResponseException) {
                val detail = metaError(ex)
                repository.fail(job.id, !ex.statusCode.is4xxClientError || ex.statusCode.value() == 429,
                    detail)
                log.warn("Facebook Page sync Meta error for post {}: {}", job.postId, detail)
            } catch (ex: Exception) {
                repository.fail(job.id, true, ex.javaClass.simpleName)
                log.warn("Facebook Page sync outcome needs review for post {}: {}", job.postId, ex.javaClass.simpleName)
            }
        }
    }

    private fun metaError(ex: RestClientResponseException): String {
        val error = runCatching { json.readTree(ex.responseBodyAsString).path("error") }.getOrNull()
        val code = error?.path("code")?.takeIf { it.isNumber }?.asInt()?.toString()
        val subcode = error?.path("error_subcode")?.takeIf { it.isNumber }?.asInt()?.toString()
        val message = error?.path("message")?.asText("")
            ?.replace(Regex("(?i)access_token=[^&\\s]+"), "access_token=[redacted]")
            ?.take(180)
        return listOfNotNull(
            "Meta HTTP ${ex.statusCode.value()}",
            code?.let { "code $it" },
            subcode?.let { "subcode $it" },
            message?.takeIf { it.isNotBlank() },
        ).joinToString(": ").take(300)
    }
}
