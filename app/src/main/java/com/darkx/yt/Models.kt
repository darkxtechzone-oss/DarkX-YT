package com.darkx.yt

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class Track(
    val title: String,
    val artist: String,
    val dur: Long,
    val uri: String,
    val yt: String,
    val thumb: String
) {
    fun toJson(): JSONObject = JSONObject()
        .put("t", title).put("a", artist).put("d", dur)
        .put("u", uri).put("y", yt).put("th", thumb)

    fun same(o: Track): Boolean =
        if (uri.isNotEmpty()) uri == o.uri else (yt.isNotEmpty() && yt == o.yt)

    companion object {
        fun from(j: JSONObject) = Track(
            j.optString("t"), j.optString("a"), j.optLong("d"),
            j.optString("u"), j.optString("y"), j.optString("th")
        )
    }
}

class Playlist(var name: String, val tracks: MutableList<Track> = mutableListOf())

object Store {
    fun load(c: Context): MutableList<Playlist> {
        val out = mutableListOf<Playlist>()
        try {
            val arr = JSONArray(c.getSharedPreferences("dx", Context.MODE_PRIVATE).getString("playlists", "[]"))
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val p = Playlist(o.getString("name"))
                val ts = o.getJSONArray("tracks")
                for (k in 0 until ts.length()) p.tracks.add(Track.from(ts.getJSONObject(k)))
                out.add(p)
            }
        } catch (e: Exception) {
        }
        return out
    }

    fun save(c: Context, list: List<Playlist>) {
        val arr = JSONArray()
        for (p in list) {
            val ts = JSONArray()
            for (t in p.tracks) ts.put(t.toJson())
            arr.put(JSONObject().put("name", p.name).put("tracks", ts))
        }
        c.getSharedPreferences("dx", Context.MODE_PRIVATE).edit().putString("playlists", arr.toString()).apply()
    }
}
