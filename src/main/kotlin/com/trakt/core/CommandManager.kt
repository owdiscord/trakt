package com.trakt.core

import com.trakt.data.UserRepository
import dev.kord.core.Kord
import dev.kord.core.behavior.MemberBehavior
import dev.kord.core.behavior.interaction.response.respond
import dev.kord.core.entity.interaction.GroupCommand
import dev.kord.core.entity.interaction.InteractionCommand
import dev.kord.core.entity.interaction.RootCommand
import dev.kord.core.entity.interaction.SubCommand
import dev.kord.core.event.interaction.GuildChatInputCommandInteractionCreateEvent
import dev.kord.core.event.interaction.GuildUserCommandInteractionCreateEvent
import dev.kord.core.on
import dev.kord.rest.builder.interaction.integer
import dev.kord.rest.builder.interaction.string
import dev.kord.rest.builder.interaction.subCommand

class CommandManager(
    private val kord: Kord,
    private val progressManager: ProgressManager,
    private val userRepository: UserRepository,
    private val config: TraktConfig,
) {
  private val handlers =
      mutableMapOf<String, suspend (InteractionCommand) -> String?>(
          "view" to ::handleView,
          "edit" to ::handleEdit,
          "follow" to ::handleFollow,
          "unfollow" to ::handleUnfollow,
      )

  suspend fun setupCommands() {
    kord.createGuildChatInputCommand(config.guild.snowflake, "trakt", "Interact with trakt") {
      subCommand("view", "View user award progress") {
        string("score_type", "Score type to view") {
          required = true
          choice("Message score", "message_score")
          choice("Time score", "time_score")
        }
        string("snowflake", "User ID") { required = true }
      }
      subCommand("edit", "Edit user award progress") {
        string("score_type", "Score type to edit") {
          required = true
          choice("Message score", "message_score")
          choice("Time score", "time_score")
        }
        string("snowflake", "User ID") { required = true }
        integer("override", "Override value") { required = true }
      }
      subCommand("follow", "Be notified whenever a user posts") {
        string("snowflake", "User ID to follow") { required = true }
        string(
            "timeout",
            "Minimum duration, in seconds, between alerts for this follow (default 300)",
        )
      }
      subCommand("unfollow", "Remove a follow rule") {
        string("snowflake", "User ID to unfollow") { required = true }
      }
    }

    kord.on<GuildChatInputCommandInteractionCreateEvent> {
      val response = interaction.deferEphemeralResponse()
      val command = interaction.command
      val name =
          when (command) {
            is SubCommand -> command.name
            is GroupCommand -> command.groupName
            is RootCommand -> command.rootName
          }
      val responseContent = handlers[name]?.invoke(command) ?: ERROR_RESPONSE_CONTENT
      response.respond { content = responseContent }
    }

    kord.createGuildUserCommand(config.guild.snowflake, USER_INTERACTION_MESSAGE_SCORE)
    kord.createGuildUserCommand(config.guild.snowflake, USER_INTERACTION_TIME_SCORE)

    kord.on<GuildUserCommandInteractionCreateEvent> {
      val response = interaction.deferEphemeralResponse()
      val user = interaction.target.asUser()
      val snowflake = user.id.value
      val name = user.username
      val score =
          when (interaction.invokedCommandName) {
            USER_INTERACTION_MESSAGE_SCORE -> {
              progressManager.messageScoreForUser(snowflake)?.toString()
                  ?: userRepository.messageScoreForUser(snowflake).toString()
            }
            USER_INTERACTION_TIME_SCORE -> {
              userRepository.timeScoreForUser(snowflake).toString()
            }
            else -> return@on
          }
      response.respond { content = "Score for **$name** is **$score**" }
    }
  }

  private suspend fun handleView(command: InteractionCommand): String? {
    val snowflake = command.strings["snowflake"]?.toULong() ?: return null
    val score =
        when (command.strings["score_type"]) {
          "message_score" ->
              progressManager.messageScoreForUser(snowflake)?.toString()
                  ?: userRepository.messageScoreForUser(snowflake).toString()
          "time_score" -> userRepository.timeScoreForUser(snowflake).toString()
          else -> return null
        }
    val username =
        MemberBehavior(config.guild.snowflake, snowflake.snowflake, kord).asUser().username
    return "Score for **$username** is **$score**."
  }

  private suspend fun handleEdit(command: InteractionCommand): String? {
    val snowflake = command.strings["snowflake"]?.toULong() ?: return null
    val overrideValue = command.integers["override"] ?: return null
    when (command.strings["score_type"]) {
      "message_score" -> progressManager.overrideMessageScore(snowflake, overrideValue)
      "time_score" -> userRepository.overrideTimeScore(snowflake, overrideValue)
      else -> return null
    }
    val username =
        MemberBehavior(config.guild.snowflake, snowflake.snowflake, kord).asUser().username
    return "Score for **$username** is now **$overrideValue**."
  }

  private suspend fun handleFollow(command: InteractionCommand): String? {
    val invoker = command.data.id.value?.value ?: return null
    val snowflake =
        command.strings["snowflake"]?.toULongOrNull() ?: return "Invalid Discord ID, you doofus."
    val timeout = command.strings["seconds"]?.toIntOrNull() ?: 300
    userRepository.addTracking(invoker, snowflake, timeout)
    val username =
        MemberBehavior(config.guild.snowflake, snowflake.snowflake, kord).asUser().username
    val timeoutCommentary =
        if (timeout != 0) {
          "alerting at most every $timeout seconds."
        } else {
          "alerting on every message."
        }
    return "You are now following $username, $timeoutCommentary."
  }

  private suspend fun handleUnfollow(command: InteractionCommand): String? {
    val invoker = command.data.id.value?.value ?: return null
    val snowflake =
        command.strings["snowflake"]?.toULongOrNull() ?: return "Invalid Discord ID, you doofus."
    val username =
        MemberBehavior(config.guild.snowflake, snowflake.snowflake, kord).asUser().username
    return if (userRepository.removeTracking(invoker, snowflake)) {
      "Removed your follow for **$username**."
    } else {
      "You weren't following **$username**."
    }
  }

  companion object {
    private const val ERROR_RESPONSE_CONTENT =
        "I couldn't complete this command. Please try again later."
    private const val USER_INTERACTION_MESSAGE_SCORE = "Show message score"
    private const val USER_INTERACTION_TIME_SCORE = "Show time score"
  }
}
