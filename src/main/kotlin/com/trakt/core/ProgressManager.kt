package com.trakt.core

import com.trakt.data.UserRepository
import dev.kord.core.Kord
import dev.kord.core.behavior.MemberBehavior
import dev.kord.core.behavior.RoleBehavior
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.ComparableTimeMark
import kotlin.time.TimeSource

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
      if (now - lastCredit < config.messageTimeout) {
        return false
      }
      if (messageScore < config.messageAwardThreshold) {
        messageScore++
      }
      lastCredit = now
      return true
    }

    fun decay() {
      messageScore -= config.messageDecayMagnitude
    }
  }

  private val progressChannel = Channel<ULong>(Channel.UNLIMITED)
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

  private val cachedProgress = ConcurrentHashMap<ULong, Progress>()

  fun startCollection(): ProgressManager {
    scope.launch {
      for (user in progressChannel) {
        val progress = cachedProgress[user]
        if (progress != null) {
          progress.credit()
        } else {
          // if we didn't have them in memory they can't have been in timeout, so unconditionally
          // credit them
          cachedProgress[user] = Progress(user, repository.messageScoreForUser(user) + 1)
        }
      }
    }
    scope.launch {
      while (true) {
        delay(config.progressSavePeriod)
        saveProgress()
      }
    }
    scope.launch {
      while (true) {
        delay(config.timeScorePeriod)
        timeScoreTick()
      }
    }
    scope.launch {
      while (true) {
        delay(config.messageDecayPeriod)
        doDecay()
      }
    }
    return this
  }

  private suspend fun grantAward(users: Collection<ULong>) {
    val guild = config.guild.snowflake
    val role = config.role.snowflake
    val roleName = RoleBehavior(guild, role, kord).asRole().name
    for (awardUser in users) {
      MemberBehavior(guild, awardUser.snowflake, kord).addRole(role, "Automatic $roleName award")
      println("granted $awardUser $roleName")
      repository.commitAwardGrant(awardUser)
    }
  }

  private suspend fun stripAward(users: Collection<ULong>) {
    val guild = config.guild.snowflake
    val role = config.role.snowflake
    val roleName = RoleBehavior(guild, role, kord).asRole().name
    for (awardUser in users) {
      MemberBehavior(guild, awardUser.snowflake, kord).removeRole(role, "Automatic $roleName strip")
      repository.commitAwardStrip(awardUser)
    }
  }

  private suspend fun timeScoreTick() {
    println("running time tick")
    grantAward(repository.addTimeScore())
  }

  private suspend fun doDecay() {
    val decayResult = repository.dockMessageScore()
    stripAward(decayResult.inactiveAwardUsers)
    cachedProgress.entries.removeAll { it.key in decayResult.totalUsers }
    cachedProgress.values.forEach { it.decay() }
  }

  fun submitProgress(user: ULong) {
    progressChannel.trySend(user)
  }

  /** Write our cache of progress to disk and award regular to users who now qualify. */
  private suspend fun saveProgress() {
    val now = timeSource.markNow()
    println("all cached user progress:")
    cachedProgress.values.forEach { println(it) }
    grantAward(repository.writeMessageScore(cachedProgress.values))
    // no need to keep users in memory beyond messageTimeout, since their next message is guaranteed
    // to credit
    cachedProgress.entries.removeAll { now - it.value.lastCredit > config.messageTimeout }
  }

  companion object {
    private val timeSource = TimeSource.Monotonic
  }
}
