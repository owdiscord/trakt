package com.trakt.data

import org.jetbrains.exposed.dao.IntEntity
import org.jetbrains.exposed.dao.IntEntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.IntIdTable
import org.jetbrains.exposed.sql.kotlin.datetime.date

object UsersTable : IntIdTable() {
  val snowflake = ulong("snowflake").index()
  val messageScore = long("message_score").default(1)
  val timeScore = long("time_score").default(0)
  val hasAward = bool("has_regular").default(false)
  val isBanned = bool("is_banned").default(false)
  val isMuted = bool("is_muted").default(false)
  val sanctionTime = long("sanction_time").nullable().default(null)
}

class UserEntity(id: EntityID<Int>) : IntEntity(id) {
  companion object : IntEntityClass<UserEntity>(UsersTable)

  var snowflake by UsersTable.snowflake
  var messageScore by UsersTable.messageScore
  var timeScore by UsersTable.timeScore
  var hasAward by UsersTable.hasAward
  var isBanned by UsersTable.isBanned
  var isMuted by UsersTable.isMuted
  var sanctionTime by UsersTable.sanctionTime
}

object VoiceSessionTable : IntIdTable() {
  val snowflake = ulong("snowflake").index()
  val sessionDate = date("session_date")
  val sessionDuration = long("session_duration")
}

class VoiceSessionEntity(id: EntityID<Int>) : IntEntity(id) {
  companion object : IntEntityClass<VoiceSessionEntity>(VoiceSessionTable)

  var snowflake by VoiceSessionTable.snowflake
  var sessionDate by VoiceSessionTable.sessionDate
  var sessionDuration by VoiceSessionTable.sessionDuration
}

object VoiceSummaryTable : IntIdTable() {
  val snowflake = ulong("snowflake").index()
  val weekTotal = long("week_total")
  val monthTotal = long("month_total")
  val hasAward = bool("has_regular")
}

class VoiceSummaryEntity(id: EntityID<Int>) : IntEntity(id) {
  companion object : IntEntityClass<VoiceSummaryEntity>(VoiceSummaryTable)

  var snowflake by VoiceSummaryTable.snowflake
  var weekTotal by VoiceSummaryTable.weekTotal
  var monthTotal by VoiceSummaryTable.monthTotal
  var hasAward by VoiceSummaryTable.hasAward
}

object MessageTrackingTable : IntIdTable() {
  val owner = ulong("owner").index()
  val target = ulong("target").index()
  val timeout = integer("timeout")
}

class MessageTrackingEntity(id: EntityID<Int>) : IntEntity(id) {
  companion object : IntEntityClass<MessageTrackingEntity>(MessageTrackingTable)

  var owner by MessageTrackingTable.owner
  var target by MessageTrackingTable.target
  var timeout by MessageTrackingTable.timeout
}
