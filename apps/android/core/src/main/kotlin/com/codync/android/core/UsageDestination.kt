package com.codync.android.core

import java.net.URI

data class UsageDestination(val scope: String, val computerId: String) {
    companion object {
        fun parse(value: String): UsageDestination {
            val uri = URI(value)
            require(uri.scheme == "codync" && uri.rawAuthority == "usage" && uri.fragment == null && uri.path.orEmpty() in setOf("", "/"))
            val scoped = BotDestination.parse("codync://bot/usage?" + uri.rawQuery.orEmpty())
            return UsageDestination(scoped.scope, scoped.computerId)
        }
    }
}
