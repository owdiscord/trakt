package com.trakt.data

import org.jetbrains.exposed.dao.IntEntity
import org.jetbrains.exposed.dao.IntEntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.IntIdTable

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
