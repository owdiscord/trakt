package com.trakt.core

import com.trakt.data.UserRepository
import dev.kord.core.Kord
import dev.kord.core.event.message.MessageCreateEvent
import dev.kord.core.on
import dev.kord.gateway.Intent
import dev.kord.gateway.PrivilegedIntent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

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
  val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  val userRepository = UserRepository(config)
  val progressManager = ProgressManager(bot, userRepository, config, scope).startCollection()
  val sanctionManager = SanctionManager(userRepository, config, scope).startCollection()
  val commandManager = CommandManager(bot, progressManager, userRepository, config)

  println("collection started")
  bot.on<MessageCreateEvent> {
    if (guildId?.value != config.guild || member?.isBot == true) return@on
    if (sanctionManager.maybeSanction(this)) {
      return@on
    }
    member?.id?.also { progressManager.submitProgress(it.value) }
  }

  commandManager.setupCommands()

  bot.login {
    @OptIn(PrivilegedIntent::class)
    intents += Intent.MessageContent
  }
}
