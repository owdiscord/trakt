package com.trakt.core

import com.trakt.data.UserRepository
import dev.kord.core.Kord
import dev.kord.core.behavior.MemberBehavior
import dev.kord.core.behavior.channel.MessageChannelBehavior
import dev.kord.core.event.message.MessageCreateEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.time.ComparableTimeMark
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

class FollowManager(
  private val kord: Kord,
  private val repository: UserRepository,
  private val config: TraktConfig,
  private val scope: CoroutineScope
) {

  private val timeSource = TimeSource.Monotonic
  private data class FollowTrigger(val owner: ULong, val timeout: Int, var triggerTime: ComparableTimeMark)

  private val activeFollows: MutableMap<ULong, MutableSet<FollowTrigger>> = mutableMapOf()

  private fun loadCb(target: ULong, owner: ULong, timeout: Int) {
    activeFollows.getOrPut(target) { mutableSetOf() }.add(FollowTrigger(owner, timeout, timeSource.markNow()))
  }

  fun start(): FollowManager {
    scope.launch {
      repository.loadTrackingInfo(::loadCb)
      printLogging("Loaded ${activeFollows.size} follow entries.")
    }
    return this
  }

  suspend fun handleMessage(event: MessageCreateEvent) {
    val target = event.member?.id?.value ?: return
    val followsForUser = activeFollows[target] ?: return
    val now = timeSource.markNow()
    for (follow in followsForUser) {
      if ((now - follow.triggerTime) < follow.timeout.seconds) {
        continue
      }
      printLogging("Alerting for followed user")
      follow.triggerTime = now
      alert(event, target, follow)
    }
  }

  private suspend fun alert(event: MessageCreateEvent, target: ULong, follow: FollowTrigger) {
    val id = event.message.id.value
    val guild = event.guildId?.value ?: return
    val channel = event.message.channel.id.value
    val url = "https://discord.com/channels/$guild/$channel/$id"
    val mention = "<@${follow.owner}>"
    val username = MemberBehavior(config.guild.snowflake, target.snowflake, kord).asUser().username
    MessageChannelBehavior(config.followChannel.snowflake, kord).createMessage(
      "$mention Your follow for $username triggered here: $url"
    )
  }
}
