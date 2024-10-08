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
    private val config: TraktConfig
) {
  private val handlers =
      mutableMapOf<String, suspend (InteractionCommand) -> String?>(
          "view" to ::handleView, "edit" to ::handleEdit)

  suspend fun setupCommands() {
    kord.createGuildChatInputCommand(config.guild.snowflake, "trakt", "Interact with Trakt") {
      subCommand("view", "View user award progress") {
        string("score_type", "Score type to view") {
          required = true
          choice("Message score", "message_score")
          choice("Time score", "time_score")
        }
        string("snowflake", "User ID") { required = true }
      }
      subCommand("edit", "View user award progress") {
        string("score_type", "Score type to view") {
          required = true
          choice("Message score", "message_score")
          choice("Time score", "time_score")
        }
        string("snowflake", "User ID") { required = true }
        integer("override", "Override value") { required = true}
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
      println("command: $name")
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
    println("command snowflake is $snowflake")
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
    println("command snowflake is $snowflake")
    when (command.strings["score_type"]) {
      "message_score" -> progressManager.overrideMessageScore(snowflake, overrideValue)
      "time_score" -> userRepository.overrideTimeScore(snowflake, overrideValue)
      else -> return null
    }
    val username =
        MemberBehavior(config.guild.snowflake, snowflake.snowflake, kord).asUser().username
    return "Score for **$username** is now **$overrideValue**."
  }

  companion object {
    private const val ERROR_RESPONSE_CONTENT =
        "I couldn't complete this command. Please try again later."
    private const val USER_INTERACTION_MESSAGE_SCORE = "Show message score"
    private const val USER_INTERACTION_TIME_SCORE = "Show time score"
  }
}
