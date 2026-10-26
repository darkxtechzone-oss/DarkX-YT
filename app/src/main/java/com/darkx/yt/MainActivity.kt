package com.darkx.yt

import android.Manifest
import android.animation.ValueAnimator
import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.util.LruCache
import android.view.*
import android.view.animation.LinearInterpolator
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.*
import androidx.core.content.FileProvider
import androidx.core.graphics.ColorUtils
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class Item(val id: String, val title: String, val channel: String, val dur: Long, val url: String, val thumb: String) {
    var state = 0          // 0 idle, 1 downloading, 2 saved, 3 error
    var progress = 0f
    var saved: Uri? = null
    var mime = ""
    var err = ""
}

class ChipV(val view: LinearLayout, val icon: ImageView, val label: TextView)
class RowRef(val status: TextView, val bar: ProgressBar, val dl: ChipV)
class TrackHolder(val art: ImageView, val title: TextView, val sub: TextView, val act: ImageView)

class MainActivity : Activity() {
    private val BG = Color.parseColor("#0B0F1A")
    private val CARD = Color.parseColor("#151B2E")
    private val BLUE = Color.parseColor("#2D8CFF")
    private val TXT = Color.parseColor("#E8EEFF")
    private val SUB = Color.parseColor("#8A94B0")
    private val RED = Color.parseColor("#FF4D4D")
    private val OKC = Color.parseColor("#2ECC71")
    private val CHIP = Color.parseColor("#22305A")

    private val prefs by lazy { getSharedPreferences("dx", Context.MODE_PRIVATE) }
    private val ticker = Handler(Looper.getMainLooper())
    private val imgCache = LruCache<String, Bitmap>(120)

    // shell
    private lateinit var pages: FrameLayout
    private val pageViews = arrayOfNulls<View>(4)
    private val navItems = mutableListOf<Pair<ImageView, TextView>>()
    private val letters = mutableListOf<TextView>()
    private var titleAnim: ValueAnimator? = null
    private var tab = 0

    // mini player + now playing
    private lateinit var mini: LinearLayout
    private lateinit var miniBar: ProgressBar
    private lateinit var miniArt: ImageView
    private lateinit var miniTitle: TextView
    private lateinit var miniSub: TextView
    private lateinit var miniPlay: ImageView
    private var npRefresh: (() -> Unit)? = null

    // search
    private lateinit var input: EditText
    private lateinit var list: LinearLayout
    private lateinit var msg: TextView
    private val chips = mutableListOf<TextView>()
    private val refs = HashMap<Item, RowRef>()
    private var fmt = 0
    private var active = 0
    private var pendingUrl: String? = null

    // library
    private lateinit var libList: ListView
    private lateinit var libMsg: TextView
    private lateinit var libAllow: TextView
    private lateinit var libFilter: EditText
    private lateinit var libAdapter: TrackAdapter
    private val libChips = mutableListOf<TextView>()
    private var libTracks: List<Track> = emptyList()
    private var libShown: List<Track> = emptyList()
    private var libSrc = 0
    private var libDirty = true

    // playlists
    private var playlists = mutableListOf<Playlist>()
    private lateinit var plPage: FrameLayout
    private var plOpen: Playlist? = null
    private var plAdapter: TrackAdapter? = null

    private val engineListener: () -> Unit = {
        updateMini()
        npRefresh?.invoke()
        if (::libAdapter.isInitialized) libAdapter.notifyDataSetChanged()
        plAdapter?.notifyDataSetChanged()
    }

