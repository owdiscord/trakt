package com.trakt.core

import com.trakt.data.UserRepository
import dev.kord.core.Kord
import dev.kord.core.event.guild.BanAddEvent
import dev.kord.core.event.guild.MemberJoinEvent
import dev.kord.core.event.message.MessageCreateEvent
import dev.kord.core.on
import dev.kord.gateway.Intent
import dev.kord.gateway.PrivilegedIntent

suspend fun main(args: Array<String>) {
  println("Hello World!")
  println("Program arguments: ${args.joinToString()}")
  if (args.isEmpty()) {
    System.err.println("Not enough arguments")
    return
  }

  val config = TraktConfig.readConfig(args[0])
  val bot = Kord(config.token)

  println("created bot")
  val userRepository = UserRepository(config)
  val progressManager = ProgressManager(bot, userRepository, config).startCollection()
  val commandManager = CommandManager(bot, progressManager, userRepository, config)

  println("collection started")
  bot.on<MessageCreateEvent> {
    if (guildId?.value != config.guild) return@on
//    if (member?.isBot == true) return@on
    if (message.channelId.value == 170179130694828032UL && message.webhookId != null) {
      // dealing with a new Zeppelin action
      println("action content:\n${message.content}")
      for (embed in message.embeds) {
        println("action embed:\n${embed.title}")
        for (field in embed.fields) {
          println("action embed field:\n${field.name} // ${field.value}")
        }
      }
      return@on
    }
//    member?.id?.also { progressManager.submitProgress(it.value) }
  }

  bot.on<BanAddEvent> {
    getBanOrNull()?.userId?.value?.also {
      userRepository.updateBanStatus(it, true)
    }
  }

  bot.on<MemberJoinEvent> {

  }

  commandManager.setupCommands()

  println("doing login")
  bot.login {
    @OptIn(PrivilegedIntent::class)
    intents += Intent.MessageContent
  }
}
