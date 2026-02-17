@file:OptIn(ExperimentalTime::class)

package com.trakt.core

import com.trakt.data.UserRepository
import dev.kord.core.Kord
import dev.kord.core.event.guild.MemberLeaveEvent
import dev.kord.core.event.message.MessageCreateEvent
import dev.kord.core.on
import dev.kord.gateway.Intent
import dev.kord.gateway.PrivilegedIntent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

@OptIn(PrivilegedIntent::class)
suspend fun main() {
    val configLocation = System.getenv("CONFIG_LOCATION") ?: "./config.json"
    val databaseLocation = System.getenv("DATABASE") ?: "${System.getProperty("user.home")}/trakt-db/data.db"

    require(File(configLocation).exists()) { "config location $configLocation not found!" }

    val config = TraktConfig.readConfig(configLocation)
    val bot = Kord(config.token)

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val userRepository = UserRepository(config, databaseLocation)
    val progressManager = ProgressManager(bot, userRepository, config, scope).startCollection()
//  VoiceProgressManager(bot, userRepository, config, scope).start()
    val sanctionManager = SanctionManager(userRepository, config, scope).startCollection()
    val followManager = FollowManager(bot, userRepository, config, scope).start()
    val commandManager = CommandManager(bot, progressManager, userRepository, followManager, config)
    val textCommandManager = TextCommandManager(bot, config, scope)

    printLogging("Collection started")
    bot.on<MessageCreateEvent> {
        if (guildId?.value != config.guild || member?.isBot == true) return@on
        if (sanctionManager.maybeSanction(this)) {
            return@on
        }
        member?.id?.also { progressManager.submitProgress(it.value) }
        textCommandManager.processCommand(this)
        followManager.handleMessage(this)
    }

    bot.on<MemberLeaveEvent> { userRepository.userLeft(user.id.value) }

    commandManager.setupCommands()

    bot.login {
        intents += Intent.MessageContent
        intents += Intent.GuildMembers
    }
}

fun printLogging(msg: String) {
    println("${Clock.System.now()} $msg")
}

fun printLogging(e: Exception) {
    println("${Clock.System.now()} ${e.message}")
}
