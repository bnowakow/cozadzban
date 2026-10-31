// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 https://bnowakowski.pl

package pl.bnowakowski.cozadzban.notifications

import org.slf4j.LoggerFactory
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Service
import java.math.RoundingMode
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import pl.bnowakowski.cozadzban.facebookimport.ApifyCreditsLowEvent
import pl.bnowakowski.cozadzban.facebookimport.FacebookApifyCreditsExhaustedEvent
import pl.bnowakowski.cozadzban.facebookimport.FacebookImportLoginRequiredEvent
import pl.bnowakowski.cozadzban.facebookimport.FacebookImportLoginTimedOutEvent
import pl.bnowakowski.cozadzban.facebookimport.FacebookImportRunCompletedEvent
import pl.bnowakowski.cozadzban.facebookimport.FacebookImportTrigger

@Service
class NotificationDeliveryService(
    private val properties: NotificationProperties,
    private val repository: NotificationPreferenceRepository,
    private val encryptor: PushoverUserKeyEncryptor,
    private val pushoverClient: PushoverClient,
) {
    @EventListener
    fun onFacebookLoginRequired(event: FacebookImportLoginRequiredEvent) {
        if (!properties.pushoverConfigured || event.trigger != FacebookImportTrigger.SCHEDULED) return
        val recipients = repository.findPushoverRecipientsForFacebookLoginRequired()
        recipients.forEach { recipient ->
            deliver(
                recipient,
                title = "Facebook login required",
                message = "Scheduled Facebook import ${event.importRunId} needs login or two-factor approval.",
                url = "https://cozadzban.pl/",
                urlTitle = "Open Co za dzban",
            )
        }
    }

    @EventListener
    fun onFacebookLoginTimedOut(event: FacebookImportLoginTimedOutEvent) {
        if (!properties.pushoverConfigured || event.trigger != FacebookImportTrigger.SCHEDULED) return
        val recipients = repository.findPushoverRecipientsForFacebookLoginRequired()
        recipients.forEach { recipient ->
            deliver(
                recipient,
                title = "Facebook login timed out",
                message = "Scheduled Facebook import ${event.importRunId} timed out waiting for login or two-factor approval.",
                url = "https://cozadzban.pl/",
                urlTitle = "Open Co za dzban",
            )
        }
    }

    @EventListener
    fun onFacebookImportRunCompleted(event: FacebookImportRunCompletedEvent) {
        if (!properties.pushoverConfigured) return

        val reviewCount = (event.submittedCount - event.autoApprovedCount).coerceAtLeast(0)
        if (reviewCount > 0) {
            val recipients = repository.findPushoverRecipientsForProposalSummary()
            val proposalWord = if (reviewCount == 1) "proposal is" else "proposals are"
            recipients.forEach { recipient ->
                deliver(
                    recipient,
                    title = "New article proposals",
                    message = "$reviewCount new Facebook article $proposalWord ready for review.",
                    url = "https://cozadzban.pl/article-proposals",
                    urlTitle = "Review proposals",
                )
            }
        }

        if (event.autoApprovedCount > 0) {
            val recipients = repository.findPushoverRecipientsForAutoApprovedProposals()
            val articleWord = if (event.autoApprovedCount == 1) "article was" else "articles were"
            recipients.forEach { recipient ->
                deliver(
                    recipient,
                    title = "Facebook articles auto-approved",
                    message = "${event.autoApprovedCount} Facebook $articleWord auto-approved.",
                    url = "https://cozadzban.pl/article-proposals",
                    urlTitle = "Open article proposals",
                )
            }
        }
    }

    @EventListener
    fun onApifyCreditsExhausted(event: FacebookApifyCreditsExhaustedEvent) {
        if (!properties.pushoverConfigured) return
        val remaining = event.remainingUsd?.let { "$" + it.setScale(2, RoundingMode.HALF_UP).toPlainString() + " left" }
        val resets = event.cycleEndAt?.let {
            "resets after " + DateTimeFormatter.ISO_LOCAL_DATE.format(it.atOffset(ZoneOffset.UTC))
        }
        val balance = listOfNotNull(remaining, resets).joinToString(", ").takeIf { it.isNotBlank() }
        val message = "Facebook Apify import ${event.importRunId} failed: Apify credits are exhausted" +
            (balance?.let { " ($it)" } ?: "") + "."
        repository.findPushoverRecipientsForApifyCreditsExhausted().forEach { recipient ->
            deliver(
                recipient,
                title = "Apify credits exhausted",
                message = message,
                url = "https://console.apify.com/billing",
                urlTitle = "Open Apify billing",
            )
        }
    }

    @EventListener
    fun onApifyCreditsLow(event: ApifyCreditsLowEvent) {
        if (!properties.pushoverConfigured) return
        fun usd(value: java.math.BigDecimal) = "$" + value.setScale(2, RoundingMode.HALF_UP).toPlainString()
        fun date(value: java.time.Instant) = DateTimeFormatter.ISO_LOCAL_DATE.format(value.atOffset(ZoneOffset.UTC))
        val wholeDays = Math.round(event.daysLeft).toInt()
        val within = when {
            event.daysLeft < 1.0 -> "within a day"
            wholeDays == 1 -> "in about 1 day"
            else -> "in about $wholeDays days"
        }
        val message = "Apify credits are predicted to run out $within (around ${date(event.predictedExhaustionAt)}): " +
            "${usd(event.remainingUsd)} of ${usd(event.limitUsd)} left, about ${usd(event.dailyUsageUsd)} used per day." +
            (event.cycleEndAt?.let { " The cycle resets after ${date(it)}." } ?: "")
        repository.findPushoverRecipientsForApifyCreditsLow().forEach { recipient ->
            deliver(
                recipient,
                title = "Apify credits running low",
                message = message,
                url = "https://console.apify.com/billing",
                urlTitle = "Open Apify billing",
            )
        }
    }

    private fun deliver(
        recipient: NotificationRecipient,
        title: String,
        message: String,
        url: String,
        urlTitle: String,
    ) {
        try {
            pushoverClient.send(
                PushoverMessage(
                    userKey = encryptor.decrypt(recipient.pushoverUserKeyEncrypted),
                    devices = recipient.pushoverDevices,
                    title = title,
                    message = message,
                    url = url,
                    urlTitle = urlTitle,
                ),
            )
        } catch (ex: Exception) {
            LOG.warn(
                "Could not deliver Pushover notification to userId={} email={}",
                recipient.appUserId,
                recipient.email,
                ex,
            )
        }
    }

    companion object {
        private val LOG = LoggerFactory.getLogger(NotificationDeliveryService::class.java)
    }
}
