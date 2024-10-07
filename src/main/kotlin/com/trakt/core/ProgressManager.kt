package com.trakt.core

import com.trakt.data.UserRepository
import dev.kord.common.entity.Snowflake
import dev.kord.core.Kord
import dev.kord.core.behavior.MemberBehavior
import dev.kord.core.behavior.RoleBehavior
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.ComparableTimeMark
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel

class ProgressManager(
    private val kord: Kord,
    private val repository: UserRepository,
    private val config: TraktConfig
) {
  inner class Progress(val snowflake: ULong, messageScore: Int) {
    var messageScore: Int
      private set

    var lastCredit: ComparableTimeMark = timeSource.markNow()
      private set

    init {
      this.messageScore = messageScore
    }

    fun credit(): Boolean {
      val now = timeSource.markNow()
      if (now - lastCredit < PROGRESS_DELAY) {
        println("user $snowflake in timeout")
        return false
      }
      messageScore++
      lastCredit = now
      return true
    }

    fun decay() {
      messageScore -= MESSAGE_SCORE_DECAY
    }

    override fun toString(): String {
      return "User=$snowflake, message score=$messageScore, last credit time=$lastCredit"
    }
  }

  private val progressChannel = Channel<ULong>(Channel.UNLIMITED)
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

  private val pendingProgress = ConcurrentHashMap<ULong, Progress>()
  private lateinit var knownRegulars: Set<ULong>

  fun startCollection(): ProgressManager {
    scope.launch {
      knownRegulars = repository.loadAwardUsers()
      for (user in progressChannel) {
        if (user in knownRegulars) continue
        val progress = pendingProgress[user]
        if (progress != null) {
          progress.credit()
        } else {
          // if we didn't have them in memory they can't have been in timeout, so unconditionally
          // credit them
          pendingProgress[user] = Progress(user, repository.messageScoreForUser(user) + 1)
        }
      }
    }
    scope.launch {
      while (true) {
        delay(PROGRESS_WRITE_PERIOD)
        reportAllProgress()
      }
    }
    scope.launch {
      while (true) {
        delay(AWARD_CHECK_PERIOD)
        processAwards()
      }
    }
    scope.launch {
      while (true) {
        delay(DECAY_PERIOD)
        doDecay()
      }
    }
    return this
  }

  private suspend fun processAwards() {
    val newAwardUsers = repository.addTimeScore()
    val guild = config.guild.snowflake
    val role = config.role.snowflake
    val roleName = RoleBehavior(config.guild.snowflake, config.role.snowflake, kord).asRole().name
    for (awardUser in newAwardUsers) {
      MemberBehavior(guild, awardUser.snowflake, kord)
          .addRole(role, "Automatic $roleName award")
      repository.commitAwardGrant(awardUser)
    }
  }

  private fun doDecay() {
    val removedUsers = repository.dockMessageScore()
    pendingProgress.entries.removeAll { it.key in removedUsers }
    pendingProgress.values.forEach { it.decay() }
  }

  fun submitProgress(user: ULong) {
    println("attempting to credit user $user")
    progressChannel.trySend(user)
  }

  private fun reportAllProgress() {
    val now = timeSource.markNow()
    println("all cached user progress:")
    pendingProgress.values.forEach { println(it) }
    repository.writeProgress(pendingProgress.values)
    // no need to keep users in memory beyond PROGRESS_DELAY, since their next message is guaranteed
    // to credit
    pendingProgress.entries.removeAll { now - it.value.lastCredit > PROGRESS_DELAY }
  }

  companion object {
    const val TIME_SCORE_THRESHOLD = 14
    const val MESSAGE_SCORE_TIME_THRESHOLD = 280
    const val MESSAGE_SCORE_AWARD_THRESHOLD = 560
    const val MESSAGE_SCORE_DECAY = 28
    private val DECAY_PERIOD: Duration = 86400.seconds
    private val PROGRESS_WRITE_PERIOD: Duration = 10.seconds
    private val AWARD_CHECK_PERIOD: Duration = 10800.seconds
    private val PROGRESS_DELAY: Duration = 30.seconds
    private val timeSource = TimeSource.Monotonic
  }
}
