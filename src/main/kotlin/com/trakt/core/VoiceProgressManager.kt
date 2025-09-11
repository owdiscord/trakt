package com.trakt.core

import com.trakt.data.UserRepository
import dev.kord.core.Kord
import dev.kord.core.behavior.MemberBehavior
import dev.kord.core.behavior.RoleBehavior
import dev.kord.core.behavior.channel.MessageChannelBehavior
import dev.kord.core.event.user.VoiceStateUpdateEvent
import dev.kord.core.on
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.days
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock

class VoiceProgressManager(
    private val kord: Kord,
    private val repository: UserRepository,
    private val config: TraktConfig,
    private val scope: CoroutineScope
) {

  private val voiceSessions = ConcurrentHashMap<ULong, Long>()

  fun start() {
    kord.on<VoiceStateUpdateEvent> {
      val user = state.userId.value
      if (state.channelId != null) {
        voiceSessions.putIfAbsent(user, Clock.System.now().epochSeconds)
      } else {
        voiceSessions.remove(user)?.also {
          repository.addVoiceTime(user, Clock.System.now().epochSeconds - it)
        }
      }
    }
    scope.launch {
      while (isActive) {
        try {
          delay(1.days)
          val (added, removed) = repository.purgeOldVoiceSessions()
          grantAward(added)
          stripAward(removed)
        } catch (_: Exception) {}
      }
    }
  }

  private suspend fun grantAward(users: Collection<ULong>) {
    if (users.isEmpty()) {
      return
    }
    val guild = config.guild.snowflake
    val role = config.role.snowflake
    val roleName = RoleBehavior(guild, role, kord).asRole().name
    for (awardUser in users) {
      val messageSuffix =
          if (!config.trialMode) {
            MemberBehavior(guild, awardUser.snowflake, kord)
                .addRole(role, "Automatic $roleName award")
            ""
          } else {
            "(but not really)"
          }
      repository.commitVoiceAwardGrant(awardUser)
      MessageChannelBehavior(config.announceChannel.snowflake, kord)
          .createMessage("Granted <@$awardUser> Regular for voice activity. $messageSuffix")
    }
  }

  private suspend fun stripAward(users: Collection<ULong>) {
    if (users.isEmpty()) {
      return
    }
    val guild = config.guild.snowflake
    val role = config.role.snowflake
    val roleName = RoleBehavior(guild, role, kord).asRole().name
    for (awardUser in users) {
      val messageSuffix =
          if (!config.trialMode) {
            MemberBehavior(guild, awardUser.snowflake, kord)
                .removeRole(role, "Automatic $roleName strip")
            ""
          } else {
            "(but not really)"
          }
      MessageChannelBehavior(config.announceChannel.snowflake, kord)
          .createMessage(
              "Removed Regular from <@$awardUser> due to voice inactivity. $messageSuffix")
    }
  }
}
