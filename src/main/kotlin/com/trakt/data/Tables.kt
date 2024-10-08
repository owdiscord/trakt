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
}

class UserEntity(id: EntityID<Int>) : IntEntity(id) {
  companion object : IntEntityClass<UserEntity>(UsersTable)

  var snowflake by UsersTable.snowflake
  var messageScore by UsersTable.messageScore
  var timeScore by UsersTable.timeScore
  var hasAward by UsersTable.hasAward
}
