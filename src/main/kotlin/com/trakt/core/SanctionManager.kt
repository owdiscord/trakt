package com.trakt.core

import com.trakt.data.UserRepository
import dev.kord.core.entity.Embed
import dev.kord.core.event.message.MessageCreateEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

class SanctionManager(
    private val repository: UserRepository,
    private val config: TraktConfig,
    private val scope: CoroutineScope
) {
  data class Penalty(val user: ULong, val actionType: String) {
    override fun toString(): String {
      return "user: $user, sanction type: $actionType"
    }
  }

  private val sanctionChannel = Channel<Penalty>(Channel.UNLIMITED)

  fun startCollection(): SanctionManager {
    scope.launch {
      for (penalty in sanctionChannel) {
        printLogging("Processing sanction: $penalty")
        when (penalty.actionType) {
          "WARN" -> repository.addWarn(penalty.user)
          "MUTE" -> repository.updateMuteStatus(penalty.user, true)
          "UNMUTE" -> repository.updateMuteStatus(penalty.user, false)
          "BAN" -> repository.updateBanStatus(penalty.user, true)
          "UNBAN" -> repository.updateBanStatus(penalty.user, false)
        }
      }
    }
    return this
  }

  private fun submitSanction(embed: Embed) {
    val actionType = embed.title?.split(' ')?.firstOrNull() ?: return
    var user: String? = null
    for (field in embed.fields) {
      if (field.name == "User") {
        user = Regex("<@!?(\\d+)>").find(field.value)?.groupValues?.getOrNull(1) ?: return
      }
      if (field.name == "Moderator") {
        // don't sanction automatic actions
        if (Regex("<@!?(\\d+)>").find(field.value)?.groupValues?.getOrNull(1) == "473868086773153793") {
          printLogging("Skipping automatic sanction of user $user")
          return
        }
      }
    }
    user?.also { sanctionChannel.trySend(Penalty(it.toULong(), actionType)) }
  }

  fun maybeSanction(event: MessageCreateEvent): Boolean {
    if (event.message.channelId.value == config.sanctionChannel &&
        event.message.webhookId != null) {
      // dealing with a new Zeppelin action
      if (event.message.embeds.size != 1) return false
      submitSanction(event.message.embeds.first())
      return true
    }
    return false
  }
}
