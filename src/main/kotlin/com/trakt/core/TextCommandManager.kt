package com.trakt.core

import dev.kord.common.entity.Snowflake
import dev.kord.core.Kord
import dev.kord.core.behavior.GuildBehavior
import dev.kord.core.behavior.channel.createMessage
import dev.kord.core.behavior.edit
import dev.kord.core.behavior.requestMembers
import dev.kord.core.event.message.MessageCreateEvent
import dev.kord.core.event.message.ReactionAddEvent
import dev.kord.core.on
import dev.kord.gateway.PrivilegedIntent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class TextCommandManager(
    private val kord: Kord,
    private val config: TraktConfig,
    private val scope: CoroutineScope
) {

  val handlers = mapOf("?strip" to ::strip)
  var reactionListener: Job? = null

  suspend fun processCommand(event: MessageCreateEvent) {
    var hasPerms = false
    for (role in event.member?.roleBehaviors ?: emptySet()) {
      if (role.id.value == config.massRoleRole) {
        hasPerms = true
        break
      }
    }
    if (!hasPerms) {
      return
    }

    val args = event.message.content.split(' ')
    handlers[args.first()]?.invoke(event, args.drop(1))
  }

  @OptIn(PrivilegedIntent::class)
  suspend fun strip(event: MessageCreateEvent, args: List<String>) {
    if (reactionListener?.isActive == true) {
      return
    }
    val stripRoles: MutableSet<ULong> = mutableSetOf()
    for (arg in args) {
      val maybeSnowflake = arg.toULongOrNull()
      if (maybeSnowflake == null) {
        event.message.channel.createMessage("Couldn't parse all arguments as Discord snowflakes.")
        return
      }
      stripRoles.add(maybeSnowflake)
    }
    val confirmationMessage =
        event.message.channel.createMessage(
            "You have requested to strip **${stripRoles.size}** roles from everyone in the server. This is a very " +
                "expensive operation. To confirm you want it, react \uD83C\uDD97 to this message.")
    val timeoutJob =
        scope.launch {
          delay(30000)
          reactionListener?.cancel()
          confirmationMessage.edit { content = "Confirmation timed out." }
        }
    reactionListener =
        kord.on<ReactionAddEvent> {
          if (event.member?.roleBehaviors?.any { it.id.value == config.massRoleRole } != true ||
              event.message.id != confirmationMessage.id) {
            return@on
          }
          timeoutJob.cancel()
          val chunkFlow =
              GuildBehavior(config.guild.snowflake, kord).fetchGuild().requestMembers {
                userIds =
                    config.massRoleTrialIds?.map { Snowflake(it) }?.toMutableSet() ?: mutableSetOf()
              }
          val statusMessage =
              event.message.channel.createMessage(
                  "Stripping ${stripRoles.size} roles. This may take a while.")
          scope.launch {
            chunkFlow.collect {
              printLogging("Processing chunk ${it.chunkIndex} of ${it.chunkCount} expected")
              for (member in it.members) {
                for (role in member.roleBehaviors) {
                  val snowflake = role.id
                  if (stripRoles.contains(snowflake.value)) {
                    member.removeRole(snowflake)
                  }
                }
              }
            }
            printLogging("Reporting completion")
            event.message.channel.createMessage {
              messageReference = statusMessage.id
              content = "Completed this strip operation. Yay!"
            }
          }
          reactionListener?.cancel()
        }
  }
}
