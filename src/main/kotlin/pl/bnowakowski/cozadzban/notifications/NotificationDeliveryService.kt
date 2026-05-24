// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 https://bnowakowski.pl

package pl.bnowakowski.cozadzban.notifications

import org.slf4j.LoggerFactory
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Service
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
        if (!properties.pushoverConfigured || event.submittedCount <= 0) return
        val recipients = repository.findPushoverRecipientsForProposalSummary()
        val proposalWord = if (event.submittedCount == 1) "proposal is" else "proposals are"
        recipients.forEach { recipient ->
            deliver(
                recipient,
                title = "New article proposals",
                message = "${event.submittedCount} new Facebook article $proposalWord ready for review.",
                url = "https://cozadzban.pl/article-proposals",
                urlTitle = "Review proposals",
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
