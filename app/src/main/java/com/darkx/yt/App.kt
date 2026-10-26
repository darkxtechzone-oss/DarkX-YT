package com.darkx.yt

import android.app.Application
import android.content.Context
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import java.util.concurrent.CountDownLatch

class App : Application() {
    companion object {
        val ready = CountDownLatch(1)

        @Volatile
        var initError: String? = null

        /** Updates the embedded yt-dlp (works with both old and new library signatures). */
        fun updateYtDlp(ctx: Context): String? {
            return try {
                val yd = YoutubeDL.getInstance()
                val m = yd.javaClass.methods.first { it.name == "updateYoutubeDL" }
                val res = if (m.parameterTypes.size == 1) {
                    m.invoke(yd, ctx.applicationContext)
                } else {
                    val ch = m.parameterTypes[1]
                    val inst: Any? = try {
                        ch.getField("_STABLE").get(null)
                    } catch (e: Exception) {
                        try {
                            Class.forName(ch.name + "\$STABLE").getField("INSTANCE").get(null)
                        } catch (e2: Exception) {
                            ch.getField("STABLE").get(null)
                        }
                    }
                    m.invoke(yd, ctx.applicationContext, inst)
                }
                res?.toString() ?: "OK"
            } catch (e: Exception) {
                null
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        Engine.app = applicationContext
        Thread {
            try {
                YoutubeDL.getInstance().init(this)
                FFmpeg.getInstance().init(this)
            } catch (e: Exception) {
                initError = e.toString()
            }
            ready.countDown()
            // keep yt-dlp fresh: YouTube changes often
            updateYtDlp(this)
        }.start()
    }
}
