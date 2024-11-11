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
  data class Penalty(val user: ULong, val actionType: ActionType)

  enum class ActionType {
    WARN,
    MUTE,
    UNMUTE,
    BAN,
    UNBAN;

    companion object {
      fun fromString(raw: String?): ActionType? =
          when (raw) {
            "WARN" -> WARN
            "MUTE" -> MUTE
            "UNMUTED" -> UNMUTE
            "BAN" -> BAN
            "UNBAN" -> UNBAN
            else -> null
          }
    }
  }

  private val sanctionChannel = Channel<Penalty>(Channel.UNLIMITED)

  fun startCollection(): SanctionManager {
    scope.launch {
      for (penalty in sanctionChannel) {
        when (penalty.actionType) {
          ActionType.WARN -> repository.addWarn(penalty.user)
          ActionType.MUTE -> repository.updateMuteStatus(penalty.user, true)
          ActionType.UNMUTE -> repository.updateMuteStatus(penalty.user, false)
          ActionType.BAN -> repository.updateBanStatus(penalty.user, true)
          ActionType.UNBAN -> repository.updateBanStatus(penalty.user, false)
        }
      }
    }
    return this
  }

  private fun submitSanction(embed: Embed) {
    val actionType = ActionType.fromString(embed.title?.split(' ')?.firstOrNull()) ?: return
    for (field in embed.fields) {
      if (field.name == "User") {
        val snowflake = Regex("<@!?(\\d+)>").find(field.value)?.groupValues?.getOrNull(1) ?: return
        sanctionChannel.trySend(Penalty(snowflake.toULong(), actionType))
        return
      }
    }
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
