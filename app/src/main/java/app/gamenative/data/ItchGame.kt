package app.gamenative.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import app.gamenative.enums.AppType

/**
 * Itch.io Game entity for Room database
 * Represents a game from the user's itch.io library (owned/purchased/claimed)
 */
@Entity(tableName = "itch_games")
data class ItchGame(
    @PrimaryKey
    @ColumnInfo("id")
    val id: Int = 0,

    @ColumnInfo("title")
    val title: String = "",

    @ColumnInfo("url")
    val url: String = "",

    @ColumnInfo("cover_url")
    val coverUrl: String = "",

    @ColumnInfo("short_text")
    val shortText: String = "",

    @ColumnInfo("developer")
    val developer: String = "",

    @ColumnInfo("p_windows")
    val pWindows: Boolean = false,

    @ColumnInfo("p_linux")
    val pLinux: Boolean = false,

    @ColumnInfo("p_osx")
    val pOsx: Boolean = false,

    @ColumnInfo("p_android")
    val pAndroid: Boolean = false,

    @ColumnInfo("min_price")
    val minPrice: Int = 0,

    @ColumnInfo("classification")
    val classification: String = "game",

    @ColumnInfo("created_at")
    val createdAt: String = "",

    @ColumnInfo("is_installed")
    val isInstalled: Boolean = false,

    @ColumnInfo("install_path")
    val installPath: String = "",

    @ColumnInfo("install_size")
    val installSize: Long = 0,

    @ColumnInfo("last_played")
    val lastPlayed: Long = 0,

    @ColumnInfo("play_time")
    val playTime: Long = 0,

    @ColumnInfo("type")
    val type: AppType = AppType.game,

    @ColumnInfo("download_key_id")
    val downloadKeyId: Int = 0,
) {
    /** Platforms string for display, e.g. "Windows, Linux" */
    val platformsDisplay: String
        get() = buildList {
            if (pWindows) add("Windows")
            if (pLinux) add("Linux")
            if (pOsx) add("macOS")
            if (pAndroid) add("Android")
        }.joinToString(", ")
}

/**
 * Itch.io credentials using static API keys from https://itch.io/user/settings/api-keys
 * API keys do not expire and have full access to all scopes.
 */
data class ItchCredentials(
    val apiKey: String,
    val userId: Int,
    val username: String,
    val displayName: String,
    val coverUrl: String = "",
)
