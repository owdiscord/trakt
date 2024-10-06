package com.trakt.data

import com.trakt.core.ProgressManager
import org.jetbrains.exposed.dao.IntEntity
import org.jetbrains.exposed.dao.IntEntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.IntIdTable
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.kotlin.datetime.CurrentDateTime
import org.jetbrains.exposed.sql.kotlin.datetime.datetime
import org.jetbrains.exposed.sql.transactions.transaction

object Users : IntIdTable() {
  val snowflake = ulong("snowflake").index()
  val messageScore = integer("message_score").default(1)
  val messageScoreTime = datetime("message_score_time").defaultExpression(CurrentDateTime)
  val timeScore = integer("time_score").default(0)
  val hasRegular = bool("has_regular").default(false)
}

class User(id: EntityID<Int>) : IntEntity(id) {
  companion object : IntEntityClass<User>(Users)

  var snowflake by Users.snowflake
  var messageScore by Users.messageScore
  var messageScoreTime by Users.messageScoreTime
  var timeScore by Users.timeScore
  var hasRegular by Users.hasRegular

  val qualifies = timeScore >= ProgressManager.TIME_SCORE_THRESHOLD &&
      messageScore >= ProgressManager.MESSAGE_SCORE_THRESHOLD

}

fun initUserRepository() {
  Database.connect("jdbc:h2:mem:test", driver = "org.h2.Driver", user = "root")

  transaction { SchemaUtils.create(Users) }
}

fun addUser(snowflake: ULong) {
  transaction { User.new { this.snowflake = snowflake } }
}

fun messageScoreForUser(snowflake: ULong): Int {
  return User.find { Users.snowflake eq snowflake }.firstOrNull()?.messageScore ?: 0
}

/**
 * Write the cached list of message score progress to DB. Return a list of users who now qualify for award as a result
 * of this change.
 */
fun writeProgress(userScores: Collection<ProgressManager.Progress>): List<ULong> {
  val result = ArrayList<ULong>()
  transaction {
    for (userScore in userScores) {
      User.findSingleByAndUpdate(Users.snowflake eq userScore.snowflake) {
        it.messageScore = userScore.messageScore
        if (it.qualifies) {
          result.add(it.snowflake)
        }
      }
    }
  }
  return result
}

/**
 * Update all known users' time scores if they are below the threshold. Return a list of users who now qualify for award
 * as a result of this change.
 */
fun addTimeScore(): List<ULong> {
  val result = ArrayList<ULong>()
  transaction {
    for (user in User.find { Users.timeScore less ProgressManager.TIME_SCORE_THRESHOLD }) {
      user.timeScore++
      if (user.qualifies) {
        result.add(user.snowflake)
      }
    }
  }
  return result
}
