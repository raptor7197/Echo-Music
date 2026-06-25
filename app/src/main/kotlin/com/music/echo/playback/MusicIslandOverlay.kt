package iad1tya.echo.music.playback

import android.graphics.PixelFormat
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import coil3.asDrawable
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import iad1tya.echo.music.R
import iad1tya.echo.music.constants.DynamicIslandKey
import iad1tya.echo.music.utils.dataStore
import iad1tya.echo.music.utils.get
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Dynamic-island-style floating capsule drawn as a system overlay (SYSTEM_ALERT_WINDOW).
 * Workaround for OEM punch-hole capsules (vivo Mini Capsule etc.) being closed to third-party apps.
 * Collapsed pill near the top centre; tap to expand into prev/play-pause/next/like + seek slider.
 * Lock screen is intentionally NOT covered (overlays are blocked there) — the normal media
 * notification still drives lock screen / shade.
 */
class MusicIslandOverlay(private val service: MusicService) {

    private val context = service
    private val windowManager = service.getSystemService(WindowManager::class.java)
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private var root: View? = null
    private var pill: View? = null
    private var card: View? = null
    private var pillArt: ImageView? = null
    private var pillTitle: TextView? = null
    private var pillPlay: ImageView? = null
    private var pillEq: EqualizerBarsView? = null
    private var cardArt: ImageView? = null
    private var cardTitle: TextView? = null
    private var cardArtist: TextView? = null
    private var cardSeek: SeekBar? = null
    private var cardPos: TextView? = null
    private var cardDur: TextView? = null

    private var added = false
    private var expanded = false
    private var userSeeking = false
    private var lastArtUrl: String? = null

    /** Device has a top display cutout (punch-hole/notch) where the island fits. Cached. */
    private val deviceSupported: Boolean by lazy { Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && hasTopCutout() }

