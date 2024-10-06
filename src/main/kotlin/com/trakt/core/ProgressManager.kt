package com.trakt.core

import com.trakt.data.UserRepository
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.Volatile
import kotlin.time.ComparableTimeMark
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel

class ProgressManager(private val repository: UserRepository) {
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

    override fun toString(): String {
      return "User=$snowflake, message score=$messageScore, last credit time=$lastCredit"
    }
  }

  private val progressChannel = Channel<ULong>(Channel.UNLIMITED)
  @Volatile private var collecting: Boolean = false
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

  private val pendingProgress = ConcurrentHashMap<ULong, Progress>()

  fun startCollection(): ProgressManager {
    collecting = true
    scope.launch {
      for (user in progressChannel) {
        val progress = pendingProgress[user]
        if (progress != null) {
          progress.credit()
        } else {
          // if we didn't have them in memory they can't have been in timeout, so unconditionally credit them
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
    return this
  }

  fun submitProgress(user: ULong) {
    check(collecting)
    println("attempting to credit user $user")
    progressChannel.trySend(user)
  }

  private fun reportAllProgress() {
    val now = timeSource.markNow()
    println("all cached user progress:")
    pendingProgress.values.forEach { println(it) }
    repository.writeProgress(pendingProgress.values)
    // no need to keep users in memory beyond PROGRESS_DELAY, since their next message is guaranteed to credit
    pendingProgress.entries.removeAll { now - it.value.lastCredit > PROGRESS_DELAY }
  }

  companion object {
    const val TIME_SCORE_THRESHOLD = 14
    const val MESSAGE_SCORE_THRESHOLD = 560
    private val PROGRESS_WRITE_PERIOD: Duration = 10.seconds
    private val PROGRESS_DELAY: Duration = 30.seconds
    private val timeSource = TimeSource.Monotonic
  }
}
