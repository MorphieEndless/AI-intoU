package com.yingti.app.relay

import org.json.JSONObject

/** The transport does not own hardware; it only dispatches ordered messages. */
interface RelayCommands {
    suspend fun dispatch(command: JSONObject): JSONObject
    fun deviceList(): JSONObject
}
