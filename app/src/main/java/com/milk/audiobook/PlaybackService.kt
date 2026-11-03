package com.milk.audiobook

import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null
    private var exo: ExoPlayer? = null
    private val handler = Handler(Looper.getMainLooper())

    private val ticker = object : Runnable {
        override fun run() {
            saveNow()
            handler.postDelayed(this, 3000)
        }
    }

    private fun saveNow() {
        val p = exo ?: return
        val item = p.currentMediaItem ?: return
        Store.save(this, item.mediaId, p.currentPosition, p.duration)
        Store.setLast(this, item.mediaId)
    }

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            handler.removeCallbacks(ticker)
            saveNow()
            if (isPlaying) handler.postDelayed(ticker, 3000)
        }

        override fun onPlaybackStateChanged(state: Int) {
            if (state == Player.STATE_READY || state == Player.STATE_ENDED) saveNow()
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int
        ) {
            val old = oldPosition.mediaItem
            if (old != null && old.mediaId != newPosition.mediaItem?.mediaId) {
                Store.save(this@PlaybackService, old.mediaId, oldPosition.positionMs, 0L)
            }
            saveNow()
        }
    }

    override fun onCreate() {
        super.onCreate()
        val p = ExoPlayer.Builder(this)
            .setSeekBackIncrementMs(15_000)
            .setSeekForwardIncrementMs(30_000)
            .setHandleAudioBecomingNoisy(true)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                    .setUsage(C.USAGE_MEDIA)
                    .build(),
                true
            )
            .build()
        p.addListener(listener)
        exo = p
        session = MediaSession.Builder(this, p).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo) = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        saveNow()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        handler.removeCallbacks(ticker)
        saveNow()
        exo?.release()
        session?.release()
        session = null
        exo = null
        super.onDestroy()
    }
}
