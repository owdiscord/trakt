package com.trakt.core

import dev.kord.core.event.message.MessageCreateEvent

fun processMessage(message: MessageCreateEvent, progressManager: ProgressManager) {
  if (message.member?.isBot != false) return
  message.member?.id?.also { progressManager.submitProgress(it.value) }
}