    // ---------- helpers ----------
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun rr(color: Int, r: Int): GradientDrawable =
        GradientDrawable().apply { setColor(color); cornerRadius = dp(r).toFloat() }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    private fun fmtDur(s: Long): String =
        if (s <= 0) "" else if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60)
        else "%d:%02d".format(s / 60, s % 60)

    private fun fmtMs(ms: Int): String {
        val s = ms / 1000
        return "%d:%02d".format(s / 60, s % 60)
    }

    private fun safeName(t: String): String =
        t.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().take(100).ifEmpty { "DarkX YT" }

    private fun iconBtn(res: Int, size: Int, pad: Int, tint: Int, click: () -> Unit): ImageView = ImageView(this).apply {
        setImageResource(res)
        setColorFilter(tint)
        setPadding(dp(pad), dp(pad), dp(pad), dp(pad))
        layoutParams = LinearLayout.LayoutParams(dp(size), dp(size))
        setOnClickListener { click() }
    }

    private fun label(t: String, size: Float, color: Int, bold: Boolean = false): TextView = TextView(this).apply {
        text = t
        textSize = size
        setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun hideKeyboard(v: View) {
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(v.windowToken, 0)
    }

    private fun loadThumb(url: String, iv: ImageView) {
        if (url.isEmpty()) return
        iv.tag = url
        val c = imgCache.get(url)
        if (c != null) {
            iv.setImageBitmap(c)
            return
        }
        iv.setImageDrawable(null)
        Thread {
            try {
                val bmp = BitmapFactory.decodeStream(URL(url).openStream())
                if (bmp != null) {
                    imgCache.put(url, bmp)
                    runOnUiThread { if (iv.tag == url) iv.setImageBitmap(bmp) }
                }
            } catch (e: Exception) {
            }
        }.start()
    }

    private fun bindArt(iv: ImageView, thumb: String, pad: Int) {
        if (thumb.isNotEmpty()) {
            iv.clearColorFilter()
            iv.scaleType = ImageView.ScaleType.CENTER_CROP
            iv.setPadding(0, 0, 0, 0)
            loadThumb(thumb, iv)
        } else {
            iv.tag = null
            iv.setImageResource(R.drawable.ic_music)
            iv.setColorFilter(SUB)
            iv.scaleType = ImageView.ScaleType.CENTER_INSIDE
            iv.setPadding(dp(pad), dp(pad), dp(pad), dp(pad))
        }
    }

    private fun isCurrent(t: Track): Boolean {
        val c = Engine.current ?: return false
        return (t.uri.isNotEmpty() && c.uri == t.uri) || (t.yt.isNotEmpty() && c.yt == t.yt)
    }

    // ---------- permissions ----------
    private fun hasStorage(): Boolean =
        if (Build.VERSION.SDK_INT >= 33) checkSelfPermission(Manifest.permission.READ_MEDIA_AUDIO) == PackageManager.PERMISSION_GRANTED
        else checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

    private fun askStorage() {
        if (Build.VERSION.SDK_INT >= 33)
            requestPermissions(arrayOf(Manifest.permission.READ_MEDIA_AUDIO, Manifest.permission.READ_MEDIA_VIDEO), 11)
        else requestPermissions(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE), 11)
    }

    private fun ensureNotifPerm() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            !prefs.getBoolean("askedNotif", false)
        ) {
            prefs.edit().putBoolean("askedNotif", true).apply()
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 12)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 11) {
            libDirty = true
            if (tab == 1) loadLibrary()
        }
    }

    // ---------- lifecycle ----------
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.statusBarColor = BG
        window.navigationBarColor = CARD
        playlists = Store.load(this)
        buildUi()
        Engine.listeners.add(engineListener)
        selectTab(0)
        Thread {
            App.ready.await()
            runOnUiThread {
                val e = App.initError
                if (e != null) msg.text = "Engine failed to start:\n" + e.take(300)
                else if (list.childCount == 0) msg.text = "Search a song or paste a link."
            }
        }.start()
        handleIntent(intent)
        checkUpdate(false)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        startTitleAnim()
        ticker.post(tick)
        val u = pendingUrl
        if (u != null && Build.VERSION.SDK_INT >= 26 && packageManager.canRequestPackageInstalls()) {
            pendingUrl = null
            downloadApk(u)
        }
    }

    override fun onPause() {
        super.onPause()
        titleAnim?.cancel()
        ticker.removeCallbacks(tick)
    }

    override fun onDestroy() {
        Engine.listeners.remove(engineListener)
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (tab == 2 && plOpen != null) showPlaylistList()
        else if (tab != 0) selectTab(0)
        else super.onBackPressed()
    }

    private val tick: Runnable = object : Runnable {
        override fun run() {
            updateProgress()
            ticker.postDelayed(this, 500)
        }
    }

    private fun handleIntent(i: Intent?) {
        if (i == null) return
        var text: String? = null
        if (i.action == Intent.ACTION_SEND) text = i.getStringExtra(Intent.EXTRA_TEXT)
        else if (i.action == Intent.ACTION_VIEW) text = i.dataString
        if (text.isNullOrBlank()) return
        val m = Regex("https?://\\S+").find(text)
        selectTab(0)
        input.setText(m?.value ?: text)
        doSearch()
    }

    // ---------- shell UI ----------
    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG)
        }
        root.addView(buildHeader())
        pages = FrameLayout(this)
        pageViews[0] = buildSearchPage()
        pageViews[1] = buildLibraryPage()
        pageViews[2] = buildPlaylistPage()
        pageViews[3] = buildMorePage()
        root.addView(pages, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(buildMini())
        root.addView(buildNav())
        setContentView(root)
    }

    private val titleText = "DarkX YT"

    private fun buildHeader(): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(12), dp(18), dp(6))
        }
        letters.clear()
        for (ch in titleText) {
            val tv = TextView(this).apply {
                text = ch.toString()
                textSize = 26f
                setTypeface(typeface, Typeface.BOLD)
            }
            letters.add(tv)
            row.addView(tv)
        }
        animTitle(0f)
        return row
    }

    private fun animTitle(p: Float) {
        for (i in letters.indices) {
            val w = Math.sin((p - i * 0.6f).toDouble()).toFloat()
            val tv = letters[i]
            tv.translationY = -dp(5) * w
            val base = if (i >= 6) RED else TXT
            val glow = if (i >= 6) Color.WHITE else BLUE
            tv.setTextColor(ColorUtils.blendARGB(base, glow, (w + 1f) / 2f))
        }
    }

    private fun startTitleAnim() {
        titleAnim?.cancel()
        titleAnim = ValueAnimator.ofFloat(0f, (2 * Math.PI).toFloat()).apply {
            duration = 2400
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { animTitle(it.animatedValue as Float) }
            start()
        }
    }

    private fun buildNav(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(CARD)
            setPadding(0, dp(6), 0, dp(6))
        }
        val names = listOf("Search", "Library", "Playlists", "More")
        val icons = listOf(R.drawable.ic_search, R.drawable.ic_music, R.drawable.ic_list, R.drawable.ic_more)
        navItems.clear()
        for (i in names.indices) {
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
                setPadding(0, dp(4), 0, dp(4))
            }
            val ic = ImageView(this).apply {
                setImageResource(icons[i])
                layoutParams = LinearLayout.LayoutParams(dp(26), dp(26))
            }
            val tv = TextView(this).apply {
                text = names[i]
                textSize = 11f
                gravity = Gravity.CENTER
            }
            item.addView(ic)
            item.addView(tv)
            item.setOnClickListener { selectTab(i) }
            navItems.add(Pair(ic, tv))
            bar.addView(item)
        }
        return bar
    }

    private fun styleNav() {
        for (i in navItems.indices) {
            val (ic, tv) = navItems[i]
            val c = if (i == tab) BLUE else SUB
            ic.setColorFilter(c)
            tv.setTextColor(c)
            val s = if (i == tab) 1.18f else 1f
            ic.animate().scaleX(s).scaleY(s).setDuration(160).start()
        }
    }

    private fun selectTab(i: Int) {
        val same = (i == tab && pages.childCount > 0)
        tab = i
        if (!same) {
            pages.removeAllViews()
            pages.addView(pageViews[i]!!, FrameLayout.LayoutParams(-1, -1))
            pages.alpha = 0f
            pages.animate().alpha(1f).setDuration(180).start()
        }
        styleNav()
        if (i == 1) loadLibrary()
        if (i == 2) {
            val o = plOpen
            if (o != null) showPlaylistDetail(o) else showPlaylistList()
        }
    }

    // ---------- mini player ----------
    private fun buildMini(): View {
        mini = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#1B2340"))
            visibility = View.GONE
        }
        miniBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            progressTintList = ColorStateList.valueOf(BLUE)
            layoutParams = LinearLayout.LayoutParams(-1, dp(2))
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(6), dp(4), dp(6))
        }
        miniArt = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(42), dp(42))
            background = rr(CARD, 8)
            clipToOutline = true
        }
        miniTitle = label("", 14f, TXT, true).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        miniSub = label("", 12f, SUB).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(10); marginEnd = dp(6) }
            addView(miniTitle)
            addView(miniSub)
        }
        miniPlay = iconBtn(R.drawable.ic_play, 44, 10, TXT) { Engine.toggle() }
        row.addView(miniArt)
        row.addView(col)
        row.addView(miniPlay)
        row.addView(iconBtn(R.drawable.ic_next, 44, 10, TXT) { Engine.next() })
        row.setOnClickListener { showNowPlaying() }
        mini.addView(miniBar)
        mini.addView(row)
        return mini
    }

    private fun updateMini() {
        val t = Engine.current
        if (t == null) {
            mini.visibility = View.GONE
            return
        }
        mini.visibility = View.VISIBLE
        miniTitle.text = t.title
        miniSub.text = if (Engine.error.isNotEmpty()) Engine.error else if (Engine.preparing) "Loading..." else t.artist
        miniPlay.setImageResource(if (Engine.playing) R.drawable.ic_pause else R.drawable.ic_play)
        bindArt(miniArt, t.thumb, 9)
    }

    private fun updateProgress() {
        if (Engine.current == null) return
        val d = Engine.dur()
        miniBar.progress = if (d > 0) (Engine.pos().toLong() * 1000 / d).toInt() else 0
        npRefresh?.invoke()
    }

    // ---------- Now Playing ----------
    private fun showNowPlaying() {
        if (Engine.current == null) return
        val dlg = Dialog(this, android.R.style.Theme_Material_NoActionBar)
        dlg.window?.apply {
            setLayout(-1, -1)
            statusBarColor = BG
            navigationBarColor = BG
            setWindowAnimations(android.R.style.Animation_Dialog)
        }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setBackgroundColor(BG)
            setPadding(dp(24), dp(12), dp(24), dp(24))
        }
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(iconBtn(R.drawable.ic_back, 44, 10, TXT) { dlg.dismiss() })
        top.addView(label("Now playing", 16f, SUB, true).apply { setPadding(dp(8), 0, 0, 0) })
        val art = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(270), dp(270)).apply { topMargin = dp(20) }
            background = rr(CARD, 24)
            clipToOutline = true
        }
        val titleTv = label("", 20f, TXT, true).apply {
            gravity = Gravity.CENTER
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, dp(22), 0, 0)
        }
        val artistTv = label("", 14f, SUB).apply {
            gravity = Gravity.CENTER
            maxLines = 1
            setPadding(0, dp(4), 0, dp(12))
        }
        val seek = SeekBar(this).apply {
            progressTintList = ColorStateList.valueOf(BLUE)
            thumbTintList = ColorStateList.valueOf(BLUE)
            max = 1
        }
        val curT = label("0:00", 12f, SUB)
        val totT = label("0:00", 12f, SUB)
        val times = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(curT)
            addView(View(this@MainActivity), LinearLayout.LayoutParams(0, 1, 1f))
            addView(totT)
        }
        var tracking = false
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                if (fromUser) curT.text = fmtMs(p)
            }

            override fun onStartTrackingTouch(sb: SeekBar?) {
                tracking = true
            }

            override fun onStopTrackingTouch(sb: SeekBar?) {
                tracking = false
                Engine.seekTo(sb?.progress ?: 0)
            }
        })
        val shuf = iconBtn(R.drawable.ic_shuffle, 48, 12, SUB) { Engine.shuffle = !Engine.shuffle; Engine.notifyChange() }
        val prev = iconBtn(R.drawable.ic_prev, 56, 14, TXT) { Engine.prev() }
        val play = iconBtn(R.drawable.ic_play, 72, 20, Color.WHITE) { Engine.toggle() }
        play.background = rr(BLUE, 36)
        val next = iconBtn(R.drawable.ic_next, 56, 14, TXT) { Engine.next() }
        val rep = iconBtn(R.drawable.ic_repeat, 48, 12, SUB) { Engine.repeat = (Engine.repeat + 1) % 3; Engine.notifyChange() }
        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(16), 0, 0)
            addView(shuf); addView(prev); addView(play); addView(next); addView(rep)
        }
        val caption = label("", 12f, SUB).apply { gravity = Gravity.CENTER; setPadding(0, dp(12), 0, 0) }
        col.addView(top)
        col.addView(art)
        col.addView(titleTv)
        col.addView(artistTv)
        col.addView(seek)
        col.addView(times)
        col.addView(controls)
        col.addView(caption)

        var lastKey = ""
        val upd: () -> Unit = {
            val t = Engine.current
            if (t == null) {
                dlg.dismiss()
            } else {
                val key = t.uri + "|" + t.yt + "|" + t.title
                if (key != lastKey) {
                    lastKey = key
                    bindArt(art, t.thumb, 70)
                    titleTv.text = t.title
                }
                artistTv.text = if (Engine.error.isNotEmpty()) Engine.error else if (Engine.preparing) "Loading..." else t.artist
                play.setImageResource(if (Engine.playing) R.drawable.ic_pause else R.drawable.ic_play)
                val d = Engine.dur()
                seek.max = if (d > 0) d else 1
                if (!tracking) {
                    seek.progress = Engine.pos()
                    curT.text = fmtMs(Engine.pos())
                }
                totT.text = fmtMs(d)
                shuf.setColorFilter(if (Engine.shuffle) BLUE else SUB)
                rep.setColorFilter(if (Engine.repeat > 0) BLUE else SUB)
                caption.text = "Shuffle " + (if (Engine.shuffle) "on" else "off") + "  \u00B7  Repeat " +
                    (when (Engine.repeat) { 1 -> "all"; 2 -> "one"; else -> "off" }) +
                    "  \u00B7  " + (Engine.index + 1) + "/" + Engine.queue.size
            }
            Unit
        }
        npRefresh = upd
        dlg.setOnDismissListener { npRefresh = null }
        dlg.setContentView(col)
        dlg.show()
        upd()
    }

    // ---------- video player ----------
    private fun showVideo(title: String, ytUrl: String?, uri: Uri?) {
        Engine.pause()
        val dlg = Dialog(this, android.R.style.Theme_Material_NoActionBar)
        dlg.window?.apply {
            setLayout(-1, -1)
            statusBarColor = Color.BLACK
            navigationBarColor = Color.BLACK
        }
        val frame = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        val vv = VideoView(this)
        frame.addView(vv, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))
        val spin = ProgressBar(this)
        frame.addView(spin, FrameLayout.LayoutParams(dp(48), dp(48), Gravity.CENTER))
        val close = iconBtn(R.drawable.ic_close, 44, 11, Color.WHITE) { dlg.dismiss() }
        close.background = rr(Color.parseColor("#66000000"), 22)
        frame.addView(close, FrameLayout.LayoutParams(dp(44), dp(44), Gravity.TOP or Gravity.END).apply {
            topMargin = dp(12); marginEnd = dp(12)
        })
        val tv = label(title, 14f, Color.WHITE, true).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        frame.addView(tv, FrameLayout.LayoutParams(-1, -2, Gravity.TOP or Gravity.START).apply {
            topMargin = dp(22); marginStart = dp(16); marginEnd = dp(70)
        })
        val mc = MediaController(this)
        vv.setMediaController(mc)
        vv.setOnPreparedListener {
            spin.visibility = View.GONE
            it.start()
        }
        vv.setOnErrorListener { _, _, _ ->
            spin.visibility = View.GONE
            toast("Cannot play this video")
            true
        }
        dlg.setOnDismissListener {
            try {
                vv.stopPlayback()
            } catch (e: Exception) {
            }
        }
        dlg.setContentView(frame)
        dlg.show()
        if (uri != null) {
            vv.setVideoURI(uri)
        } else {
            Thread {
                try {
                    val (u, h) = Resolver.stream(ytUrl ?: "", true)
                    runOnUiThread { vv.setVideoURI(Uri.parse(u), h) }
                } catch (e: Exception) {
                    runOnUiThread { spin.visibility = View.GONE; toast("Could not load the preview") }
                }
            }.start()
        }
    }

    // ---------- Search page ----------
    private fun buildSearchPage(): View {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(2), dp(16), 0)
        }
        input = EditText(this).apply {
            background = null
            hint = "Search or paste a link"
            setHintTextColor(SUB)
            setTextColor(TXT)
            textSize = 16f
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setPadding(dp(16), 0, dp(8), 0)
            layoutParams = LinearLayout.LayoutParams(0, -1, 1f)
        }
        input.setOnEditorActionListener { _, _, _ -> doSearch(); true }
        val go = ImageView(this).apply {
            setImageResource(R.drawable.ic_search)
            setColorFilter(Color.WHITE)
            setPadding(dp(10), dp(10), dp(10), dp(10))
            background = rr(BLUE, 22)
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
        }
        go.setOnClickListener { doSearch() }
        val pill = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rr(CARD, 26)
            setPadding(dp(4), dp(4), dp(4), dp(4))
            layoutParams = LinearLayout.LayoutParams(-1, dp(52)).apply { topMargin = dp(4) }
            addView(input)
            addView(go)
        }
        val chipRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(12), 0, dp(2))
        }
        val labels = listOf("MP3", "M4A", "Video MP4")
        for (i in labels.indices) {
            val c = TextView(this).apply {
                text = labels[i]
                textSize = 13f
                setTypeface(typeface, Typeface.BOLD)
                setPadding(dp(16), dp(8), dp(16), dp(8))
                layoutParams = LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(8) }
            }
            c.setOnClickListener { fmt = i; styleChips(chips, fmt) }
            chips.add(c)
            chipRow.addView(c)
        }
        styleChips(chips, fmt)
        msg = label("Preparing engine (first launch takes a few seconds)...", 14f, SUB).apply {
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(40), dp(8), dp(8))
        }
        list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, dp(16))
        }
        val inner = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        inner.addView(msg)
        inner.addView(list)
        val scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            addView(inner)
        }
        col.addView(pill)
        col.addView(chipRow)
        col.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        return col
    }

    private fun styleChips(cs: List<TextView>, sel: Int) {
        for (i in cs.indices) {
            cs[i].background = rr(if (i == sel) BLUE else CARD, 18)
            cs[i].setTextColor(if (i == sel) Color.WHITE else SUB)
        }
    }

    private fun isUrl(q: String) = q.startsWith("http://") || q.startsWith("https://") || q.contains("youtu.be/") || q.contains("youtube.com/")

    private fun doSearch() {
        val q = input.text.toString().trim()
        if (q.isEmpty()) return
        hideKeyboard(input)
        list.removeAllViews()
        refs.clear()
        msg.text = "Searching..."
        msg.visibility = View.VISIBLE
        Thread {
            try {
                App.ready.await()
                App.initError?.let { throw Exception(it) }
                val req = if (isUrl(q)) {
                    val r = YoutubeDLRequest(if (q.startsWith("http")) q else "https://$q")
                    r.addOption("-J")
                    if (q.contains("v=") || q.contains("youtu.be/") || q.contains("/shorts/")) r.addOption("--no-playlist")
                    else r.addOption("--flat-playlist")
                    r
                } else {
                    val r = YoutubeDLRequest("ytsearch20:$q")
                    r.addOption("--flat-playlist")
                    r.addOption("-J")
                    r
                }
                val out = YoutubeDL.getInstance().execute(request = req, callback = { _, _, _ -> }).out
                val j = JSONObject(out.substring(out.indexOf('{')))
                val items = mutableListOf<Item>()
                val arr = j.optJSONArray("entries")
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        val e = arr.optJSONObject(i) ?: continue
                        parseEntry(e)?.let { items.add(it) }
                    }
                } else {
                    parseEntry(j)?.let { items.add(it) }
                }
                runOnUiThread { showResults(items) }
            } catch (e: Exception) {
                runOnUiThread {
                    msg.text = "Could not load results.\n" + (e.message ?: "unknown error").take(240)
                    msg.visibility = View.VISIBLE
                }
            }
        }.start()
    }

    private fun parseEntry(j: JSONObject): Item? {
        val id = j.optString("id")
        if (id.isEmpty()) return null
        val url = j.optString("webpage_url").ifEmpty { j.optString("url") }.ifEmpty { "https://www.youtube.com/watch?v=$id" }
        val title = j.optString("title").ifEmpty { "Unknown title" }
        val ch = j.optString("channel").ifEmpty { j.optString("uploader") }
        val dur = j.optLong("duration", 0L)
        val isYt = url.contains("youtube.com") || url.contains("youtu.be")
        val th = if (isYt) "https://i.ytimg.com/vi/$id/mqdefault.jpg" else j.optString("thumbnail")
        return Item(id, title, ch, dur, url, th)
    }

    private fun showResults(items: List<Item>) {
        list.removeAllViews()
        refs.clear()
        if (items.isEmpty()) {
            msg.text = "No results."
            msg.visibility = View.VISIBLE
            return
        }
        msg.visibility = View.GONE
        for (it in items) addRow(it)
        list.alpha = 0f
        list.animate().alpha(1f).setDuration(220).start()
    }

    private fun actionChip(res: Int, text: String, bg: Int, click: () -> Unit): ChipV {
        val ic = ImageView(this).apply {
            setImageResource(res)
            setColorFilter(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(dp(18), dp(18))
        }
        val tv = label(text, 13f, Color.WHITE, true).apply { setPadding(dp(6), 0, 0, 0) }
        val v = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rr(bg, 18)
            setPadding(dp(12), dp(8), dp(14), dp(8))
            layoutParams = LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(8) }
            setOnClickListener { click() }
        }
        v.addView(ic)
        v.addView(tv)
        return ChipV(v, ic, tv)
    }

    private fun trackOf(item: Item): Track {
        val s = item.saved
        return Track(item.title, item.channel, item.dur, s?.toString() ?: "", if (s != null) "" else item.url, item.thumb)
    }

    private fun addRow(item: Item) {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rr(CARD, 16)
            setPadding(dp(10), dp(10), dp(10), dp(10))
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) }
        }
        val topRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val thumb = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(Color.parseColor("#222A40"))
            layoutParams = LinearLayout.LayoutParams(dp(112), dp(63))
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(v: View, o: Outline) {
                    o.setRoundRect(0, 0, v.width, v.height, dp(10).toFloat())
                }
            }
        }
        loadThumb(item.thumb, thumb)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(12) }
        }
        col.addView(label(item.title, 14f, TXT, true).apply {
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        })
        val sub = listOf(item.channel, fmtDur(item.dur)).filter { it.isNotEmpty() }.joinToString(" \u00B7 ")
        col.addView(label(sub, 12f, SUB).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, dp(2), 0, 0)
        })
        topRow.addView(thumb)
        topRow.addView(col)
        val status = label("", 12f, SUB).apply {
            visibility = View.GONE
            setPadding(0, dp(8), 0, 0)
        }
        val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progressTintList = ColorStateList.valueOf(BLUE)
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(-1, dp(3)).apply { topMargin = dp(4) }
        }
        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(10), 0, 0)
        }
        val pv = actionChip(R.drawable.ic_play, "Preview", CHIP) { preview(item) }
        val dl = actionChip(R.drawable.ic_download, "Download", BLUE) {
            when (item.state) {
                0, 3 -> startDownload(item)
                2 -> playItem(item)
            }
        }
        val pl = actionChip(R.drawable.ic_playlist_add, "Playlist", CHIP) { addToPlaylist(trackOf(item)) }
        actions.addView(pv.view)
        actions.addView(dl.view)
        actions.addView(pl.view)
        card.addView(topRow)
        card.addView(status)
        card.addView(bar)
        card.addView(actions)
        list.addView(card)
        refs[item] = RowRef(status, bar, dl)
        refresh(item)
    }

    private fun refresh(item: Item) {
        val r = refs[item] ?: return
        when (item.state) {
            0 -> {
                r.status.visibility = View.GONE
                r.bar.visibility = View.GONE
                r.dl.icon.setImageResource(R.drawable.ic_download)
                r.dl.label.text = "Download"
                r.dl.view.alpha = 1f
            }
            1 -> {
                r.status.visibility = View.VISIBLE
                r.status.setTextColor(SUB)
                r.status.text = if (item.progress >= 99.9f) "Converting..." else if (item.progress > 0f) item.progress.toInt().toString() + "%" else "Starting..."
                r.bar.visibility = View.VISIBLE
                r.bar.progress = item.progress.toInt()
                r.dl.label.text = "Downloading"
                r.dl.view.alpha = 0.5f
            }
            2 -> {
                r.status.visibility = View.VISIBLE
                r.status.setTextColor(OKC)
                r.status.text = "Saved \u2713"
                r.bar.visibility = View.GONE
                r.dl.icon.setImageResource(R.drawable.ic_play)
                r.dl.label.text = "Play"
                r.dl.view.alpha = 1f
            }
            else -> {
                r.status.visibility = View.VISIBLE
                r.status.setTextColor(RED)
                r.status.text = item.err
                r.bar.visibility = View.GONE
                r.dl.icon.setImageResource(R.drawable.ic_retry)
                r.dl.label.text = "Retry"
                r.dl.view.alpha = 1f
            }
        }
    }

    private fun preview(item: Item) {
        if (fmt == 2) {
            showVideo(item.title, item.url, null)
        } else {
            ensureNotifPerm()
            toast("Loading preview...")
            Engine.playQueue(listOf(Track(item.title, item.channel, item.dur, "", item.url, item.thumb)), 0)
        }
    }

    private fun playItem(item: Item) {
        val u = item.saved ?: return
        if (item.mime.startsWith("video")) showVideo(item.title, null, u)
        else {
            ensureNotifPerm()
            Engine.playQueue(listOf(trackOf(item)), 0)
        }
    }

    // ---------- download ----------
    private fun startDownload(item: Item) {
        if (item.state == 1) return
        item.state = 1
        item.progress = 0f
        refresh(item)
        active++
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val f = fmt
        Thread {
            var tmp: File? = null
            try {
                App.ready.await()
                App.initError?.let { throw Exception(it) }
                val dir = File(getExternalFilesDir(null), "tmp/" + System.currentTimeMillis())
                dir.mkdirs()
                tmp = dir
                val req = YoutubeDLRequest(item.url)
                req.addOption("--no-playlist")
                req.addOption("-o", dir.absolutePath + "/%(id)s.%(ext)s")
                req.addOption("--embed-metadata")
                when (f) {
                    0 -> {
                        req.addOption("-x")
                        req.addOption("--audio-format", "mp3")
                        req.addOption("--audio-quality", "0")
                    }
                    1 -> {
                        req.addOption("-f", "bestaudio[ext=m4a]/bestaudio")
                        req.addOption("-x")
                        req.addOption("--audio-format", "m4a")
                    }
                    else -> {
                        req.addOption("-f", "bv*[height<=720]+ba/b[height<=720]/b")
                        req.addOption("--merge-output-format", "mp4")
                    }
                }
                YoutubeDL.getInstance().execute(request = req, callback = { p, _, _ ->
                    item.progress = p
                    runOnUiThread { refresh(item) }
                })
                val out = dir.listFiles()
                    ?.filter { it.isFile && it.extension.lowercase() in listOf("mp3", "m4a", "mp4", "webm", "opus", "mkv", "aac", "ogg") }
                    ?.maxByOrNull { it.length() }
                    ?: throw Exception("No output file was produced")
                val uri = saveToStore(out, item.title) ?: throw Exception("Could not save the file")
                item.saved = uri
                item.mime = contentResolver.getType(uri) ?: ""
                item.state = 2
                libDirty = true
            } catch (e: Exception) {
                item.state = 3
                item.err = (e.message ?: "Download failed").lines().lastOrNull { it.isNotBlank() }?.take(160) ?: "Download failed"
            } finally {
                tmp?.deleteRecursively()
                runOnUiThread {
                    active--
                    if (active <= 0) window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    refresh(item)
                }
            }
        }.start()
    }

    private fun saveToStore(f: File, title: String): Uri? {
        val ext = f.extension.lowercase()
        val mime = when (ext) {
            "mp3" -> "audio/mpeg"
            "m4a" -> "audio/mp4"
            "aac" -> "audio/aac"
            "opus", "ogg" -> "audio/ogg"
            "mp4" -> "video/mp4"
            "webm" -> "video/webm"
            "mkv" -> "video/x-matroska"
            else -> "application/octet-stream"
        }
        val video = mime.startsWith("video")
        val cv = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, safeName(title) + "." + ext)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, (if (video) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_MUSIC) + "/DarkX YT")
        }
        val coll = if (video) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val uri = contentResolver.insert(coll, cv) ?: return null
        contentResolver.openOutputStream(uri)?.use { out -> f.inputStream().use { it.copyTo(out) } }
        return uri
    }

    // ---------- Library page ----------
    inner class TrackAdapter(
        val data: () -> List<Track>,
        val onTap: (Int) -> Unit,
        val onAct: (Track) -> Unit,
        val actIcon: () -> Int
    ) : BaseAdapter() {
        override fun getCount(): Int = data().size
        override fun getItem(p: Int): Any = data()[p]
        override fun getItemId(p: Int): Long = p.toLong()
        override fun getView(p: Int, cv: View?, parent: ViewGroup?): View {
            val row = cv ?: buildTrackRow()
            val h = row.tag as TrackHolder
            val t = data()[p]
            h.title.text = t.title
            h.title.setTextColor(if (isCurrent(t)) BLUE else TXT)
            h.sub.text = listOf(t.artist, fmtDur(t.dur)).filter { it.isNotEmpty() }.joinToString(" \u00B7 ")
            bindArt(h.art, t.thumb, 11)
            val a = actIcon()
            if (a == 0) {
                h.act.visibility = View.GONE
            } else {
                h.act.visibility = View.VISIBLE
                h.act.setImageResource(a)
                h.act.setOnClickListener { onAct(t) }
            }
            row.setOnClickListener { onTap(p) }
            return row
        }
    }

    private fun buildTrackRow(): View {
        val art = ImageView(this).apply {
            background = rr(CARD, 10)
            clipToOutline = true
            layoutParams = LinearLayout.LayoutParams(dp(46), dp(46))
        }
        val title = label("", 15f, TXT).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        val sub = label("", 12f, SUB).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(12) }
            addView(title)
            addView(sub)
        }
        val act = ImageView(this).apply {
            setColorFilter(SUB)
            setPadding(dp(11), dp(11), dp(11), dp(11))
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(6), dp(8), dp(6))
            addView(art)
            addView(col)
            addView(act)
        }
        row.tag = TrackHolder(art, title, sub, act)
        return row
    }

    private fun buildLibraryPage(): View {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val chipRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(16), dp(4), dp(16), dp(6))
        }
        val labels = listOf("All music", "Downloads", "Videos")
        for (i in labels.indices) {
            val c = TextView(this).apply {
                text = labels[i]
                textSize = 13f
                setTypeface(typeface, Typeface.BOLD)
                setPadding(dp(16), dp(8), dp(16), dp(8))
                layoutParams = LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(8) }
            }
            c.setOnClickListener {
                libSrc = i
                styleChips(libChips, libSrc)
                libDirty = true
                loadLibrary()
            }
            libChips.add(c)
            chipRow.addView(c)
        }
        styleChips(libChips, libSrc)
        libFilter = EditText(this).apply {
            background = rr(CARD, 22)
            hint = "Filter songs"
            setHintTextColor(SUB)
            setTextColor(TXT)
            textSize = 14f
            isSingleLine = true
            setPadding(dp(16), 0, dp(16), 0)
            layoutParams = LinearLayout.LayoutParams(-1, dp(44)).apply { marginStart = dp(16); marginEnd = dp(16) }
        }
        libFilter.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                applyFilter()
            }
        })
        libMsg = label("", 14f, SUB).apply {
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(24), dp(16), dp(8))
            visibility = View.GONE
        }
        libAllow = label("Allow access", 14f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER
            background = rr(BLUE, 22)
            setPadding(dp(24), dp(12), dp(24), dp(12))
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(-2, -2).apply { gravity = Gravity.CENTER_HORIZONTAL }
            setOnClickListener { askStorage() }
        }
        libAdapter = TrackAdapter(
            { libShown },
            { i ->
                val t = libShown[i]
                if (libSrc == 2) {
                    showVideo(t.title, null, Uri.parse(t.uri))
                } else {
                    ensureNotifPerm()
                    Engine.playQueue(libShown.toList(), i)
                }
            },
            { t -> addToPlaylist(t) },
            { if (libSrc == 2) 0 else R.drawable.ic_playlist_add }
        )
        libList = ListView(this).apply {
            divider = null
            adapter = libAdapter
            isVerticalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(6) }
        }
        col.addView(chipRow)
        col.addView(libFilter)
        col.addView(libMsg)
        col.addView(libAllow)
        col.addView(libList)
        return col
    }

    private fun applyFilter() {
        val q = libFilter.text.toString().trim().lowercase()
        libShown = if (q.isEmpty()) libTracks else libTracks.filter { it.title.lowercase().contains(q) || it.artist.lowercase().contains(q) }
        libAdapter.notifyDataSetChanged()
    }

    private fun loadLibrary() {
        if (!hasStorage()) {
            libTracks = emptyList()
            libShown = emptyList()
            libAdapter.notifyDataSetChanged()
            libMsg.text = "Allow access to your music to browse and play it here."
            libMsg.visibility = View.VISIBLE
            libAllow.visibility = View.VISIBLE
            return
        }
        libAllow.visibility = View.GONE
        if (!libDirty) return
        libDirty = false
        libMsg.text = "Loading..."
        libMsg.visibility = View.VISIBLE
        val src = libSrc
        Thread {
            val res = queryMedia(src)
            runOnUiThread {
                if (src == libSrc) {
                    libTracks = res
                    applyFilter()
                    libMsg.text = "Nothing here yet."
                    libMsg.visibility = if (res.isEmpty()) View.VISIBLE else View.GONE
                }
            }
        }.start()
    }

    private fun queryMedia(src: Int): List<Track> {
        val out = mutableListOf<Track>()
        val video = src == 2
        val coll = if (video) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val proj = if (video) arrayOf(MediaStore.Video.Media._ID, MediaStore.Video.Media.TITLE, MediaStore.Video.Media.DURATION)
        else arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST, MediaStore.Audio.Media.DURATION)
        var sel: String? = null
        var args: Array<String>? = null
        if (video) {
            sel = MediaStore.MediaColumns.RELATIVE_PATH + " LIKE ?"
            args = arrayOf("Movies/DarkX YT%")
        } else if (src == 1) {
            sel = MediaStore.MediaColumns.RELATIVE_PATH + " LIKE ?"
            args = arrayOf("Music/DarkX YT%")
        } else {
            sel = MediaStore.Audio.Media.IS_MUSIC + " != 0"
        }
        val order = if (src == 0) null else MediaStore.MediaColumns.DATE_ADDED + " DESC"
        try {
            contentResolver.query(coll, proj, sel, args, order)?.use { c ->
                val iId = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val iTitle = c.getColumnIndexOrThrow(MediaStore.MediaColumns.TITLE)
                val iDur = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DURATION)
                val iArt = if (video) -1 else c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                while (c.moveToNext()) {
                    val id = c.getLong(iId)
                    var artist = if (iArt >= 0) (c.getString(iArt) ?: "") else ""
                    if (artist == "<unknown>") artist = ""
                    out.add(
                        Track(
                            c.getString(iTitle) ?: "Unknown", artist, c.getLong(iDur) / 1000,
                            ContentUris.withAppendedId(coll, id).toString(), "", ""
                        )
                    )
                }
            }
        } catch (e: Exception) {
        }
        if (src == 0) out.sortBy { it.title.lowercase() }
        return out
    }

    // ---------- Playlists ----------
    private fun savePlaylists() = Store.save(this, playlists)

    private fun buildPlaylistPage(): View {
        plPage = FrameLayout(this)
        return plPage
    }

    private fun showPlaylistList() {
        plOpen = null
        plAdapter = null
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(4), dp(16), 0)
        }
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        head.addView(label("Playlists", 20f, TXT, true).apply { layoutParams = LinearLayout.LayoutParams(0, -2, 1f) })
        val add = actionChip(R.drawable.ic_add, "New", BLUE) { newPlaylistDialog { showPlaylistList() } }
        head.addView(add.view)
        col.addView(head)
        val inner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, dp(16))
        }
        if (playlists.isEmpty()) {
            inner.addView(label("No playlists yet.\nTap New to create one, then add songs with the playlist button.", 14f, SUB).apply {
                gravity = Gravity.CENTER
                setPadding(dp(8), dp(40), dp(8), 0)
            })
        }
        for (pl in playlists) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = rr(CARD, 16)
                setPadding(dp(12), dp(10), dp(6), dp(10))
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) }
                setOnClickListener { showPlaylistDetail(pl) }
            }
            val ic = ImageView(this).apply {
                setImageResource(R.drawable.ic_list)
                setColorFilter(BLUE)
                setPadding(dp(11), dp(11), dp(11), dp(11))
                background = rr(CHIP, 12)
                layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
            }
            val c = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(12) }
                addView(label(pl.name, 16f, TXT, true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
                addView(label(pl.tracks.size.toString() + " songs", 12f, SUB))
            }
            row.addView(ic)
            row.addView(c)
            row.addView(iconBtn(R.drawable.ic_play, 44, 11, BLUE) { playPlaylist(pl, false) })
            inner.addView(row)
        }
        col.addView(ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            addView(inner)
        }, LinearLayout.LayoutParams(-1, 0, 1f))
        plPage.removeAllViews()
        plPage.addView(col, FrameLayout.LayoutParams(-1, -1))
    }

    private fun showPlaylistDetail(pl: Playlist) {
        plOpen = pl
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(4), 0, 0)
        }
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), 0, dp(8), 0)
        }
        head.addView(iconBtn(R.drawable.ic_back, 44, 10, TXT) { showPlaylistList() })
        head.addView(label(pl.name, 18f, TXT, true).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        })
        head.addView(iconBtn(R.drawable.ic_shuffle, 44, 11, BLUE) { playPlaylist(pl, true) })
        head.addView(iconBtn(R.drawable.ic_play, 44, 11, BLUE) { playPlaylist(pl, false) })
        head.addView(iconBtn(R.drawable.ic_delete, 44, 11, RED) {
            AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("Delete playlist?")
                .setMessage(pl.name)
                .setPositiveButton("Delete") { _, _ ->
                    playlists.remove(pl)
                    savePlaylists()
                    showPlaylistList()
                }
                .setNegativeButton("Cancel", null).show()
        })
        val empty = label("This playlist is empty.\nAdd songs from Library or Search.", 14f, SUB).apply {
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(40), dp(16), 0)
            visibility = if (pl.tracks.isEmpty()) View.VISIBLE else View.GONE
        }
        lateinit var ad: TrackAdapter
        ad = TrackAdapter(
            { pl.tracks },
            { i ->
                ensureNotifPerm()
                Engine.playQueue(pl.tracks.toList(), i)
            },
            { t ->
                pl.tracks.remove(t)
                savePlaylists()
                ad.notifyDataSetChanged()
                empty.visibility = if (pl.tracks.isEmpty()) View.VISIBLE else View.GONE
            },
            { R.drawable.ic_close }
        )
        plAdapter = ad
        val lv = ListView(this).apply {
            divider = null
            adapter = ad
            isVerticalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(-1, 0, 1f)
        }
        col.addView(head)
        col.addView(empty)
        col.addView(lv)
        plPage.removeAllViews()
        plPage.addView(col, FrameLayout.LayoutParams(-1, -1))
    }

    private fun playPlaylist(pl: Playlist, shuffle: Boolean) {
        if (pl.tracks.isEmpty()) {
            toast("Playlist is empty")
            return
        }
        ensureNotifPerm()
        Engine.shuffle = shuffle
        Engine.playQueue(pl.tracks.toList(), if (shuffle) pl.tracks.indices.random() else 0)
    }

    private fun newPlaylistDialog(done: (Playlist) -> Unit) {
        val et = EditText(this).apply {
            hint = "Playlist name"
            isSingleLine = true
            setPadding(dp(24), dp(16), dp(24), dp(16))
        }
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("New playlist")
            .setView(et)
            .setPositiveButton("Create") { _, _ ->
                val n = et.text.toString().trim()
                if (n.isEmpty()) {
                    toast("Enter a name")
                } else {
                    val p = Playlist(n)
                    playlists.add(p)
                    savePlaylists()
                    done(p)
                }
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun addTo(pl: Playlist, t: Track) {
        if (pl.tracks.any { it.same(t) }) {
            toast("Already in " + pl.name)
            return
        }
        pl.tracks.add(t)
        savePlaylists()
        toast("Added to " + pl.name)
    }

    private fun addToPlaylist(t: Track) {
        val names = playlists.map { it.name }.toTypedArray() + "+ New playlist"
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("Add to playlist")
            .setItems(names) { _, i ->
                if (i == playlists.size) newPlaylistDialog { addTo(it, t) }
                else addTo(playlists[i], t)
            }
            .show()
    }

    // ---------- More page ----------
    private fun settingRow(title: String, sub: String, click: () -> Unit): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rr(CARD, 14)
            setPadding(dp(16), dp(14), dp(16), dp(14))
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) }
            setOnClickListener { click() }
        }
        row.addView(label(title, 15f, TXT, true))
        row.addView(label(sub, 12f, SUB).apply { setPadding(0, dp(2), 0, 0) })
        return row
    }

    private fun buildMorePage(): View {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(4), dp(16), dp(16))
        }
        col.addView(label("More", 20f, TXT, true))
        col.addView(settingRow("Update yt-dlp", "Keeps downloads working when YouTube changes") {
            toast("Updating yt-dlp...")
            Thread {
                val r = App.updateYtDlp(applicationContext)
                runOnUiThread { toast("yt-dlp: " + (r ?: "update failed")) }
            }.start()
        })
        col.addView(settingRow("Check for app update", "Installed version: v" + BuildConfig.VERSION_CODE) { checkUpdate(true) })
        col.addView(settingRow("Storage access", "Allow DarkX YT to browse the music on your phone") {
            if (hasStorage()) toast("Access already granted") else askStorage()
        })
        col.addView(settingRow("Stop playback", "Stop the music player and hide the notification") { Engine.stop() })
        col.addView(label("Download only content you have the right to use.", 12f, SUB).apply {
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(24), dp(8), 0)
        })
        return ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            addView(col)
        }
    }

    // ---------- auto update (GitHub Releases) ----------
    private fun checkUpdate(manual: Boolean) {
        val repo = BuildConfig.GH_REPO
        if (repo.isEmpty()) {
            if (manual) toast("Updates work only for GitHub-built APKs")
            return
        }
        Thread {
            try {
                val c = URL("https://api.github.com/repos/$repo/releases/latest").openConnection() as HttpURLConnection
                c.connectTimeout = 6000
                c.readTimeout = 6000
                c.setRequestProperty("Accept", "application/vnd.github+json")
                val j = JSONObject(c.inputStream.bufferedReader().readText())
                val ver = j.getString("tag_name").removePrefix("v").toIntOrNull() ?: 0
                val assets = j.getJSONArray("assets")
                var apk: String? = null
                for (i in 0 until assets.length()) {
                    val a = assets.getJSONObject(i)
                    if (a.getString("name").endsWith(".apk")) apk = a.getString("browser_download_url")
                }
                val link = apk
                runOnUiThread {
                    if (link != null && ver > BuildConfig.VERSION_CODE) askUpdate(ver, link)
                    else if (manual) toast("You have the latest version")
                }
            } catch (e: Exception) {
                if (manual) runOnUiThread { toast("Update check failed") }
            }
        }.start()
    }

    private fun askUpdate(ver: Int, url: String) {
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("Update available")
            .setMessage("DarkX YT v$ver is ready to install.")
            .setPositiveButton("Update") { _, _ -> downloadApk(url) }
            .setNegativeButton("Later", null)
            .show()
    }

    private fun downloadApk(url: String) {
        if (Build.VERSION.SDK_INT >= 26 && !packageManager.canRequestPackageInstalls()) {
            pendingUrl = url
            toast("Allow \"Install unknown apps\" for DarkX YT, then go back")
            startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
            return
        }
        val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            setPadding(dp(24), dp(16), dp(24), dp(16))
        }
        val dlg = AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("Downloading update").setView(bar).setCancelable(false).create()
        dlg.show()
        Thread {
            try {
                val dir = File(cacheDir, "update")
                dir.mkdirs()
                val f = File(dir, "DarkXYT.apk")
                val c = URL(url).openConnection() as HttpURLConnection
                c.connectTimeout = 10000
                c.readTimeout = 20000
                val total = c.contentLength
                c.inputStream.use { ins ->
                    f.outputStream().use { out ->
                        val buf = ByteArray(32768)
                        var done = 0L
                        var n = 0
                        while (ins.read(buf).also { n = it } > 0) {
                            out.write(buf, 0, n)
                            done += n
                            if (total > 0) {
                                val p = (done * 100 / total).toInt()
                                runOnUiThread { bar.progress = p }
                            }
                        }
                    }
                }
                runOnUiThread { dlg.dismiss(); installApk(f) }
            } catch (e: Exception) {
                runOnUiThread { dlg.dismiss(); toast("Download failed") }
            }
        }.start()
    }

    private fun installApk(f: File) {
        val uri = FileProvider.getUriForFile(this, "com.darkx.yt.fileprovider", f)
        startActivity(Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }
}
