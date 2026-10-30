package com.rawcapture.converter.storage

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.io.FileInputStream

object MediaStoreManager {

    private const val TAG = "MediaStoreManager"

    /**
     * Saves a video file to the device's Gallery using MediaStore API.
     * Returns the content URI of the saved file, or null on failure.
     */
    fun saveVideoToGallery(
        context: Context,
        sourceFile: File,
        displayName: String
    ): Uri? {
        return try {
            val resolver = context.contentResolver

            val contentValues = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.SIZE, sourceFile.length())
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Video.Media.RELATIVE_PATH,
                        "${Environment.DIRECTORY_MOVIES}/RawCapture")
                    put(MediaStore.Video.Media.IS_PENDING, 1)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    put(MediaStore.Video.Media.DATE_ADDED,
                        System.currentTimeMillis() / 1000)
                }
            }

            val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            } else {
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            }

            val videoUri = resolver.insert(collection, contentValues)
                ?: return null

            resolver.openOutputStream(videoUri)?.use { outputStream ->
                FileInputStream(sourceFile).use { inputStream ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                        outputStream.write(buffer, 0, bytesRead)
                    }
                    outputStream.flush()
                }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                contentValues.clear()
                contentValues.put(MediaStore.Video.Media.IS_PENDING, 0)
                resolver.update(videoUri, contentValues, null, null)
            }

            Log.i(TAG, "Video saved: $videoUri")
            videoUri

        } catch (e: Exception) {
            Log.e(TAG, "Failed to save video", e)
            null
        }
    }

    /**
     * Gets available storage space in bytes
     */
    fun getAvailableStorage(context: Context): Long {
        return try {
            val stat = android.os.StatFs(Environment.getExternalStorageDirectory().path)
            stat.availableBlocksLong * stat.blockSizeLong
        } catch (e: Exception) {
            Long.MAX_VALUE
        }
    }

    /**
     * Checks if there's enough space for the output file
     */
    fun hasEnoughSpace(context: Context, estimatedSize: Long): Boolean {
        val available = getAvailableStorage(context)
        return available > (estimatedSize * 1.2).toLong() // 20% safety margin
    }
}
