package com.micah.cj7brain


import android.util.Log
import io.socket.client.IO
import io.socket.client.Socket
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

object SocketManager {

    private var socket: Socket? = null

    private val _connectionState = MutableStateFlow(false)
    val connectionState: StateFlow<Boolean> = _connectionState

    fun connect() {
        if (socket?.connected() == true) return

        val opts = IO.Options()
        socket = IO.socket("http://raspberrypi.local:5000", opts)

        socket?.on(Socket.EVENT_CONNECT) {
            Log.d("SocketIO", "Connected")
            _connectionState.value = true
            socket?.emit("android_connect")
        }

        socket?.on(Socket.EVENT_DISCONNECT) {
            Log.d("SocketIO", "Disconnected")
            _connectionState.value = false
        }
        Log.d("SocketIO", "Connecting...")
        socket?.connect()
    }

    fun disconnect() {
        socket?.disconnect()
    }
}