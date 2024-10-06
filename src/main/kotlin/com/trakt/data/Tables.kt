package com.trakt.data

import com.trakt.core.ProgressManager
import org.jetbrains.exposed.dao.IntEntity
import org.jetbrains.exposed.dao.IntEntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.IntIdTable

object UsersTable : IntIdTable() {
  val snowflake = ulong("snowflake").index()
  val messageScore = integer("message_score").default(1)
  val timeScore = integer("time_score").default(0)
  val hasRegular = bool("has_regular").default(false)
}

class UserEntity(id: EntityID<Int>) : IntEntity(id) {
  companion object : IntEntityClass<UserEntity>(UsersTable)

  var snowflake by UsersTable.snowflake
  var messageScore by UsersTable.messageScore
  var timeScore by UsersTable.timeScore
  var hasRegular by UsersTable.hasRegular

  val qualifies: Boolean
    get() = timeScore >= ProgressManager.TIME_SCORE_THRESHOLD &&
      messageScore >= ProgressManager.MESSAGE_SCORE_THRESHOLD

}
