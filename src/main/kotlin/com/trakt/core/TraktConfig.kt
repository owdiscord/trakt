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
  /** Required message score for award. */
  val messageAwardThreshold: Int,
  /** Required message score before we begin tracking time for this user. */
  val timeTrackingMessageThreshold: Int,
  /** Required time score for award. Not tracked until user message score is above [timeTrackingMessageThreshold] */
  val timeAwardThreshold: Int,
  /** Value of message score deducted at regular intervals. */
  val messageDecayMagnitude: Int,
  /** Interval at which to perform message score decay. */
  val messageDecayPeriod: Duration,
  /** Delay before a user can gain further message score. */
  val messageTimeout: Duration,
  val warnDelay: Duration,
  val muteDelay: Duration,
  val banDelay: Duration,
  /** How often to advance time score for all tracked users. */
  val timeScorePeriod: Duration,
  /** How often to commit in-memory message score tracking to storage. */
  val progressSavePeriod: Duration,
) {

  companion object {
    fun readConfig(path: String): TraktConfig {
      return Json.decodeFromString(File(path).readText())
    }
  }
}
