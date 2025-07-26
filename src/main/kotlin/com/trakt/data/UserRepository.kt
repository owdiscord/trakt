@file:OptIn(DelicateCoroutinesApi::class, ExperimentalCoroutinesApi::class)

package com.trakt.data

import com.trakt.core.ProgressManager
import com.trakt.core.TraktConfig
import com.trakt.core.printLogging
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.Transaction
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.transactions.transaction

class UserRepository(private val config: TraktConfig) {

  init {
    //    Database.connect("jdbc:h2:mem:test;DB_CLOSE_DELAY=-1")
    Database.connect("jdbc:sqlite:${System.getProperty("user.home")}/trakt-db/data.db")
    println(System.getProperty("user.dir"))
    transaction {
      if (SchemaUtils.listDatabases().isEmpty()) {
        SchemaUtils.createDatabase("trakt")
      }
      SchemaUtils.create(UsersTable)
      // sanity check people's message scores
      printLogging("Performing startup message score sanity check")
      UserEntity.find { UsersTable.messageScore greater (config.messageAwardThreshold + 10) }
          .forEach { it.messageScore = config.messageAwardThreshold }

      val now = Clock.System.now().epochSeconds
      UserEntity.find {
            ((UsersTable.isMuted eq true) or (UsersTable.isBanned eq true)) and
                UsersTable.sanctionTime.isNull()
          }
          .forEach { it.sanctionTime = now }

      // This bit doesn't have to be very precise. If they've been sanctioned for more than twice
      // the imposed delay, just bust them right back to the start regardless.
      UserEntity.find { UsersTable.sanctionTime.isNotNull() }
          .forEach { user ->
            user.sanctionTime?.also { sanctionTime ->
              val days = if (user.isMuted) config.muteDelayPeriods else config.banDelayPeriods
              if (now - sanctionTime > (days * 2 * 86400)) {
                user.delete()
              }
            }
          }
    }
  }

  class DecayResult(val inactiveAwardUsers: Set<ULong>, val totalUsers: Set<ULong>)

  /** Load this user's message score from the repository, or 0 if we don't know them. */
  suspend fun messageScoreForUser(snowflake: ULong): Long {
    return safeTransaction {
      UserEntity.find { UsersTable.snowflake eq snowflake }.firstOrNull()?.messageScore ?: 0
    }
  }

  /** Load this user's time score from the repository, or 0 if we don't know them. */
  suspend fun timeScoreForUser(snowflake: ULong): Long {
    return safeTransaction {
      UserEntity.find { UsersTable.snowflake eq snowflake }.firstOrNull()?.timeScore ?: 0
    }
  }

  private fun qualifies(user: UserEntity): Boolean {
    return user.messageScore >= config.messageAwardThreshold &&
        user.timeScore >= config.timeAwardThreshold
  }

  /**
   * Write the cached list of message score progress to DB. Return a list of users who now qualify
   * for award as a result of this change.
   */
  suspend fun writeMessageScore(userScores: Collection<ProgressManager.Progress>): List<ULong> {
    val result = ArrayList<ULong>()
    safeTransaction {
      for (userScore in userScores) {
        UserEntity.findSingleByAndUpdate(UsersTable.snowflake eq userScore.snowflake) {
          it.messageScore = userScore.messageScore
          if (!it.hasAward && qualifies(it)) {
            result.add(it.snowflake)
          }
        }
            ?: UserEntity.new {
                  snowflake = userScore.snowflake
                  messageScore = userScore.messageScore
                }
                .also {
                  // unlikely, but possible with certain configuration choices
                  if (qualifies(it)) {
                    result.add(userScore.snowflake)
                  }
                }
      }
    }
    return result
  }

  suspend fun userLeft(user: ULong) {
    safeTransaction {
      UserEntity.findSingleByAndUpdate(UsersTable.snowflake eq user) {
        it.hasAward = false
      }
    }
  }

  /**
   * Update all known users' time scores if they are below the threshold. Return a list of users who
   * now qualify for award as a result of this change.
   */
  suspend fun addTimeScore(): List<ULong> {
    val result = ArrayList<ULong>()
    safeTransaction {
      for (user in
          UserEntity.find {
            (UsersTable.hasAward eq false) and
                (UsersTable.timeScore less config.timeAwardThreshold) and
                (UsersTable.messageScore greater config.timeTrackingMessageThreshold)
          }) {
        user.timeScore++
        if (qualifies(user)) {
          result.add(user.snowflake)
        }
      }
    }
    return result
  }

  suspend fun overrideTimeScore(user: ULong, value: Long) {
    safeTransaction {
      UserEntity.findSingleByAndUpdate(UsersTable.snowflake eq user) { it.timeScore = value }
    }
  }

  suspend fun addWarn(user: ULong) {
    safeTransaction {
      UserEntity.findSingleByAndUpdate(UsersTable.snowflake eq user) {
        it.timeScore -= config.warnDelayPeriods
      }
    }
  }

  suspend fun updateMuteStatus(user: ULong, isMuted: Boolean) {
    safeTransaction {
      UserEntity.findSingleByAndUpdate(UsersTable.snowflake eq user) {
        it.isMuted = isMuted
        if (isMuted) {
          it.messageScore = 0
          it.timeScore = -config.muteDelayPeriods
          it.sanctionTime = Clock.System.now().epochSeconds
        } else {
          it.sanctionTime = null
        }
      }
    }
  }

  suspend fun updateBanStatus(user: ULong, isBanned: Boolean) {
    safeTransaction {
      UserEntity.findSingleByAndUpdate(UsersTable.snowflake eq user) {
        it.isBanned = isBanned
        if (isBanned) {
          it.messageScore = 0
          it.timeScore = -config.banDelayPeriods
          it.sanctionTime = Clock.System.now().epochSeconds
        } else {
          it.sanctionTime = null
        }
      }
    }
  }

  /**
   * Perform periodic decay of user message scores. Any users whose score would reach zero via this
   * mechanism are deleted from the database (unless they are muted or banned), and their snowflake
   * is added to the returned set so we can clear them from cache too.
   */
  suspend fun dockMessageScore(): DecayResult {
    val result = mutableSetOf<ULong>()
    val awardResult = mutableSetOf<ULong>()
    safeTransaction {
      for (user in UserEntity.all()) {
        if (user.isBanned || user.isMuted) {
          // Don't allow users to wipe their slate clean early by simply not talking. We've already
          // set their score to zero.
          continue
        }
        if (user.messageScore <= config.messageDecayMagnitude) {
          if (user.hasAward) {
            awardResult.add(user.snowflake)
          }
          result.add(user.snowflake)
          user.delete()
          continue
        }
        user.messageScore -= config.messageDecayMagnitude
      }
    }
    return DecayResult(awardResult, result)
  }

  suspend fun commitAwardGrant(user: ULong) {
    safeTransaction {
      UserEntity.findSingleByAndUpdate(UsersTable.snowflake eq user) { it.hasAward = true }
    }
  }

  suspend fun commitAwardStrip(user: ULong) {
    safeTransaction {
      UserEntity.findSingleByAndUpdate(UsersTable.snowflake eq user) { it.hasAward = false }
    }
  }

  private suspend fun <T> safeTransaction(db: Database? = null, statement: Transaction.() -> T): T {
    return withContext(dispatcher) { transaction(db, statement) }
  }

  companion object {
    private val dispatcher = newSingleThreadContext("repository")
  }
}
