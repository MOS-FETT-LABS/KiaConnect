package com.mosfettlabs.kiacompanion.pebble

import java.util.UUID

/** Wire contract between the Kia watchapp (C) and this companion.
 *  Key values are the package.json messageKeys indices (0-based). */
object KiaPebbleProtocol {
    val APP_UUID: UUID = UUID.fromString("e024c06c-5326-4f9e-ba24-98677601ad5d")

    val STATUS_REQUEST: UInt = 10000u
    val NAME: UInt = 10001u
    val LOCKED: UInt = 10002u
    val FUEL: UInt = 10003u
    val ODO: UInt = 10004u
    val UPDATED: UInt = 10005u
    val INFO: UInt = 10006u
    val WATCH_READY: UInt = 10007u
    val ACTION: UInt = 10008u
    val ACTION_TEMP: UInt = 10009u
    val ACTION_DURATION: UInt = 10010u
}
