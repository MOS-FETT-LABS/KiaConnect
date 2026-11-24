package com.mosfettlabs.kiacompanion.pebble

import java.util.UUID

/** Wire contract between the Kia watchapp (C) and this companion.
 *  Key values are the package.json messageKeys indices (0-based). */
object KiaPebbleProtocol {
    val APP_UUID: UUID = UUID.fromString("e024c06c-5326-4f9e-ba24-98677601ad5d")

    val STATUS_REQUEST: UInt = 0u
    val NAME: UInt = 1u
    val LOCKED: UInt = 2u
    val FUEL: UInt = 3u
    val ODO: UInt = 4u
    val UPDATED: UInt = 5u
    val INFO: UInt = 6u
    val WATCH_READY: UInt = 7u
}
