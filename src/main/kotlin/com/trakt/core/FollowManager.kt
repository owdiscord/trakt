package com.trakt.core

import com.trakt.data.UserRepository
import dev.kord.core.Kord
import dev.kord.core.behavior.MemberBehavior
import dev.kord.core.behavior.channel.MessageChannelBehavior
import dev.kord.core.behavior.channel.createMessage
import dev.kord.core.event.message.MessageCreateEvent
import dev.kord.rest.builder.message.addFile
import kotlin.time.ComparableTimeMark
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.nio.file.Path

class FollowManager(
    private val kord: Kord,
    private val repository: UserRepository,
    private val config: TraktConfig,
    private val scope: CoroutineScope,
) {

  private val timeSource = TimeSource.Monotonic
  private val activeFollows: MutableMap<ULong, MutableMap<ULong, Pair<Int, ComparableTimeMark>>> =
      mutableMapOf()

  fun start(): FollowManager {
    scope.launch {
      repository.loadTrackingInfo(::handleFollow)
      printLogging("Loaded ${activeFollows.size} follow entries.")
    }
    return this
  }

  suspend fun handleMessage(event: MessageCreateEvent) {
    val target = event.member?.id?.value ?: return
    val followsForUser = activeFollows[target] ?: return
    val now = timeSource.markNow()
    for (follow in followsForUser) {
      val timeout = follow.value.first
      if ((now - follow.value.second) < timeout.seconds) {
        continue
      }
      printLogging("Alerting for followed user")
      follow.setValue(Pair(timeout, now))
      alert(event, target, follow.key)
    }
  }

  fun handleFollow(owner: ULong, target: ULong, timeout: Int) {
    activeFollows.getOrPut(target) { mutableMapOf() }[owner] =
        Pair(timeout, timeSource.markNow() - timeout.seconds)
  }

  fun handleUnfollow(owner: ULong, target: ULong) {
    activeFollows[target]?.remove(owner)
  }

  private suspend fun alert(event: MessageCreateEvent, target: ULong, owner: ULong) {
    val id = event.message.id.value
    val guild = event.guildId?.value ?: return
    val channel = event.message.channel.id.value
    val url = "https://discord.com/channels/$guild/$channel/$id"
    val mention = "<@${owner}>"
    val username = MemberBehavior(config.guild.snowflake, target.snowflake, kord).asUser().username
    MessageChannelBehavior(config.followChannel.snowflake, kord)
        .createMessage("$mention Your follow for $username triggered here: $url")
    MessageChannelBehavior(config.followChannel.snowflake, kord).createMessage {
      addFile(Path.of(""))
    }
  }
}
