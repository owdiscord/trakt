package com.trakt.data

import com.trakt.core.ProgressManager
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.transactions.transaction

class UserRepository {

  init {
    Database.connect("jdbc:h2:mem:test;DB_CLOSE_DELAY=-1")
    transaction { SchemaUtils.create(UsersTable) }
  }

  /** Load this user's message score from the repository, or 0 if we don't know them. */
  fun messageScoreForUser(snowflake: ULong): Int {
    return transaction {
      UserEntity.find { UsersTable.snowflake eq snowflake }.firstOrNull()?.messageScore ?: 0
    }
  }

  /**
   * Write the cached list of message score progress to DB. Return a list of users who now qualify
   * for award as a result of this change.
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
        }
            ?: UserEntity.new {
              snowflake = userScore.snowflake
              messageScore = userScore.messageScore
            }
      }
    }
    return result
  }

  /**
   * Update all known users' time scores if they are below the threshold. Return a list of users who
   * now qualify for award as a result of this change.
   */
  fun addTimeScore(): List<ULong> {
    val result = ArrayList<ULong>()
    transaction {
      for (user in
          UserEntity.find {
            (UsersTable.timeScore less ProgressManager.TIME_SCORE_THRESHOLD) and
                (UsersTable.messageScore greater ProgressManager.MESSAGE_SCORE_TIME_THRESHOLD)
          }) {
        user.timeScore++
        if (user.qualifies) {
          result.add(user.snowflake)
        }
      }
    }
    return result
  }

  /**
   * Perform periodic decay of user message scores. Any users whose score would reach zero via this
   * mechanism are deleted from the database, and their snowflake is added to the returned set.
   */
  fun dockMessageScore(): Set<ULong> {
    val result = mutableSetOf<ULong>()
    transaction {
      for (user in UserEntity.all()) {
        if (user.messageScore <= ProgressManager.MESSAGE_SCORE_DECAY) {
          result.add(user.snowflake)
          user.delete()
          continue
        }
        user.messageScore -= ProgressManager.MESSAGE_SCORE_DECAY
      }
    }
    return result
  }

  fun commitAwardGrant(user: ULong) {
    transaction {
      UserEntity.findSingleByAndUpdate(UsersTable.snowflake eq user) { it.hasAward = true }
    }
  }

  fun loadAwardUsers(): Set<ULong> {
    val result = mutableSetOf<ULong>()
    for (user in UserEntity.find { UsersTable.hasAward eq true }) {
      result.add(user.snowflake)
    }
    return result
  }
}
