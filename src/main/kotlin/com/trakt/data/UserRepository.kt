package com.trakt.data

import com.trakt.core.ProgressManager
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction

class UserRepository {
  private lateinit var db: Database
  fun initUserRepository() {
    db = Database.connect("jdbc:h2:mem:test", driver = "org.h2.Driver", user = "root")

    transaction { SchemaUtils.create(UsersTable) }
  }

  fun messageScoreForUser(snowflake: ULong): Int {
    return UserEntity.find { UsersTable.snowflake eq snowflake }.firstOrNull()?.messageScore ?: 0
  }

  /**
   * Write the cached list of message score progress to DB. Return a list of users who now qualify for award as a result
   * of this change.
   */
  fun writeProgress(userScores: Collection<ProgressManager.Progress>): List<ULong> {
    val result = ArrayList<ULong>()
    transaction {
      for (userScore in userScores) {
        UserEntity.findSingleByAndUpdate(UsersTable.snowflake eq userScore.snowflake) {
          it.messageScore = userScore.messageScore
          if (it.qualifies) {
            result.add(it.snowflake)
          }
        } ?: UserEntity.new {
          snowflake = userScore.snowflake
          messageScore = userScore.messageScore
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
      for (user in UserEntity.find { UsersTable.timeScore less ProgressManager.TIME_SCORE_THRESHOLD }) {
        user.timeScore++
        if (user.qualifies) {
          result.add(user.snowflake)
        }
      }
    }
    return result
  }
}
