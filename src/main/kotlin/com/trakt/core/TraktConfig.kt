package com.trakt.core

import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class TraktConfig(
  val guild: ULong,
  val role: ULong,
  val messageThreshold: Int,
  val timeThreshold: Int,
  val decayMagnitude: Int,
  val decayPeriod: Int,
  val warnDelay: Int,
  val muteDelay: Int,
  val banDelay: Int
) {

  companion object {
    fun readConfig(path: String): TraktConfig {
      return Json.decodeFromString(File(path).readText())
    }
  }
}
