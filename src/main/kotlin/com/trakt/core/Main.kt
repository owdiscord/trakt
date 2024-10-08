package com.trakt.core

import com.trakt.data.UserRepository
import dev.kord.core.Kord
import dev.kord.core.event.message.MessageCreateEvent
import dev.kord.core.on

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
    if (member?.isBot == true) return@on
    println("msg: user=${member?.id}, channel=${message.channel}")
    member?.id?.also { progressManager.submitProgress(it.value) }
  }

  commandManager.setupCommands()

  println("doing login")
  bot.login()
}
