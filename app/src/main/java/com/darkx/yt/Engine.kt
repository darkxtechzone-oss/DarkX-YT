package com.darkx.yt

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import org.json.JSONObject

object Resolver {
    /** Returns a direct stream URL (and headers) for a YouTube/other link using yt-dlp. */
    fun stream(url: String, video: Boolean): Pair<String, Map<String, String>> {
        App.ready.await()
        App.initError?.let { throw Exception(it) }
        val r = YoutubeDLRequest(url)
        r.addOption("--no-playlist")
        r.addOption("-f", if (video) "best[ext=mp4][height<=720]/best[ext=mp4]/best" else "bestaudio[ext=m4a]/bestaudio/best")
        r.addOption("-J")
        val out = YoutubeDL.getInstance().execute(request = r, callback = { _, _, _ -> }).out
        val j = JSONObject(out.substring(out.indexOf('{')))
        var u = j.optString("url")
        var hdr = j.optJSONObject("http_headers")
        if (u.isEmpty()) {
            val rf = j.optJSONArray("requested_formats")
            if (rf != null && rf.length() > 0) {
                val f0 = rf.getJSONObject(0)
                u = f0.optString("url")
                hdr = f0.optJSONObject("http_headers")
            }
        }
        if (u.isEmpty()) throw Exception("No stream URL")
        val m = HashMap<String, String>()
        val h = hdr
        if (h != null) {
            val ks = h.keys()
            while (ks.hasNext()) {
                val k = ks.next()
                m[k] = h.getString(k)
            }
        }
        return Pair(u, m)
    }
}

object Engine {
    lateinit var app: Context
    val main = Handler(Looper.getMainLooper())
    val listeners = mutableListOf<() -> Unit>()
    var queue: MutableList<Track> = mutableListOf()
    var index = -1
    var shuffle = false
    var repeat = 0 // 0 off, 1 all, 2 one
    var playing = false
    var preparing = false
    var error = ""
    private var mp: MediaPlayer? = null
    private var token = 0
    private var focusReq: AudioFocusRequest? = null

    val current: Track? get() = queue.getOrNull(index)

    fun notifyChange() {
        val f = { listeners.toList().forEach { it() } }
        if (Looper.myLooper() == Looper.getMainLooper()) f() else main.post { f() }
    }

    fun playQueue(list: List<Track>, start: Int) {
        queue = list.toMutableList()
        play(start)
    }

    fun play(i: Int) {
        if (i !in queue.indices) return
        index = i
        val t = queue[i]
        val my = ++token
        release()
        preparing = true
        playing = false
        error = ""
        notifyChange()
        try {
            app.startForegroundService(Intent(app, PlayService::class.java))
        } catch (e: Exception) {
        }
        if (t.uri.isNotEmpty()) {
            start(Uri.parse(t.uri), null, my)
        } else {
            Thread {
                try {
                    val (u, h) = Resolver.stream(t.yt, false)
                    main.post { if (my == token) start(Uri.parse(u), h, my) }
                } catch (e: Exception) {
                    main.post { if (my == token) fail("Could not stream this track") }
                }
            }.start()
        }
    }

    private fun fail(msg: String) {
        preparing = false
        playing = false
        error = msg
        notifyChange()
    }

