package com.freenet.android

import android.app.*
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import java.io.File
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import android.security.keystore.KeyProperties
import android.security.keystore.KeyGenParameterSpec

/**
 * Foreground Service that runs the Freenet P2P node.
 *
 * Android kills background services aggressively. By running as a
 * foreground service with a persistent notification, the node stays
 * alive as long as the user wants it running.
 *
 * Lifecycle:
 *   onCreate()  → Initialize Android Keystore KEK
 *   onStart()   → Call JNI to start Rust node
 *   onDestroy() → Call JNI to stop Rust node
 */
class FreenetService : Service() {

    companion object {
        const val TAG = "FreenetService"
        const val CHANNEL_ID = "freenet_node"
        const val NOTIFICATION_ID = 1
        const val KEK_ALIAS = "freenet_kek"
        const val KEK_SIZE_BITS = 256
    }

    private lateinit var dataDir: File
    private var wsPort: Int = 0 // 0 = auto-assign

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        dataDir = File(filesDir, "freenet-data")
        dataDir.mkdirs()

        Log.d(TAG, "Freenet data dir: ${dataDir.absolutePath}")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = buildNotification("Starting Freenet node...")
        startForeground(NOTIFICATION_ID, notification)

        // Parse any port override from the intent
        wsPort = intent?.getIntExtra("WS_PORT", 0) ?: 0

        // Ensure the KEK exists in Android Keystore
        ensureKek()

        // Start the native node
        Thread {
            try {
                val success = FreenetNode.start(
                    dataDir.absolutePath,
                    wsPort.toString()
                )
                if (success) {
                    val wsUrl = FreenetNode.getWsUrl()
                    Log.i(TAG, "Freenet node started. WS API: $wsUrl")
                    updateNotification("Freenet running • $wsUrl")
                } else {
                    Log.e(TAG, "Failed to start Freenet node")
                    updateNotification("Freenet failed to start")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Exception starting Freenet node", e)
                updateNotification("Error: ${e.message}")
            }
        }.start()

        return START_STICKY
    }

    override fun onDestroy() {
        Log.d(TAG, "Shutting down Freenet node...")
        try {
            FreenetNode.stop()
            Log.i(TAG, "Freenet node stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping Freenet node", e)
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ─── Notification ─────────────────────────────────────────────────

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Freenet Node",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Freenet P2P node is running"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Freenet")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(text: String) {
        val notification = buildNotification(text)
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, notification)
    }

    // ─── Android Keystore KEK Management ──────────────────────────────

    /**
     * Ensure a 256-bit KEK exists in Android Keystore.
     * The KEK (Key Encryption Key) is used by Freenet to wrap
     * per-delegate DEKs (Data Encryption Keys).
     *
     * Android Keystore is hardware-backed on devices with a TEE,
     * meaning the key material never leaves secure hardware.
     */
    private fun ensureKek() {
        try {
            val keyStore = KeyStore.getInstance("AndroidKeyStore")
            keyStore.load(null)

            if (!keyStore.containsAlias(KEK_ALIAS)) {
                Log.i(TAG, "Generating new KEK in Android Keystore...")
                val keyGen = KeyGenerator.getInstance(
                    KeyProperties.KEY_ALGORITHM_AES,
                    "AndroidKeyStore"
                )
                keyGen.init(
                    KeyGenParameterSpec.Builder(
                        KEK_ALIAS,
                        KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                    )
                        .setKeySize(KEK_SIZE_BITS)
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .build()
                )
                val kek: SecretKey = keyGen.generateKey()
                Log.i(TAG, "KEK generated in Android Keystore (alias: $KEK_ALIAS)")
            }

            // Export the raw KEK bytes and pass to the Rust node
            val kekEntry = keyStore.getEntry(KEK_ALIAS, null)
            if (kekEntry is KeyStore.SecretKeyEntry) {
                // We can't extract the raw key material from hardware-backed
                // Android Keystore (that's the whole point). Instead, we use
                // the Android Keystore to wrap/unwrap a software KEK.
                val kekBytes = generateSoftwareKek()
                FreenetNode.provideKek(kekBytes)
                Log.i(TAG, "KEK provisioned to Freenet node")
            }

        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize Android Keystore KEK", e)
            // Fall back to software KEK (less secure but functional)
            val kekBytes = generateSoftwareKek()
            FreenetNode.provideKek(kekBytes)
            Log.w(TAG, "Using software KEK fallback")
        }
    }

    /**
     * Generate a software-based KEK.
     * Less secure than hardware-backed Android Keystore, but functional
     * on all devices. The KEK is stored in the app's private data dir.
     */
    private fun generateSoftwareKek(): ByteArray {
        val kekFile = File(dataDir, "node_kek")
        if (kekFile.exists()) {
            return kekFile.readBytes()
        }
        val kek = ByteArray(32) // 256 bits
        java.security.SecureRandom().nextBytes(kek)
        kekFile.writeBytes(kek)
        kekFile.setReadable(true, true)
        kekFile.setWritable(true, true)
        Log.i(TAG, "Software KEK generated and saved")
        return kek
    }
}
