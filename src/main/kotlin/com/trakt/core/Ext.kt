package com.trakt.core

import dev.kord.common.entity.Snowflake

val ULong.snowflake: Snowflake
  get() = Snowflake(this)
