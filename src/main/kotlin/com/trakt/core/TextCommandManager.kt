package com.trakt.core

import dev.kord.common.annotation.KordPreview
import dev.kord.core.Kord
import dev.kord.core.behavior.GuildBehavior
import dev.kord.core.behavior.channel.createMessage
import dev.kord.core.behavior.edit
import dev.kord.core.behavior.requestMembers
import dev.kord.core.event.message.MessageCreateEvent
import dev.kord.core.event.message.ReactionAddEvent
import dev.kord.core.live.live
import dev.kord.core.live.on
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
  var stripJob: Job? = null

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

  @OptIn(PrivilegedIntent::class, KordPreview::class)
  suspend fun strip(event: MessageCreateEvent, args: List<String>) {
    if (stripJob?.isActive == true) {
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
    if (stripRoles.isEmpty()) {
      return
    }
    val confirmationMessage =
        event.message.channel.createMessage(
            "You have requested to strip **${stripRoles.size}** roles from everyone in the server. This is a very " +
                "expensive operation. To confirm you want it, react \uD83C\uDD97 to this message.")
    printLogging("Adding confirmation message with id ${confirmationMessage.id.value}")
    var reactionListener: Job? = null
    val timeoutJob =
      scope.launch {
        delay(30000)
        reactionListener?.cancel()
        confirmationMessage.edit { content = "Confirmation timed out." }
      }
    reactionListener =
        confirmationMessage.live().on<ReactionAddEvent> { confirmReaction ->
          if (confirmReaction.userAsMember?.asMember()?.roleBehaviors?.any { it.id.value == config.massRoleRole } !=
              true || confirmReaction.messageId != confirmationMessage.id || confirmReaction.emoji.name != "🆗") {
            return@on
          }
          timeoutJob.cancel()
          val chunkFlow =
              GuildBehavior(config.guild.snowflake, kord).fetchGuild().requestMembers {
                requestAllMembers()
              }
          var includeMention = false
          val statusMessage =
              event.message.channel
                  .createMessage(
                      "Stripping ${stripRoles.size} roles. This may take a while. React ⏰ to be mentioned on completion.")
          var notifyJob: Job? = null
          notifyJob = statusMessage.live().on<ReactionAddEvent> { notifyReaction ->
            if (notifyReaction.emoji.name != "⏰" || event.member?.id != confirmReaction.userAsMember?.id) {
              return@on
            }
            includeMention = true
            notifyJob?.cancel()
          }
          stripJob = scope.launch {
            var announcedChunkCount = false
            chunkFlow.collect {
              if (!announcedChunkCount) {
                statusMessage.edit {
                  content += " The server expects that this will take about ${it.chunkCount / 4} mins (very roughly)."
                }
                announcedChunkCount = true
              }
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
            event.message.channel.createMessage {
              messageReference = statusMessage.id
              content = "Completed this strip operation. Yay!"
              if (includeMention) {
                event.member?.id?.also { content += " <@$it.>" }
              }
            }
          }
          reactionListener?.cancel()
        }
  }
}
