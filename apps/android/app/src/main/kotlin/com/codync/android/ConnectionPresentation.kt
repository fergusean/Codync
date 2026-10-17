package com.codync.android

import com.codync.android.core.LinkState

/** Cached bots remain readable while the connection reports its actual state. */
internal fun connectionLabel(link: LinkState): String = when (link) {
    is LinkState.Ready -> "Connected"
    LinkState.Connecting -> "Connecting…"
    is LinkState.ComputerOffline -> "Computer offline"
    is LinkState.Failed -> if (link.status == 403) "Access denied" else if (link.status == 426) "Update required" else "Disconnected"
}

internal data class EmptyRoster(val title: String, val detail: String? = null, val canCreate: Boolean = false)

internal fun emptyRoster(state: AppState, query: String, hidden: Boolean): EmptyRoster = when {
    state.computers.isEmpty() -> EmptyRoster("No computer yet")
    query.isNotBlank() -> EmptyRoster("No matching bots")
    state.connection !is LinkState.Ready -> EmptyRoster(connectionLabel(state.connection),
        when (val link = state.connection) {
            is LinkState.Failed -> link.message
            is LinkState.ComputerOffline -> "Your bots and chats will appear when the computer reconnects."
            else -> "Waiting for your computer’s bot list."
        })
    !state.rosterLoaded -> EmptyRoster("Syncing bots…")
    hidden -> EmptyRoster("No hidden bots")
    else -> EmptyRoster("No bots yet", canCreate = state.actualConnection is LinkState.Ready)
}
