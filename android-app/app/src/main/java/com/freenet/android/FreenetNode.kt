package com.freenet.android

/**
 * JNI bridge to the native freenet-android Rust library.
 *
 * All methods are static — they communicate with the global Rust state
 * through the JNI functions defined in rust/src/lib.rs.
 */
object FreenetNode {
    init {
        System.loadLibrary("freenet_android")
    }

    /** Start the Freenet node. Returns the WebSocket API URL. */
    external fun start(dataDir: String, wsPort: String): Boolean

    /** Stop the node gracefully. */
    external fun stop()

    /** Check if the node is currently running. */
    external fun isRunning(): Boolean

    /** Get the WebSocket API URL for the UI to connect. */
    external fun getWsUrl(): String

    /** Provide a 256-bit KEK from Android Keystore. */
    external fun provideKek(kek: ByteArray): Boolean

    /** Get node statistics as a JSON string. */
    external fun getStats(): String
}
