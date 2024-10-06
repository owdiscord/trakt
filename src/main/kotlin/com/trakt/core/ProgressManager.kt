package com.trakt.core

import com.trakt.data.messageScoreForUser
import com.trakt.data.writeProgress
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.consume
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.Volatile
import kotlin.time.*
import kotlin.time.Duration.Companion.seconds

class ProgressManager {
  inner class Progress(val snowflake: ULong, messageScore: Int = 0) {
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
        return false
      }
      messageScore++
      lastCredit = now
      return true
    }
  }

  private val progressChannel = Channel<ULong>(Channel.UNLIMITED)
  @Volatile private var collecting: Boolean = false

  private val pendingProgress = object : ConcurrentHashMap<ULong, Progress>() {
    fun getOrCreate(key: ULong): Progress {
      return super.get(key) ?: Progress(key, messageScoreForUser(key)).also { super.put(key, it) }
    }
  }

  suspend fun startCollection(): ProgressManager {
    coroutineScope {
      collecting = true
      launch(Dispatchers.IO) {
        for (user in progressChannel) {
          pendingProgress.getOrCreate(user).credit()
        }
      }
      launch {
        while (true) {
          delay(PROGRESS_WRITE_PERIOD)
          reportAllProgress()
        }
      }
    }
    return this
  }

  fun submitProgress(user: ULong) {
    check(collecting)
    progressChannel.trySend(user)
  }

  private suspend fun reportAllProgress() {
    val now = timeSource.markNow()
    coroutineScope {
      launch(Dispatchers.IO) {
        writeProgress(pendingProgress.values)
        pendingProgress.entries.removeAll { now - it.value.lastCredit > PROGRESS_DELAY }
      }
    }
  }

  companion object {
    const val TIME_SCORE_THRESHOLD = 14
    const val MESSAGE_SCORE_THRESHOLD = 560
    private val PROGRESS_WRITE_PERIOD: Duration = 300.seconds
    private val PROGRESS_DELAY: Duration = 900.seconds
    private val timeSource = TimeSource.Monotonic
  }
}
