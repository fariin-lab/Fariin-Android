package com.fariin.app.call

import android.view.View
import io.livekit.android.room.track.Track
import io.livekit.android.room.track.video.VideoSinkVisibility
import io.livekit.android.room.track.video.ViewVisibility

/**
 * LiveKit's own view tracking (on screen or not, how big), but the size it reports is capped at
 * [maxSide] on the long side, so the server sends a small tile on a LOW phone the small layer even
 * when the tile itself is half the screen.
 */
@Suppress("DEPRECATION")
class SmallTileVisibility(view: View, private val maxSide: Int) : VideoSinkVisibility(), java.util.Observer {
    private val inner = ViewVisibility(view)

    init { inner.addObserver(this) }

    override fun update(o: java.util.Observable?, arg: Any?) = notifyChanged()
    override fun isVisible(): Boolean = inner.isVisible()
    override fun size(): Track.Dimensions {
        val d = inner.size()
        val (w, h) = GroupVideoRules.capSize(d.width, d.height, maxSide)
        return Track.Dimensions(w, h)
    }
    override fun close() { inner.deleteObserver(this); inner.close() }
}
