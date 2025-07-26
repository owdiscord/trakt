package com.trakt.core

import dev.kord.common.entity.Snowflake
import dev.kord.core.Kord
import dev.kord.core.behavior.GuildBehavior
import dev.kord.core.behavior.channel.createMessage
import dev.kord.core.behavior.requestMembers
import dev.kord.core.event.message.MessageCreateEvent
import dev.kord.gateway.PrivilegedIntent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

class TextCommandManager(private val kord: Kord, private val config: TraktConfig, private val scope: CoroutineScope) {

  val stripRoles = config.massRoleStripRoles.toSet()
  val handlers = mapOf("?strip" to ::strip)

  suspend fun processCommand(event: MessageCreateEvent) {
    var hasPerms = false
    for (role in event.member?.roleBehaviors ?: emptySet()) {
      if (role.id.value == config.massRoleRole) {
        hasPerms = true
      }
    }
    if (!hasPerms) {
      return
    }

    handlers[event.message.content]?.invoke(event)
  }

  @OptIn(PrivilegedIntent::class)
  suspend fun strip(event: MessageCreateEvent) {
    val chunkFlow = GuildBehavior(config.guild.snowflake, kord).fetchGuild().requestMembers {
      userIds = config.massRoleTrialIds?.map { Snowflake(it) }?.toMutableSet() ?: mutableSetOf()
    }
    val statusMessage = event.message.channel.createMessage("Stripping roles. This may take a while.")
    scope.launch {
      chunkFlow.collect {
        for (member in it.members) {
          for (role in member.roleBehaviors) {
            val snowflake = role.id
            if (stripRoles.contains(snowflake.value)) {
              member.removeRole(snowflake)
            }
          }
        }
      }
      event.message.channel.createMessage {
        messageReference = statusMessage.id
        content = "Completed this strip operation. Yay!"
      }
    }
  }
}
