package app.gamenative.ui.screen.library.appscreen

import android.content.Context
import androidx.compose.runtime.Composable
import app.gamenative.data.LibraryItem
import app.gamenative.ui.data.GameDisplayInfo
import com.winlator.container.ContainerData
import timber.log.Timber

/**
 * Itch.io-specific implementation of BaseAppScreen
 * 
 * This screen handles itch.io games that are not installed yet, properly
 * showing them as "not installed" initially instead of immediately showing a Play button.
 */
class ItchAppScreen : BaseAppScreen() {
    
    @Composable
    override fun getGameDisplayInfo(
        context: Context,
        libraryItem: LibraryItem
    ): GameDisplayInfo {
        // For now, just use the library item data directly
        // TODO: Fetch additional game details from database when needed
        return GameDisplayInfo(
            name = libraryItem.name,
            developer = "Unknown",
            releaseDate = 0L,
            heroImageUrl = libraryItem.iconHash,
            iconUrl = libraryItem.iconHash,
            gameId = libraryItem.gameId,
            appId = libraryItem.appId,
            installLocation = null,
            sizeOnDisk = null
        )
    }

    override fun isInstalled(context: Context, libraryItem: LibraryItem): Boolean {
        // Check the database for install status
        // Note: This is synchronous, so we need to be careful. In reality, itch.io games
        // don't support installation tracking yet, so this will always return false
        Timber.tag("ItchAppScreen").d("isInstalled() called for ${libraryItem.appId} - returning false")
        return false // TODO: Implement proper async check when download support is added
    }

    override fun isValidToDownload(context: Context, libraryItem: LibraryItem): Boolean {
        // For now, itch.io games cannot be downloaded through the app
        return false
    }

    override fun isDownloading(context: Context, libraryItem: LibraryItem): Boolean {
        // Not yet implemented for itch.io
        return false
    }

    override fun getDownloadProgress(context: Context, libraryItem: LibraryItem): Float {
        // Not yet implemented for itch.io
        return 0f
    }

    override fun onDownloadInstallClick(
        context: Context,
        libraryItem: LibraryItem,
        onClickPlay: (Boolean) -> Unit
    ) {
        // Not implemented yet - itch.io downloads not supported
    }

    override fun onPauseResumeClick(context: Context, libraryItem: LibraryItem) {
        // Not implemented yet - itch.io downloads not supported
    }

    override fun onDeleteDownloadClick(context: Context, libraryItem: LibraryItem) {
        // Not implemented yet - itch.io downloads not supported
    }

    override fun onUpdateClick(context: Context, libraryItem: LibraryItem) {
        // Not implemented yet - itch.io updates not supported
    }

    override fun getExportFileExtension(): String = ".itch"

    override fun getInstallPath(context: Context, libraryItem: LibraryItem): String? {
        // Itch games are not installed locally yet
        return null
    }

    override fun loadContainerData(context: Context, libraryItem: LibraryItem): ContainerData {
        // Itch.io games don't use containers yet
        throw UnsupportedOperationException("Itch.io games do not support containers")
    }

    override fun saveContainerConfig(context: Context, libraryItem: LibraryItem, config: ContainerData) {
        // Itch.io games don't use containers yet
        throw UnsupportedOperationException("Itch.io games do not support containers")
    }

    override fun supportsContainerConfig(): Boolean = false

    @Composable
    override fun getResetContainerOption(
        context: Context,
        libraryItem: LibraryItem,
    ): app.gamenative.ui.data.AppMenuOption? {
        // No container reset for itch.io games since there's no installation yet
        return null
    }
}
