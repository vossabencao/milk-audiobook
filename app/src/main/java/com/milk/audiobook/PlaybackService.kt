package com.milk.audiobook

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null
    private var exo: ExoPlayer? = null
    private val handler = Handler(Looper.getMainLooper())

    private val backCmd = SessionCommand("milk.back10", Bundle.EMPTY)
    private val fwdCmd = SessionCommand("milk.fwd10", Bundle.EMPTY)

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

    private fun seekBy(deltaMs: Long) {
        val p = exo ?: return
        val dur = p.duration
        var target = p.currentPosition + deltaMs
        if (target < 0) target = 0
        if (dur != C.TIME_UNSET && target > dur) target = dur
        p.seekTo(target)
    }

    private fun buttons(): ImmutableList<CommandButton> {
        val back = CommandButton.Builder(CommandButton.ICON_UNDEFINED)
            .setCustomIconResId(R.drawable.ic_back10)
            .setDisplayName("-10s")
            .setSessionCommand(backCmd)
            .build()
        val fwd = CommandButton.Builder(CommandButton.ICON_UNDEFINED)
            .setCustomIconResId(R.drawable.ic_fwd10)
            .setDisplayName("+10s")
            .setSessionCommand(fwdCmd)
            .build()
        return ImmutableList.of(back, fwd)
    }

    private val callback = object : MediaSession.Callback {
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo
        ): MediaSession.ConnectionResult {
            val cmds = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                .add(backCmd)
                .add(fwdCmd)
                .build()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(cmds)
                .setCustomLayout(buttons())
                .build()
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle
        ): ListenableFuture<SessionResult> {
            when (customCommand.customAction) {
                "milk.back10" -> seekBy(-10_000L)
                "milk.fwd10" -> seekBy(10_000L)
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }
    }

    override fun onCreate() {
        super.onCreate()
        val p = ExoPlayer.Builder(this)
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(10_000)
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
        session = MediaSession.Builder(this, p)
            .setCallback(callback)
            .setCustomLayout(buttons())
            .build()
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
