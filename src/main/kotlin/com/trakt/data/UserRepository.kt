@file:OptIn(DelicateCoroutinesApi::class, ExperimentalCoroutinesApi::class)

package com.trakt.data

import com.trakt.core.ProgressManager
import com.trakt.core.TraktConfig
import com.trakt.core.printLogging
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.todayIn
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.upsert

class UserRepository(config: TraktConfig) : Repository(config) {

  init {
    Database.connect("jdbc:sqlite:${System.getProperty("user.home")}/trakt-db/data.db")
    println(System.getProperty("user.dir"))
    transaction {
      if (SchemaUtils.listDatabases().isEmpty()) {
        SchemaUtils.createDatabase("trakt")
      }
      SchemaUtils.create(UsersTable)
      SchemaUtils.create(VoiceSessionTable)
      SchemaUtils.create(MessageTrackingTable)
      // sanity check people's message scores
      printLogging("Performing startup message score sanity check")
      UserEntity.find { UsersTable.messageScore greater (config.messageAwardThreshold + 10) }
          .forEach { it.messageScore = config.messageAwardThreshold }

      UserEntity.find { UsersTable.isMuted eq true }
          .forEach {
            it.isMuted = false
            it.messageScore = 0
            it.timeScore = -config.muteDelayPeriods
          }

      UserEntity.find { UsersTable.isBanned eq true }
          .forEach {
            it.isBanned = false
            it.messageScore = 0
            it.timeScore = -config.banDelayPeriods
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

  suspend fun commitMute(user: ULong) {
    safeTransaction {
      UserEntity.findSingleByAndUpdate(UsersTable.snowflake eq user) {
        it.messageScore = 0
        it.timeScore = -config.muteDelayPeriods
      }
    }
  }

  suspend fun commitBan(user: ULong) {
    safeTransaction {
      UserEntity.findSingleByAndUpdate(UsersTable.snowflake eq user) {
        it.messageScore = 0
        it.timeScore = -config.banDelayPeriods
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
    var expiredUsers = 0
    safeTransaction {
      for (user in UserEntity.all()) {
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
    printLogging("Activity decay done. Deleted $expiredUsers expired users.")
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

  @OptIn(ExperimentalTime::class)
  suspend fun addVoiceTime(user: ULong, duration: Long) {
    safeTransaction {
      VoiceSessionEntity.findSingleByAndUpdate(
          (VoiceSessionTable.snowflake eq user) and
              (VoiceSessionTable.sessionDate eq Clock.System.todayIn(TimeZone.UTC))
      ) {
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
  @OptIn(ExperimentalTime::class)
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

      VoiceSummaryTable.upsert {}
      printLogging("Applying totals")
      for ((user, monthTotal) in monthDurations) {
        VoiceSummaryEntity.findSingleByAndUpdate(VoiceSessionTable.snowflake eq user) {
          it.monthTotal = monthTotal
          it.weekTotal = weekDurations[user] ?: 0
        }
            ?: VoiceSummaryEntity.new {
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

  suspend fun addTracking(owner: ULong, target: ULong, timeout: Int) {
    safeTransaction {
      MessageTrackingEntity.findSingleByAndUpdate(
          (MessageTrackingTable.owner eq owner) and (MessageTrackingTable.target eq target)
      ) {
        it.timeout = timeout
      }
          ?: MessageTrackingEntity.new {
            this.owner = owner
            this.target = target
            this.timeout = timeout
          }
    }
  }

  suspend fun resetUser(user: ULong): Boolean {
    return safeTransaction { UsersTable.deleteWhere { UsersTable.snowflake eq user } != 0 }
  }

  suspend fun getUserForReport(user: ULong): UserEntity? {
    return safeTransaction { UserEntity.find(UsersTable.snowflake eq user).firstOrNull() }
  }

  /** Return true if we removed a row */
  suspend fun removeTracking(owner: ULong, target: ULong): Boolean {
    return safeTransaction {
      MessageTrackingTable.deleteWhere {
        (MessageTrackingTable.owner eq owner) and (MessageTrackingTable.target eq target)
      } != 0
    }
  }

  suspend fun showTracking(owner: ULong): List<ULong> {
    return safeTransaction {
      MessageTrackingEntity.find { MessageTrackingTable.owner eq owner }.map { it.target }
    }
  }

  suspend fun loadTrackingInfo(loadCb: (ULong, ULong, Int) -> Unit) {
    return safeTransaction {
      MessageTrackingEntity.all().forEach { loadCb(it.owner, it.target, it.timeout) }
    }
  }
}
