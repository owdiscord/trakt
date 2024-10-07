package com.trakt.core

import com.trakt.data.UserRepository
import dev.kord.core.Kord
import dev.kord.core.event.guild.BanAddEvent
import dev.kord.core.event.guild.MemberJoinEvent
import dev.kord.core.event.message.MessageCreateEvent
import dev.kord.core.on

suspend fun main(args: Array<String>) {
  println("Hello World!")
  println("Program arguments: ${args.joinToString()}")

  val bot = Kord(args.first())
  val config = TraktConfig.readConfig(args[1])

  println("created bot")
  val progressManager = ProgressManager(bot, UserRepository(), config).startCollection()

  println("collection started")
  bot.on<MessageCreateEvent> {
    if (member?.isBot == true) return@on
    println("msg: user=${member?.id}, channel=${message.channel}")
    member?.id?.also { progressManager.submitProgress(it.value) }
  }

  bot.on<BanAddEvent> {  }

  bot.on<MemberJoinEvent> {  }

  println("doing login")
  bot.login()
}
