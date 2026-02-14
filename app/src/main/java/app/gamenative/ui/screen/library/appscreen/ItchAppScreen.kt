package app.gamenative.ui.screen.library.appscreen

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import app.gamenative.data.ItchGame
import app.gamenative.data.LibraryItem
import app.gamenative.service.itch.ItchConstants
import app.gamenative.service.itch.ItchService
import app.gamenative.ui.data.GameDisplayInfo
import com.winlator.container.ContainerData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.time.Instant

/**
 * Itch.io-specific implementation of BaseAppScreen
 */
class ItchAppScreen : BaseAppScreen() {
    private val itchGame = mutableStateOf<ItchGame?>(null)

    @Composable
    override fun getGameDisplayInfo(
        context: Context,
        libraryItem: LibraryItem
    ): GameDisplayInfo {
        if (itchGame.value == null) {
            CoroutineScope(Dispatchers.IO).launch {
                val game = ItchService.getItchGameOf(libraryItem.gameId.toString())
                itchGame.value = game
            }
        }

        val game = itchGame.value
        
        // Fetch upload size if not installed
        var sizeFromStore by remember { mutableStateOf<String?>(null) }
        val isInstalled = game?.isInstalled == true
        
        LaunchedEffect(libraryItem.gameId, isInstalled) {
            if (!isInstalled) {
                try {
                    val sizeBytes = ItchService.getUploadSize(context, libraryItem.gameId.toString())
                    if (sizeBytes > 0) {
                        sizeFromStore = formatBytes(sizeBytes)
                    }
                } catch (e: Exception) {
                    Timber.tag("ItchAppScreen").e(e, "Failed to fetch upload size")
                }
            }
        }
        
        return GameDisplayInfo(
            name = game?.title ?: libraryItem.name,
            developer = game?.developer ?: "Unknown",
            releaseDate = parseIsoDate(game?.createdAt),
            heroImageUrl = game?.coverUrl ?: libraryItem.iconHash,
            iconUrl = game?.coverUrl ?: libraryItem.iconHash,
            gameId = libraryItem.gameId,
            appId = libraryItem.appId,
            installLocation = game?.installPath,
            sizeOnDisk = game?.installSize?.toString()?.let { formatBytes(it.toLong()) },
            sizeFromStore = sizeFromStore
        )
    }
    
    private fun formatBytes(bytes: Long): String {
        return when {
            bytes >= 1024 * 1024 * 1024 -> String.format("%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0))
            bytes >= 1024 * 1024 -> String.format("%.2f MB", bytes / (1024.0 * 1024.0))
            bytes >= 1024 -> String.format("%.2f KB", bytes / 1024.0)
            else -> "$bytes B"
        }
    }

    private fun parseIsoDate(isoDate: String?): Long {
        return try {
            if (isoDate != null) {
                Instant.parse(isoDate).epochSecond
            } else {
                0L
            }
        } catch (e: Exception) {
            0L
        }
    }

    override fun isInstalled(context: Context, libraryItem: LibraryItem): Boolean {
        val game = itchGame.value
        return game?.isInstalled == true
    }

    override fun isValidToDownload(context: Context, libraryItem: LibraryItem): Boolean {
        return !isInstalled(context, libraryItem)
    }

    override fun isDownloading(context: Context, libraryItem: LibraryItem): Boolean {
        val gameId = libraryItem.gameId.toString()
        val downloadInfo = ItchService.getDownloadInfo(gameId)
        return downloadInfo != null && (downloadInfo.getProgress() ?: 0f) < 1f
    }

    override fun getDownloadProgress(context: Context, libraryItem: LibraryItem): Float {
        val gameId = libraryItem.gameId.toString()
        val downloadInfo = ItchService.getDownloadInfo(gameId)
        return downloadInfo?.getProgress() ?: 0f
    }

    override fun onDownloadInstallClick(
        context: Context,
        libraryItem: LibraryItem,
        onClickPlay: (Boolean) -> Unit
    ) {
        Timber.tag("ItchAppScreen").i("onDownloadInstallClick: ${libraryItem.name}")
        
        val gameId = libraryItem.gameId.toString()
        val downloadInfo = ItchService.getDownloadInfo(gameId)
        val isDownloading = downloadInfo != null && (downloadInfo.getProgress() ?: 0f) < 1f
        val installed = isInstalled(context, libraryItem)

        when {
            isDownloading -> {
                Timber.tag("ItchAppScreen").i("Cancelling download for: ${libraryItem.name}")
                downloadInfo.cancel()
                ItchService.cleanupDownload(gameId)
            }
            installed -> {
                Timber.tag("ItchAppScreen").i("Game already installed, launching: ${libraryItem.name}")
                onClickPlay(false)
            }
            else -> {
                performDownload(context, libraryItem, onClickPlay)
            }
        }
    }

    private fun performDownload(context: Context, libraryItem: LibraryItem, onClickPlay: (Boolean) -> Unit) {
        val gameId = libraryItem.gameId.toString()
        Timber.tag("ItchAppScreen").i("Starting download: ${libraryItem.name}")
        
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val installPath = ItchConstants.getGameInstallPath(context, libraryItem.name)
                Timber.tag("ItchAppScreen").d("Downloading to: $installPath")

                withContext(Dispatchers.Main) {
                    android.widget.Toast.makeText(
                        context,
                        "Starting download for ${libraryItem.name}...",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                }

                val result = ItchService.downloadGame(context, gameId, installPath)

                if (result.isSuccess) {
                    Timber.tag("ItchAppScreen").i("Download started successfully for: $gameId")
                } else {
                    val error = result.exceptionOrNull()
                    Timber.tag("ItchAppScreen").e(error, "Failed to start download")
                    withContext(Dispatchers.Main) {
                        android.widget.Toast.makeText(
                            context,
                            "Failed to start download: ${error?.message}",
                            android.widget.Toast.LENGTH_LONG
                        ).show()
                    }
                }
            } catch (e: Exception) {
                Timber.tag("ItchAppScreen").e(e, "Error during download")
                withContext(Dispatchers.Main) {
                    android.widget.Toast.makeText(
                        context,
                        "Download error: ${e.message}",
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    override fun onPauseResumeClick(context: Context, libraryItem: LibraryItem) {
        // Pause/resume not supported for itch.io
    }

    override fun onDeleteDownloadClick(context: Context, libraryItem: LibraryItem) {
        val gameId = libraryItem.gameId.toString()
        val downloadInfo = ItchService.getDownloadInfo(gameId)
        if (downloadInfo != null) {
            downloadInfo.cancel()
            ItchService.cleanupDownload(gameId)
        }
    }

    override fun onUpdateClick(context: Context, libraryItem: LibraryItem) {
        // Not implemented yet - itch.io updates not supported
    }

    override fun getExportFileExtension(): String = ".itch"

    override fun getInstallPath(context: Context, libraryItem: LibraryItem): String? {
        return itchGame.value?.installPath
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
