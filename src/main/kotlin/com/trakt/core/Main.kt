package com.trakt.core

import com.trakt.data.initUserRepository
import dev.kord.core.Kord
import dev.kord.core.event.guild.BanAddEvent
import dev.kord.core.event.guild.MemberJoinEvent
import dev.kord.core.event.guild.MemberUpdateEvent
import dev.kord.core.event.message.MessageCreateEvent
import dev.kord.core.event.role.RoleUpdateEvent
import dev.kord.core.on
import dev.kord.gateway.GuildBanAdd

suspend fun main(args: Array<String>) {
  println("Hello World!")

  // Try adding program arguments via Run/Debug configuration.
  // Learn more about running applications:
  // https://www.jetbrains.com/help/idea/running-applications.html.
  println("Program arguments: ${args.joinToString()}")

  initUserRepository()

  val bot = Kord("MTI5MjU4NDUyNzg0NzQ4OTU3OQ.GwK1Ai.t6L7rS-zArLu-tqilDqyXakd-npk9YxDmtcBUE")

  val progressManager = ProgressManager().startCollection()

  bot.on<MessageCreateEvent> {
    if (member?.isBot != false) return@on
    member?.id?.also { progressManager.submitProgress(it.value) }
  }

  bot.on<BanAddEvent> {  }

  bot.on<MemberJoinEvent> {  }

  bot.login()
}
