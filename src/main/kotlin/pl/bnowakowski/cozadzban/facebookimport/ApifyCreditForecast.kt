// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 https://bnowakowski.pl

package pl.bnowakowski.cozadzban.facebookimport

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.ApplicationEventPublisher
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.stereotype.Repository
import pl.bnowakowski.cozadzban.facebookpublish.utcTimestamp
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.time.Clock
import java.time.Duration
import java.time.Instant

/** When the Apify usage of the current billing cycle is predicted to run out at the current spending rate. */
data class ApifyCreditForecast(
    val predictedExhaustionAt: Instant,
    val daysLeft: Double,
    val dailyUsageUsd: BigDecimal,
    val usage: ApifyAccountUsage,
) {
    /** The alert this forecast calls for: 1 or 3 days before exhaustion, or null when it is further away. */
    val alertDaysBefore: Int? get() = ALERT_DAYS_BEFORE.sorted().firstOrNull { daysLeft <= it }

    companion object {
        /** Alerts are sent when exhaustion is predicted within these many days. */
        val ALERT_DAYS_BEFORE = listOf(3, 1)

        /**
         * Linear forecast from the spend so far this cycle. Elapsed time counts as at least one day,
         * so a large spend in the first hours doesn't predict a far too steep rate. Returns null when
         * nothing is left (the exhausted notification covers that), nothing has been spent yet, the
         * cycle start is unknown, or the cycle resets before the credit would run out.
         */
        fun predict(usage: ApifyAccountUsage, now: Instant): ApifyCreditForecast? {
            val cycleStart = usage.cycleStartAt ?: return null
            if (usage.remainingUsd.signum() <= 0 || usage.usedUsd.signum() <= 0) return null
            val elapsedDays = maxOf(Duration.between(cycleStart, now).toSeconds() / SECONDS_PER_DAY, 1.0)
            val dailyUsageUsd = usage.usedUsd.divide(BigDecimal.valueOf(elapsedDays), MathContext.DECIMAL64)
            val daysLeft = usage.remainingUsd.divide(dailyUsageUsd, MathContext.DECIMAL64).toDouble()
            val predictedAt = now.plusSeconds((daysLeft * SECONDS_PER_DAY).toLong())
            if (usage.cycleEndAt != null && predictedAt.isAfter(usage.cycleEndAt)) return null
            return ApifyCreditForecast(predictedAt, daysLeft, dailyUsageUsd.setScale(4, RoundingMode.HALF_UP), usage)
        }

        private const val SECONDS_PER_DAY = 86_400.0
    }
}

/** Records which forecast alerts were sent per Apify billing cycle, so each fires only once. */
@Repository
class ApifyCreditAlertRepository(private val jdbc: NamedParameterJdbcTemplate) {
    /** True only for the caller that recorded this alert first; concurrent containers get false. */
    fun claim(cycleStartAt: Instant, daysBefore: Int, forecast: ApifyCreditForecast): Boolean =
        jdbc.update(
            """INSERT INTO apify_credit_alert(
                   cycle_start_at, days_before, predicted_exhaustion_at, remaining_usd, daily_usage_usd)
               VALUES (:cycleStartAt, :daysBefore, :predictedAt, :remainingUsd, :dailyUsageUsd)
               ON CONFLICT (cycle_start_at, days_before) DO NOTHING""",
            mapOf(
                "cycleStartAt" to utcTimestamp(cycleStartAt),
                "daysBefore" to daysBefore,
                "predictedAt" to utcTimestamp(forecast.predictedExhaustionAt),
                "remainingUsd" to forecast.usage.remainingUsd.setScale(4, RoundingMode.HALF_UP),
                "dailyUsageUsd" to forecast.dailyUsageUsd.max(BigDecimal("0.0001")),
            ),
        ) == 1
}

/**
 * Checks Apify usage hourly and publishes [ApifyCreditsLowEvent] once per billing cycle when the
 * credit is predicted to run out within 3 days, and again within 1 day.
 */
@Component
class ApifyCreditForecastScheduler(
    private val properties: FacebookImportProperties,
    private val usageClient: ApifyAccountUsageClient,
    private val alerts: ApifyCreditAlertRepository,
    private val eventPublisher: ApplicationEventPublisher,
    @Value("\${app.facebook-import.apify.low-credit-alerts-enabled:true}") private val enabled: Boolean = true,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    @Scheduled(initialDelay = 300_000, fixedDelay = 3_600_000)
    fun check() {
        if (!enabled || properties.apify.apiToken.isBlank()) return
        try {
            evaluate()
        } catch (ex: Exception) {
            logger.warn("Apify credit forecast check failed: {}", ex.message ?: ex.javaClass.simpleName)
        }
    }

    internal fun evaluate(): ApifyCreditForecast? {
        val usage = usageClient.fresh() ?: return null
        val cycleStart = usage.cycleStartAt ?: return null
        val forecast = ApifyCreditForecast.predict(usage, clock.instant()) ?: return null
        val daysBefore = forecast.alertDaysBefore ?: return forecast
        val claimed = alerts.claim(cycleStart, daysBefore, forecast)
        // When the first check already finds less than a day left, skip the 3-day alert for this cycle.
        ApifyCreditForecast.ALERT_DAYS_BEFORE.filter { it > daysBefore }.forEach { alerts.claim(cycleStart, it, forecast) }
        if (claimed) {
            logger.info(
                "Apify credits predicted to run out at {} ({} days, {} USD/day); sending {}-day alert",
                forecast.predictedExhaustionAt, "%.1f".format(forecast.daysLeft), forecast.dailyUsageUsd, daysBefore,
            )
            eventPublisher.publishEvent(
                ApifyCreditsLowEvent(
                    daysBefore = daysBefore,
                    daysLeft = forecast.daysLeft,
                    predictedExhaustionAt = forecast.predictedExhaustionAt,
                    remainingUsd = usage.remainingUsd,
                    limitUsd = usage.limitUsd,
                    dailyUsageUsd = forecast.dailyUsageUsd,
                    cycleEndAt = usage.cycleEndAt,
                ),
            )
        }
        return forecast
    }
}
