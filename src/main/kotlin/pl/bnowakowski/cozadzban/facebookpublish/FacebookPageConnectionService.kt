// SPDX-License-Identifier: GPL-3.0-or-later
package pl.bnowakowski.cozadzban.facebookpublish

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.bnowakowski.cozadzban.article.ArticleService
import java.time.ZoneId

@Service
class FacebookPageConnectionService(
    private val repository: FacebookPageConnectionRepository,
    private val encryptor: FacebookPageTokenEncryptor,
) {
    fun all(): List<FacebookPageConnection> = repository.all()

    @Transactional
    fun connect(languageInput: String, page: DiscoveredFacebookPage): FacebookPageConnection {
        val language = ArticleService.normalizeLanguage(languageInput)
        require(page.canCreateContent) {
            "The selected Page did not grant content creation access"
        }
        repository.upsert(language, AvailableFacebookPage(page.id, page.name, page.tasks), encryptor.encrypt(page.token))
        return repository.find(language)!!
    }

    @Transactional
    fun saveSettings(languageInput: String, settings: FacebookPageSettings): FacebookPageConnection {
        val language = ArticleService.normalizeLanguage(languageInput)
        ZoneId.of(settings.timeZone)
        require(settings.monthlyLinkLimit in 0..100) { "Monthly link limit must be between 0 and 100" }
        require(settings.minimumPostAgeHours in 0..8760) { "Minimum post age must be between 0 and 8760 hours" }
        require(settings.schedulerIntervalMinutes in 1..10080) { "Scheduler interval must be between 1 and 10080 minutes" }
        require(repository.updateSettings(language, settings)) { "No Page connected for $language" }
        return repository.find(language)!!
    }

    @Transactional
    fun disconnect(languageInput: String): Boolean = repository.delete(ArticleService.normalizeLanguage(languageInput))

    fun tokenForEnabledLanguage(languageInput: String): String? {
        val language = ArticleService.normalizeLanguage(languageInput)
        if (repository.find(language)?.enabled != true) return null
        return repository.encryptedToken(language)?.let(encryptor::decrypt)
    }
}
