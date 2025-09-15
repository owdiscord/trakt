package com.trakt.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.time.Duration

@Serializable
data class TraktConfig(
    val token: String,
    val guild: ULong,
    val role: ULong,
    val sanctionChannel: ULong,
    val announceChannel: ULong,
    /** Required message score for award. */
    val messageAwardThreshold: Long,
    /** Required message score before we begin tracking time for this user. */
    val timeTrackingMessageThreshold: Long,
    /**
     * Required time score for award. Not tracked until user message score is above
     * [timeTrackingMessageThreshold]
     */
    val timeAwardThreshold: Long,
    /** Value of message score deducted at regular intervals. */
    val messageDecayMagnitude: Int,
    /** Interval at which to perform message score decay. */
    val messageDecayPeriod: Duration,
    /** Delay before a user can gain further message score. */
    val messageTimeout: Duration,
    /** Number of [timeScorePeriod]s to delay a user when warned */
    val warnDelayPeriods: Long,
    /** Number of [timeScorePeriod]s to delay a user when muted */
    val muteDelayPeriods: Long,
    /** Number of [timeScorePeriod]s to delay a user when banned (nb: applies only after rejoin) */
    val banDelayPeriods: Long,
    /** How often to advance time score for all tracked users. */
    val timeScorePeriod: Duration,
    /** How often to commit in-memory message score tracking to storage. */
    val progressSavePeriod: Duration,
    /** Don't actually commit role changes (but announce them still) */
    val trialMode: Boolean,
    /** Role required to do mass role operations */
    val massRoleRole: ULong,
    /** Total voice time required in the last 7 days */
    val voiceWeekThreshold: Duration,
    /** Total voice time required in the last 30 days */
    val voiceMonthThreshold: Duration,
    /** How often to process voice data */
    val voiceTickPeriod: Duration,
) {

  companion object {
    fun readConfig(path: String): TraktConfig {
      return Json.decodeFromString(File(path).readText())
    }
  }
}
