package com.trakt.core

import com.trakt.data.initUserRepository
import dev.kord.core.Kord
import dev.kord.core.event.message.MessageCreateEvent
import dev.kord.core.event.role.RoleUpdateEvent
import dev.kord.core.on

suspend fun main(args: Array<String>) {
  println("Hello World!")

  // Try adding program arguments via Run/Debug configuration.
  // Learn more about running applications:
  // https://www.jetbrains.com/help/idea/running-applications.html.
  println("Program arguments: ${args.joinToString()}")

  initUserRepository()

  val bot = Kord("hi")

  val progressManager = ProgressManager().startCollection()

  bot.on<MessageCreateEvent> { processMessage(this, progressManager) }

  bot.on<RoleUpdateEvent> {  }
}
