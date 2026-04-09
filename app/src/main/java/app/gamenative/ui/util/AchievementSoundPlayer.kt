package app.gamenative.ui.util

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import app.gamenative.R
import app.gamenative.PrefManager
import timber.log.Timber

/**
 * Plays the achievement unlock sound.
 *
 * Uses the user's chosen custom audio URI when set in [PrefManager.achievementSoundUri],
 * otherwise falls back to the bundled default sound [R.raw.achievement_unlock].
 *
 * Call [play] from any thread; it is safe to call concurrently.
 * The [MediaPlayer] is released automatically once playback completes or on error.
 */
object AchievementSoundPlayer {

    private val audioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_GAME)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    fun play(context: Context) {
        if (!PrefManager.achievementSoundEnabled) return

        val customUri = PrefManager.achievementSoundUri

        try {
            val player = MediaPlayer().apply {
                setAudioAttributes(audioAttributes)
                if (customUri.isNotEmpty()) {
                    setDataSource(context, Uri.parse(customUri))
                } else {
                    val afd = context.resources.openRawResourceFd(R.raw.achievement_unlock)
                    setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                    afd.close()
                }
                setOnCompletionListener { release() }
                setOnErrorListener { mp, what, extra ->
                    Timber.w("AchievementSoundPlayer error what=$what extra=$extra")
                    mp.release()
                    true
                }
                prepare()
                start()
            }
            Timber.d("AchievementSoundPlayer: playing ${if (customUri.isNotEmpty()) "custom ($customUri)" else "default"}")
        } catch (e: Exception) {
            Timber.e(e, "AchievementSoundPlayer: failed to play sound")
        }
    }
}