    private fun start(uri: Uri, headers: Map<String, String>?, my: Int) {
        val m = MediaPlayer()
        mp = m
        try {
            m.setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .setUsage(AudioAttributes.USAGE_MEDIA).build()
            )
            if (headers != null) m.setDataSource(app, uri, headers) else m.setDataSource(app, uri)
            m.setOnPreparedListener {
                if (my == token) {
                    preparing = false
                    requestFocus()
                    it.start()
                    playing = true
                    notifyChange()
                }
            }
            m.setOnCompletionListener { if (my == token) next(true) }
            m.setOnErrorListener { _, _, _ ->
                if (my == token) fail("Playback error")
                true
            }
            m.prepareAsync()
        } catch (e: Exception) {
            fail("Cannot play this file")
        }
    }

    private fun release() {
        try {
            mp?.release()
        } catch (e: Exception) {
        }
        mp = null
    }

    private fun requestFocus() {
        try {
            val am = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setOnAudioFocusChangeListener { c ->
                    if (c == AudioManager.AUDIOFOCUS_LOSS || c == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) pause()
                }.build()
            focusReq = req
            am.requestAudioFocus(req)
        } catch (e: Exception) {
        }
    }

    fun toggle() {
        val m = mp ?: return
        try {
            if (m.isPlaying) {
                m.pause()
                playing = false
            } else if (!preparing) {
                requestFocus()
                m.start()
                playing = true
            }
        } catch (e: Exception) {
        }
        notifyChange()
    }

    fun pause() {
        try {
            val m = mp
            if (m != null && m.isPlaying) m.pause()
        } catch (e: Exception) {
        }
        playing = false
        notifyChange()
    }

    fun next(auto: Boolean = false) {
        if (queue.isEmpty()) return
        if (auto && repeat == 2) {
            play(index)
            return
        }
        val n = if (shuffle && queue.size > 1) queue.indices.random() else index + 1
        if (n >= queue.size) {
            if (repeat == 1 || !auto) play(0) else stop()
        } else play(n)
    }

    fun prev() {
        if (queue.isEmpty()) return
        if (pos() > 3000) seekTo(0) else play(if (index > 0) index - 1 else queue.size - 1)
    }

    fun seekTo(ms: Int) {
        try {
            mp?.seekTo(ms)
        } catch (e: Exception) {
        }
    }

    fun pos(): Int = try { if (preparing) 0 else mp?.currentPosition ?: 0 } catch (e: Exception) { 0 }
    fun dur(): Int = try { if (preparing) 0 else mp?.duration ?: 0 } catch (e: Exception) { 0 }

    fun stop() {
        token++
        release()
        playing = false
        preparing = false
        queue.clear()
        index = -1
        notifyChange()
    }
}

class PlayService : Service() {
    companion object {
        const val CH = "dx_play"
        const val A_TOGGLE = "dx.toggle"
        const val A_NEXT = "dx.next"
        const val A_PREV = "dx.prev"
        const val A_STOP = "dx.stop"
    }

    private val l: () -> Unit = { update() }

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(CH, "Playback", NotificationManager.IMPORTANCE_LOW))
        Engine.listeners.add(l)
    }

    override fun onStartCommand(i: Intent?, flags: Int, startId: Int): Int {
        when (i?.action) {
            A_TOGGLE -> Engine.toggle()
            A_NEXT -> Engine.next()
            A_PREV -> Engine.prev()
            A_STOP -> Engine.stop()
        }
        if (Engine.current != null) {
            startForeground(1, build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        Engine.listeners.remove(l)
        super.onDestroy()
    }

    override fun onBind(i: Intent?): IBinder? = null

    private fun update() {
        if (Engine.current == null) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(1, build())
    }

    private fun act(icon: Int, label: String, a: String): Notification.Action {
        val pi = PendingIntent.getService(
            this, a.hashCode(), Intent(this, PlayService::class.java).setAction(a),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Action.Builder(Icon.createWithResource(this, icon), label, pi).build()
    }

    private fun build(): Notification {
        val t = Engine.current
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CH)
            .setSmallIcon(R.drawable.ic_music)
            .setContentTitle(t?.title ?: "DarkX YT")
            .setContentText(if (Engine.preparing) "Loading..." else (t?.artist ?: ""))
            .setContentIntent(open)
            .setOngoing(Engine.playing)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .addAction(act(R.drawable.ic_prev, "Previous", A_PREV))
            .addAction(act(if (Engine.playing) R.drawable.ic_pause else R.drawable.ic_play, "Play/Pause", A_TOGGLE))
            .addAction(act(R.drawable.ic_next, "Next", A_NEXT))
            .addAction(act(R.drawable.ic_close, "Stop", A_STOP))
            .setStyle(Notification.MediaStyle().setShowActionsInCompactView(0, 1, 2))
            .build()
    }
}