    private fun hasTopCutout(): Boolean {
        return try {
            when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> {
                    val cutout = windowManager.currentWindowMetrics.windowInsets.displayCutout
                    cutout != null && cutout.safeInsetTop > 0
                }
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> {
                    @Suppress("DEPRECATION")
                    val cutout = windowManager.defaultDisplay?.cutout
                    cutout != null && cutout.safeInsetTop > 0
                }
                else -> false // API < 29: no reliable cutout query without an attached view
            }
        } catch (e: Exception) {
            Timber.tag("MusicIsland").w(e, "cutout check failed")
            false
        }
    }

    private fun enabled(): Boolean =
        deviceSupported &&
            context.dataStore.get(DynamicIslandKey, true) &&
            Settings.canDrawOverlays(context)

    private fun buildLayoutParams(): WindowManager.LayoutParams {
        val type =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            // Sit just below the status bar so the whole pill is tappable
            // (SystemUI owns touches in the status-bar strip) while staying near the punch-hole.
            val density = context.resources.displayMetrics.density
            val sbId = context.resources.getIdentifier("status_bar_height", "dimen", "android")
            val statusBar =
                if (sbId > 0) context.resources.getDimensionPixelSize(sbId) else (28 * density).toInt()
            y = statusBar + (4 * density).toInt()
        }
    }

    private fun inflate() {
        if (root != null) return
        val view = LayoutInflater.from(context).inflate(R.layout.overlay_music_island, null)
        pill = view.findViewById(R.id.island_pill)
        card = view.findViewById(R.id.island_card)
        pillArt = view.findViewById(R.id.pill_art)
        pillTitle = view.findViewById(R.id.pill_title)
        pillPlay = view.findViewById(R.id.pill_play)
        pillEq = view.findViewById(R.id.pill_eq)
        cardArt = view.findViewById(R.id.card_art)
        cardTitle = view.findViewById(R.id.card_title)
        cardArtist = view.findViewById(R.id.card_artist)
        cardSeek = view.findViewById(R.id.card_seekbar)
        cardPos = view.findViewById(R.id.card_pos)
        cardDur = view.findViewById(R.id.card_dur)

        pill?.setOnClickListener { setExpanded(true) }
        view.findViewById<View>(R.id.card_close)?.setOnClickListener { setExpanded(false) }
        view.findViewById<View>(R.id.pill_state)?.setOnClickListener { togglePlay() }
        view.findViewById<View>(R.id.card_play_pause)?.setOnClickListener { togglePlay() }
        view.findViewById<View>(R.id.card_prev)?.setOnClickListener {
            runCatching { service.player.seekToPrevious() }
        }
        view.findViewById<View>(R.id.card_next)?.setOnClickListener {
            runCatching { service.player.seekToNext() }
        }
        view.findViewById<View>(R.id.card_like)?.setOnClickListener { service.toggleLike() }

        cardSeek?.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) cardPos?.text = fmt(progress.toLong())
            }

            override fun onStartTrackingTouch(sb: SeekBar?) { userSeeking = true }

            override fun onStopTrackingTouch(sb: SeekBar?) {
                userSeeking = false
                runCatching { service.player.seekTo(sb?.progress?.toLong() ?: 0L) }
            }
        })

        root = view
    }

    private fun togglePlay() {
        runCatching {
            val p = service.player
            if (p.isPlaying) p.pause() else p.play()
        }
    }

    private fun setExpanded(value: Boolean) {
        expanded = value
        pill?.visibility = if (value) View.GONE else View.VISIBLE
        card?.visibility = if (value) View.VISIBLE else View.GONE
    }

    /** Show (or refresh) the island for the current track. Safe to call repeatedly. */
    fun show() {
        if (!enabled()) {
            hide()
            return
        }
        inflate()
        val view = root ?: return
        if (!added) {
            runCatching {
                windowManager.addView(view, buildLayoutParams())
                added = true
            }.onFailure { Timber.tag("MusicIsland").e(it, "addView failed") }
        }
        refreshMetadata()
    }

    fun hide() {
        val view = root
        if (added && view != null) {
            runCatching { windowManager.removeView(view) }
            added = false
        }
        setExpanded(false)
    }

    fun refreshMetadata() {
        if (!added) return
        val meta = service.currentMediaMetadata.value ?: run { hide(); return }
        val title = meta.title
        val artist = meta.artists.joinToString(", ") { it.name }
        pillTitle?.text = title
        cardTitle?.text = title
        cardArtist?.text = artist

        val url = meta.thumbnailUrl
        if (url != lastArtUrl) {
            lastArtUrl = url
            loadArt(url)
        }
        updateLiked()
        updatePlayState(service.player.isPlaying)
    }

    fun updatePlayState(isPlaying: Boolean) {
        if (!added) return
        // Collapsed pill: animated music bars while playing, tappable play icon when paused.
        if (isPlaying) {
            pillEq?.visibility = View.VISIBLE
            pillEq?.start()
            pillPlay?.visibility = View.GONE
        } else {
            pillEq?.stop()
            pillEq?.visibility = View.GONE
            pillPlay?.visibility = View.VISIBLE
        }
        // Expanded card keeps a normal play/pause toggle.
        root?.findViewById<ImageView>(R.id.card_play_pause)
            ?.setImageResource(if (isPlaying) R.drawable.pause else R.drawable.play)
    }

    fun updateLiked() {
        if (!added) return
        val liked = service.currentSongLiked()
        root?.findViewById<ImageView>(R.id.card_like)
            ?.setImageResource(if (liked) R.drawable.ic_heart else R.drawable.ic_heart_outline)
    }

    fun updateProgress(positionMs: Long, durationMs: Long) {
        if (!added || expanded.not()) return
        val dur = if (durationMs > 0) durationMs else 0L
        cardSeek?.max = dur.toInt()
        if (!userSeeking) {
            cardSeek?.progress = positionMs.coerceIn(0, dur).toInt()
            cardPos?.text = fmt(positionMs)
        }
        cardDur?.text = fmt(dur)
    }

    private fun loadArt(url: String?) {
        if (url == null) {
            pillArt?.setImageDrawable(null)
            cardArt?.setImageDrawable(null)
            return
        }
        scope.launch {
            val result = runCatching {
                SingletonImageLoader.get(context)
                    .execute(ImageRequest.Builder(context).data(url).build())
            }.getOrNull()
            val image = (result as? SuccessResult)?.image ?: return@launch
            val drawable = image.asDrawable(context.resources)
            pillArt?.setImageDrawable(drawable)
            cardArt?.setImageDrawable(drawable)
        }
    }

    fun destroy() {
        hide()
        scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        root = null
    }

    private fun fmt(ms: Long): String {
        if (ms <= 0) return "0:00"
        val totalSec = ms / 1000
        return "%d:%02d".format(totalSec / 60, totalSec % 60)
    }
}
