package com.myiptv.app

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.util.concurrent.Executors

class PlayerActivity : AppCompatActivity() {

    private var player: ExoPlayer? = null
    private lateinit var playerView: PlayerView
    private lateinit var bar: View
    private lateinit var title: TextView
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var index = 0
    private val items: List<Channel> get() = PlayerQueue.items

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (items.isEmpty()) {
            finish()
            return
        }
        setContentView(R.layout.activity_player)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        index = (savedInstanceState?.getInt("index") ?: PlayerQueue.index).coerceIn(0, items.size - 1)

        playerView = findViewById(R.id.player_view)
        bar = findViewById(R.id.bar)
        title = findViewById(R.id.title)
        findViewById<View>(R.id.back).setOnClickListener { finish() }
        findViewById<View>(R.id.prev).setOnClickListener { zap(-1) }
        findViewById<View>(R.id.next).setOnClickListener { zap(1) }
        findViewById<View>(R.id.tv).setOnClickListener { sendToTv() }
        findViewById<View>(R.id.mirror).setOnClickListener { Util.openMirror(this) }
        playerView.setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { v ->
            bar.visibility = v
        })
        hideSystemUi()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("index", index)
    }

    override fun onStart() {
        super.onStart()
        if (items.isNotEmpty() && player == null) startPlayer()
    }

    override fun onStop() {
        super.onStop()
        player?.release()
        player = null
    }

    private fun hideSystemUi() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val c = WindowInsetsControllerCompat(window, window.decorView)
        c.hide(WindowInsetsCompat.Type.systemBars())
        c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    private fun startPlayer() {
        val p = ExoPlayer.Builder(this).build()
        p.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                Toast.makeText(
                    this@PlayerActivity,
                    "Cannot play this channel (${error.errorCodeName})",
                    Toast.LENGTH_LONG
                ).show()
            }
        })
        player = p
        playerView.player = p
        playCurrent()
    }

    private fun playCurrent() {
        val p = player ?: return
        val c = items[index]
        title.text = "${index + 1}. ${c.name}"
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(c.userAgent.ifEmpty { Library.DEFAULT_UA })
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15000)
            .setReadTimeoutMs(20000)
        val builder = MediaItem.Builder().setUri(c.url)
        if (c.url.contains(".m3u8", ignoreCase = true)) builder.setMimeType(MimeTypes.APPLICATION_M3U8)
        p.setMediaSource(DefaultMediaSourceFactory(http).createMediaSource(builder.build()))
        p.prepare()
        p.playWhenReady = true
        playerView.showController()
    }

    private fun zap(d: Int) {
        index = (index + d + items.size) % items.size
        playCurrent()
    }

    private fun sendToTv() {
        val c = items[index]
        Toast.makeText(this, "Looking for your TV…", Toast.LENGTH_SHORT).show()
        io.execute {
            val found: List<Renderer> = try {
                Dlna.discover(this)
            } catch (_: Exception) {
                emptyList()
            }
            main.post {
                if (isFinishing) return@post
                if (found.isEmpty()) {
                    MaterialAlertDialogBuilder(this)
                        .setTitle("No TV found")
                        .setMessage("Make sure the monitor is on and on the same Wi-Fi. You can mirror your screen instead.")
                        .setPositiveButton("Mirror screen") { _, _ -> Util.openMirror(this) }
                        .setNegativeButton("Close", null)
                        .show()
                } else {
                    MaterialAlertDialogBuilder(this)
                        .setTitle("Send to")
                        .setItems(found.map { it.name }.toTypedArray()) { _, i -> sendTo(found[i], c) }
                        .show()
                }
            }
        }
    }

    private fun sendTo(r: Renderer, c: Channel) {
        io.execute {
            val err: String? = try {
                Dlna.play(r, c.url, c.name)
                null
            } catch (e: Exception) {
                e.message ?: "error"
            }
            main.post {
                if (err == null) {
                    player?.pause()
                    Toast.makeText(this, "Playing on ${r.name}", Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(this, "The TV refused this stream ($err). Try Mirror instead.", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}
