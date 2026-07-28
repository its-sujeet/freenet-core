package com.freenet.android

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Bundle
import android.webkit.WebView
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Main activity for the Freenet Android app.
 *
 * Shows:
 * - Node status (running/stopped)
 * - WebSocket API URL
 * - Controls: Start / Stop
 * - WebView dashboard (connected to local node)
 */
class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var urlText: TextView
    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private lateinit var webView: WebView

    private var lastWsUrl: String? = null

    private val postNotificationsPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            startNodeService()
        } else {
            Toast.makeText(this, "Notification permission needed for background node", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        urlText = findViewById(R.id.urlText)
        startButton = findViewById(R.id.startButton)
        stopButton = findViewById(R.id.stopButton)
        webView = findViewById(R.id.webView)

        // Configure WebView
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowContentAccess = true
            allowFileAccess = true
        }
        // Load embedded dashboard HTML
        webView.loadUrl("file:///android_asset/dashboard.html")

        startButton.setOnClickListener { handleStart() }
        stopButton.setOnClickListener { handleStop() }

        // Register network callback to detect WiFi/mobile data
        registerNetworkCallback()

        // Poll node status
        lifecycleScope.launch {
            while (true) {
                updateUi()
                delay(2000)
            }
        }
    }

    private fun handleStart() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            postNotificationsPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        startNodeService()
    }

    private fun startNodeService() {
        val intent = Intent(this, FreenetService::class.java).apply {
            putExtra("WS_PORT", 0) // 0 = auto-assign
        }
        ContextCompat.startForegroundService(this, intent)
        Toast.makeText(this, "Starting Freenet node...", Toast.LENGTH_SHORT).show()
    }

    private fun handleStop() {
        val intent = Intent(this, FreenetService::class.java)
        stopService(intent)
        Toast.makeText(this, "Stopping Freenet node...", Toast.LENGTH_SHORT).show()
        lastWsUrl = null
    }

    private fun updateUi() {
        try {
            val running = FreenetNode.isRunning()
            val stats = FreenetNode.getStats()

            statusText.text = if (running) "Running" else "Stopped"
            statusText.setTextColor(
                if (running) 0xFF00FF00.toInt()
                else 0xFFFF0000.toInt()
            )

            startButton.isEnabled = !running
            stopButton.isEnabled = running

            if (running) {
                val wsUrl = FreenetNode.getWsUrl()
                urlText.text = wsUrl

                // Inject WS URL into the embedded dashboard via JS
                if (wsUrl != lastWsUrl && wsUrl.isNotEmpty()) {
                    lastWsUrl = wsUrl
                    val js = "javascript:(function(){" +
                        "var ev=new MessageEvent('message',{data:{wsUrl:'$wsUrl'}});" +
                        "window.dispatchEvent(ev);" +
                        "})()"
                    webView.evaluateJavascript(js, null)
                }
            } else {
                urlText.text = "Not connected"
                webView.loadData("<html><body><h2>Freenet not running</h2><p>Start the node to see the dashboard.</p></body></html>", "text/html", "UTF-8")
            }
        } catch (e: Exception) {
            statusText.text = "Error"
            urlText.text = e.message
        }
    }

    private fun registerNetworkCallback() {
        val connectivityManager = getSystemService(ConnectivityManager::class.java)
        val networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "Network available", Toast.LENGTH_SHORT).show()
                }
            }

            override fun onLost(network: Network) {
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "Network lost", Toast.LENGTH_SHORT).show()
                }
            }
        }

        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        connectivityManager.registerNetworkCallback(request, networkCallback)
    }

    override fun onDestroy() {
        super.onDestroy()
        // Don't stop the service — it runs independently as foreground service
    }
}
