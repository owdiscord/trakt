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
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.todayIn
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.Transaction
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.upsert

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
      SchemaUtils.create(VoiceSessionTable)
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
      UserEntity.findSingleByAndUpdate(UsersTable.snowflake eq user) { it.hasAward = false }
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
    var longSanctionUsers = 0
    var expiredUsers = 0
    safeTransaction {
      for (user in UserEntity.all()) {
        val sanctionTime = user.sanctionTime
        if (sanctionTime != null) {
          // This bit doesn't have to be very precise. If they've been sanctioned for more than
          // twice
          // the imposed delay, just bust them right back to the start regardless.
          val now = Clock.System.now().epochSeconds
          val days = if (user.isMuted) config.muteDelayPeriods else config.banDelayPeriods
          if (now - sanctionTime > (days * 2 * 86400)) {
            user.delete()
            longSanctionUsers++
          }

          // Don't allow users to wipe their slate clean early by simply not talking. If
          // sanction_time is set,
          // this user has been naughty and must wait out their punishment.
          continue
        }
        if (user.messageScore <= config.messageDecayMagnitude) {
          if (user.hasAward) {
            awardResult.add(user.snowflake)
          }
          result.add(user.snowflake)
          user.delete()
          expiredUsers++
          continue
        }
        user.messageScore -= config.messageDecayMagnitude
      }
    }
    printLogging(
        "Activity decay done. Deleted $longSanctionUsers long sanction users." +
            " Deleted $expiredUsers expired users.")
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

  suspend fun addVoiceTime(user: ULong, duration: Long) {
    safeTransaction {
      VoiceSessionEntity.findSingleByAndUpdate(
          (VoiceSessionTable.snowflake eq user) and
              (VoiceSessionTable.sessionDate eq Clock.System.todayIn(TimeZone.UTC))) {
            it.sessionDuration += duration
          }
          ?: VoiceSessionEntity.new {
            snowflake = user
            sessionDuration = duration
            sessionDate = Clock.System.todayIn(TimeZone.UTC)
          }
      VoiceSummaryEntity.findSingleByAndUpdate(VoiceSummaryTable.snowflake eq user) {
        it.weekTotal += duration
        it.monthTotal += duration
      }
    }
  }

  /**
   * Do a daily tick of voice data. The following things happen:
   * - Delete all session data greater than 30 days old.
   * - Read all session data. Sum their durations (`monthDurations`).
   * - Read all session data within the past week. Sum their durations (`weekDurations`).
   * - Apply appropriate durations to users as their month total and week total.
   * - Find users who now qualify for award.
   * - Find users who should now have award taken (month total less than half the threshold).
   * - Return two lists: users who gained and users who lost as a result.
   */
  suspend fun doVoiceTick(): Pair<List<ULong>, List<ULong>> {
    val today = Clock.System.todayIn(TimeZone.UTC)
    val weekCutoff = today - DatePeriod(days = 7)
    val monthCutoff = today - DatePeriod(days = 30)
    val monthDurations = mutableMapOf<ULong, Long>()
    val weekDurations = mutableMapOf<ULong, Long>()
    val addedResult = mutableListOf<ULong>()
    val removedResult = mutableListOf<ULong>()
    safeTransaction {
      printLogging("Removing old voice sessions")
      VoiceSessionEntity.find { VoiceSessionTable.sessionDate less monthCutoff }
          .forEach { it.delete() }

      printLogging("Collecting month totals")
      VoiceSessionEntity.all().forEach {
        monthDurations[it.snowflake] = it.sessionDuration + (monthDurations[it.snowflake] ?: 0)
      }

      printLogging("Collecting week totals")
      VoiceSessionEntity.find { VoiceSessionTable.sessionDate greaterEq weekCutoff }
          .forEach {
            weekDurations[it.snowflake] = it.sessionDuration + (weekDurations[it.snowflake] ?: 0)
          }

      VoiceSummaryTable.upsert {
      }
      printLogging("Applying totals")
      for ((user, monthTotal) in monthDurations) {
        VoiceSummaryEntity.findSingleByAndUpdate(VoiceSessionTable.snowflake eq user) {
          it.monthTotal = monthTotal
          it.weekTotal = weekDurations[user] ?: 0
        } ?: VoiceSummaryEntity.new {
          snowflake = user
          this.monthTotal = monthTotal
          this.weekTotal = weekDurations[user] ?: 0
        }
      }

      printLogging("Finding qualified users")
      VoiceSummaryEntity.find {
            (VoiceSummaryTable.hasAward eq false) and
                (VoiceSummaryTable.weekTotal greaterEq config.voiceWeekThreshold.inWholeSeconds) and
                (VoiceSummaryTable.monthTotal greaterEq config.voiceMonthThreshold.inWholeSeconds)
          }
          .forEach { addedResult.add(it.snowflake) }

      VoiceSummaryEntity.find {
            (VoiceSummaryTable.hasAward eq true) and
                (VoiceSummaryTable.weekTotal less (config.voiceWeekThreshold.inWholeSeconds / 2))
          }
          .forEach { removedResult.add(it.snowflake) }
    }
    return Pair(addedResult, removedResult)
  }

  suspend fun commitVoiceAwardGrant(user: ULong) {
    safeTransaction {
      VoiceSummaryEntity.findSingleByAndUpdate(VoiceSummaryTable.snowflake eq user) {
        it.hasAward = true
      }
    }
  }

  suspend fun commitVoiceAwardStrip(user: ULong) {
    safeTransaction {
      VoiceSummaryEntity.findSingleByAndUpdate(VoiceSummaryTable.snowflake eq user) {
        it.hasAward = false
      }
    }
  }

  private suspend fun <T> safeTransaction(db: Database? = null, statement: Transaction.() -> T): T {
    return withContext(dispatcher) { transaction(db, statement) }
  }

  companion object {
    private val dispatcher = newSingleThreadContext("repository")
  }
}
