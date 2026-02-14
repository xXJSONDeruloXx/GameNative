package app.gamenative.service.itch

import android.content.Context
import app.gamenative.data.DownloadInfo
import app.gamenative.data.ItchGame
import app.gamenative.utils.Net
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipInputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * ItchDownloadManager handles downloading itch.io games
 * 
 * Architecture:
 * 1. Use download_key from game to access downloads
 * 2. Fetch list of uploads (game files) using /download-key/:id/uploads
 * 3. Get download URL for specific upload using /download-key/:id/download/:uploadId
 * 4. Download file with progress tracking
 * 5. Extract zip to install directory
 * 6. Set executable permissions on .exe/.sh files
 */
@Singleton
class ItchDownloadManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val httpClient = Net.http
    
    /**
     * Downloads and installs an itch.io game
     * 
     * @param game The game to download
     * @param installPath The directory to install to
     * @param downloadInfo Progress tracker for UI updates
     * @return Result with success or error
     */
    suspend fun downloadGame(
        game: ItchGame,
        installPath: File,
        downloadInfo: DownloadInfo
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            downloadInfo.setActive(true)
            downloadInfo.setProgress(0.0f)
            
            Timber.tag("Itch").d("[Download] Starting download for ${game.title}")
            
            // Get credentials
            val credentials = ItchAuthManager.getStoredCredentials(context).getOrElse {
                return@withContext Result.failure(Exception("Not authenticated"))
            }
            
            // Step 1: Get list of uploads for this game (10%)
            val uploads = getUploads(credentials.apiKey, game.id, game.downloadKeyId).getOrElse {
                return@withContext Result.failure(it)
            }
            
            if (uploads.isEmpty()) {
                return@withContext Result.failure(Exception("No game files available for download"))
            }
            
            // Find best upload - prefer Windows builds
            val upload = uploads.firstOrNull { it.platform == "windows" } 
                ?: uploads.first()
            
            Timber.tag("Itch").d("[Download] Selected upload: ${upload.filename} (${upload.size} bytes)")
            downloadInfo.setProgress(0.1f)
            
            // Step 2: Get download URL (20%)
            val downloadUrl = getDownloadUrl(credentials.apiKey, game.downloadKeyId, upload.id).getOrElse {
                return@withContext Result.failure(it)
            }
            downloadInfo.setProgress(0.2f)
            
            // Step 3: Download file (20% -> 90%)
            val tempFile = File(context.cacheDir, "itch_${game.id}_${System.currentTimeMillis()}.zip")
            downloadFile(downloadUrl, tempFile, upload.size, downloadInfo).getOrElse {
                tempFile.delete()
                return@withContext Result.failure(it)
            }
            
            // Step 4: Extract zip (90% -> 100%)
            installPath.mkdirs()
            extractZip(tempFile, installPath, downloadInfo).getOrElse {
                tempFile.delete()
                return@withContext Result.failure(it)
            }
            
            tempFile.delete()
            downloadInfo.setProgress(1.0f)
            downloadInfo.setActive(false)
            
            Timber.tag("Itch").i("[Download] Successfully installed ${game.title}")
            Result.success(Unit)
            
        } catch (e: Exception) {
            Timber.tag("Itch").e(e, "[Download] Failed to download ${game.title}")
            downloadInfo.setActive(false)
            Result.failure(e)
        }
    }
    
    /**
     * Fetches list of uploads for a game
     * Made public to allow checking file sizes before download
     * 
     * @param apiKey The static API key for authentication
     * @param gameId The game ID
     * @param downloadKeyId The download key ID for authentication
     */
    suspend fun getUploads(apiKey: String, gameId: Int, downloadKeyId: Int): Result<List<ItchUpload>> {
        return getUploadsForGame(apiKey, gameId, downloadKeyId)
    }
    
    /**
     * Fetches uploads for a specific game ID with download key credentials
     * Uses itch.io API endpoint /games/:id/uploads with Authorization header and download_key_id parameter
     */
    private suspend fun getUploadsForGame(apiKey: String, gameId: Int, downloadKeyId: Int): Result<List<ItchUpload>> {
        return try {
            // Use the game uploads endpoint with Authorization header and download_key_id parameter
            // Format: GET https://api.itch.io/games/:id/uploads?download_key_id=:key_id
            // Header: Authorization: <api_key>
            val url = "${ItchConstants.ITCH_API_BASE_URL}/games/$gameId/uploads?download_key_id=$downloadKeyId"
            Timber.tag("Itch").d("[API] GET /games/$gameId/uploads?download_key_id=$downloadKeyId")
            
            val request = okhttp3.Request.Builder()
                .url(url)
                .header("Authorization", apiKey)
                .get()
                .build()
            
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val errorBody = response.body?.string() ?: ""
                    Timber.tag("Itch").e("[API] Failed to fetch uploads: HTTP ${response.code}")
                    Timber.tag("Itch").e("[API] Response body: $errorBody")
                    return Result.failure(Exception("Failed to fetch uploads: HTTP ${response.code}"))
                }
                
                val json = org.json.JSONObject(response.body?.string() ?: "{}")
                val uploadsArray = json.getJSONArray("uploads")
                val uploads = mutableListOf<ItchUpload>()
                
                for (i in 0 until uploadsArray.length()) {
                    val uploadJson = uploadsArray.getJSONObject(i)
                    val pWindows = uploadJson.optString("p_windows", "")
                    val pLinux = uploadJson.optString("p_linux", "")
                    val pOsx = uploadJson.optString("p_osx", "")
                    
                    val platform = when {
                        !pWindows.isNullOrEmpty() -> "windows"
                        !pLinux.isNullOrEmpty() -> "linux"
                        !pOsx.isNullOrEmpty() -> "mac"
                        else -> "unknown"
                    }
                    
                    uploads.add(ItchUpload(
                        id = uploadJson.getLong("id"),
                        filename = uploadJson.getString("filename"),
                        size = uploadJson.optLong("size", 0),
                        platform = platform
                    ))
                }
                
                Timber.tag("Itch").d("[API] Found ${uploads.size} uploads for game $gameId")
                Result.success(uploads)
            }
        } catch (e: Exception) {
            Timber.tag("Itch").e(e, "[API] Exception fetching uploads for game")
            Result.failure(e)
        }
    }
    
    /**
     * Gets download URL for a specific upload
     * Generates the direct download URL using API key and download_key_id as parameters
     */
    private suspend fun getDownloadUrl(apiKey: String, downloadKeyId: Int, uploadId: Long): Result<String> {
        return try {
            // Generate the direct download URL
            // Format: https://api.itch.io/uploads/:id/download
            // This URL includes auth credentials as query parameters
            val downloadUrl = "${ItchConstants.ITCH_API_BASE_URL}/uploads/$uploadId/download" +
                "?api_key=$apiKey&download_key_id=$downloadKeyId"
            
            Timber.tag("Itch").d("[API] Generated download URL for upload $uploadId")
            Result.success(downloadUrl)
        } catch (e: Exception) {
            Timber.tag("Itch").e(e, "[API] Exception generating download URL")
            Result.failure(e)
        }
    }
    
    /**
     * Downloads a file with progress tracking
     */
    private suspend fun downloadFile(
        url: String,
        destFile: File,
        totalSize: Long,
        downloadInfo: DownloadInfo
    ): Result<Unit> {
        return try {
            withContext(Dispatchers.IO) {
                val request = okhttp3.Request.Builder()
                    .url(url)
                    .build()
                
                val client = okhttp3.OkHttpClient()
                val response = client.newCall(request).execute()
                
                if (!response.isSuccessful) {
                    return@withContext Result.failure(Exception("Download failed: HTTP ${response.code}"))
                }
                
                response.body?.let { body ->
                    val inputStream = body.byteStream()
                    val outputStream = FileOutputStream(destFile)
                    
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    var totalBytesRead = 0L
                    val contentLength = totalSize.takeIf { it > 0 } ?: body.contentLength()
                    
                    while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                        outputStream.write(buffer, 0, bytesRead)
                        totalBytesRead += bytesRead
                        
                        if (contentLength > 0) {
                            // Progress from 20% to 90% during download
                            val progress = 0.2f + (totalBytesRead.toFloat() / contentLength.toFloat() * 0.7f)
                            downloadInfo.setProgress(progress)
                        }
                    }
                    
                    outputStream.close()
                    inputStream.close()
                    
                    Result.success(Unit)
                } ?: Result.failure(Exception("Empty response body"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    /**
     * Extracts a zip file to a directory
     */
    private suspend fun extractZip(
        zipFile: File,
        destDir: File,
        downloadInfo: DownloadInfo
    ): Result<Unit> {
        return try {
            withContext(Dispatchers.IO) {
                val inputStream = FileInputStream(zipFile)
                val zipInputStream = ZipInputStream(inputStream)
                
                var entry = zipInputStream.nextEntry
                var filesExtracted = 0
                
                while (entry != null) {
                    val file = File(destDir, entry.name)
                    
                    if (entry.isDirectory) {
                        file.mkdirs()
                    } else {
                        file.parentFile?.mkdirs()
                        val outputStream = FileOutputStream(file)
                        
                        val buffer = ByteArray(8192)
                        var bytesRead: Int
                        while (zipInputStream.read(buffer).also { bytesRead = it } != -1) {
                            outputStream.write(buffer, 0, bytesRead)
                        }
                        
                        outputStream.close()
                        
                        // Set executable permission for .exe and .sh files
                        if (entry.name.endsWith(".exe") || entry.name.endsWith(".sh")) {
                            file.setExecutable(true)
                        }
                        
                        filesExtracted++
                    }
                    
                    zipInputStream.closeEntry()
                    entry = zipInputStream.nextEntry
                    
                    // Progress from 90% to 100% during extraction
                    val progress = 0.9f + (filesExtracted * 0.001f).coerceAtMost(0.1f)
                    downloadInfo.setProgress(progress)
                }
                
                zipInputStream.close()
                inputStream.close()
                
                Result.success(Unit)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}

/**
 * Data class representing an itch.io upload
 */
data class ItchUpload(
    val id: Long,
    val filename: String,
    val size: Long,
    val platform: String
)
