package com.codync.android.core

/**
 * True when an entry event is new to the mirror and lies below the bot's main-chat floor for this connection.
 *
 * `seq` is assigned in insert order, so if the mirror held a contiguous newest block when a connection started
 * at `since > 0`, everything the host created afterwards sits above that block's lowest seq. A new entry below
 * the floor is an old one whose rev was bumped (a rewrite, a reaction); history paging brings it. Known entries,
 * threads and a missing floor (since 0, or an empty bot at hello) are never dropped.
 */
fun outsideLoadedWindow(entry: Entry, known: Boolean, floor: Long?): Boolean =
    !known && entry.threadId == null && floor != null && entry.seq < floor
