@file:OptIn(DelicateCoroutinesApi::class, ExperimentalCoroutinesApi::class)

package com.trakt.data

import com.trakt.core.TraktConfig
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.core.Transaction
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

abstract class Repository(protected val config: TraktConfig) {

  protected suspend fun <T> safeTransaction(db: Database? = null, statement: Transaction.() -> T): T {
    return withContext(dispatcher) { transaction(db, statement = statement) }
  }

  companion object {
    private val dispatcher = newSingleThreadContext("repository")
  }
}
