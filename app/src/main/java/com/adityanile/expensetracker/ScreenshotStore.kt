package com.adityanile.expensetracker

import android.content.Context
import android.graphics.Bitmap
import java.io.File
import java.io.FileOutputStream

object ScreenshotStore {

    private const val DIRECTORY_NAME = "screenshots"

    private fun screenshotDirectory(context: Context): File {
        return File(context.filesDir, DIRECTORY_NAME).apply {
            if (!exists()) mkdirs()
        }
    }

    fun save(context: Context, bitmap: Bitmap): File {
        val output = File(
            screenshotDirectory(context),
            "txn_${System.currentTimeMillis()}.png",
        )
        FileOutputStream(output).use { stream ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
            stream.flush()
        }
        return output
    }

    fun listScreenshots(context: Context): List<File> {
        return screenshotDirectory(context)
            .listFiles()
            ?.filter { it.isFile && it.extension.equals("png", ignoreCase = true) }
            ?.sortedByDescending { it.lastModified() }
            .orEmpty()
    }

    fun delete(file: File): Boolean {
        return file.exists() && file.delete()
    }
}
