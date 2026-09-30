package com.sosina.terefe.budgetingapp.data.scan

import android.content.Context
import android.graphics.Rect
import android.net.Uri
import androidx.core.content.FileProvider
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.sosina.terefe.budgetingapp.domain.scan.ParsedReceipt
import com.sosina.terefe.budgetingapp.domain.scan.ReceiptParser
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.min

/**
 * Quick scan: reads a receipt photo on the phone (no internet) and parses it.
 */
@Singleton
class ReceiptScanner @Inject constructor(
    @ApplicationContext private val context: Context
) {
    // Created on first use, then reused.
    private val recognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    /** Reads the photo at [imageUri] and returns whatever could be found. */
    suspend fun scan(imageUri: Uri): ParsedReceipt = withContext(Dispatchers.Default) {
        // fromFilePath also fixes the rotation of photos taken sideways.
        val image = InputImage.fromFilePath(context, imageUri)
        val text = recognizer.process(image).await()
        ReceiptParser.parse(toRows(text))
    }

    /**
     * Creates an empty file for the camera app to save a photo into,
     * and returns an address the camera app is allowed to write to.
     */
    fun createPhotoUri(): Uri {
        val folder = File(context.cacheDir, "receipts").apply { mkdirs() }
        deleteOldPhotos(folder)
        val file = File.createTempFile("receipt_", ".jpg", folder)
        return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }

    /**
     * ML Kit often reads one printed line as separate pieces: the item name
     * on the left and its price on the right. This puts pieces that sit at the
     * same height back together into one row, left to right, so
     * "Milk" + "4.50" becomes "Milk 4.50".
     */
    private fun toRows(text: Text): List<String> {
        val pieces = text.textBlocks
            .flatMap { it.lines }
            .mapNotNull { line -> line.boundingBox?.let { Piece(line.text, it) } }
            .sortedBy { it.box.centerY() }

        val rows = mutableListOf<MutableList<Piece>>()
        for (piece in pieces) {
            val row = rows.lastOrNull()
            if (row != null) {
                val rowCenter = row.map { it.box.centerY() }.average()
                val tolerance = min(row.first().box.height(), piece.box.height()) * 0.6
                if (abs(piece.box.centerY() - rowCenter) <= tolerance) {
                    row += piece
                    continue
                }
            }
            rows += mutableListOf(piece)
        }

        return rows.map { row -> row.sortedBy { it.box.left }.joinToString(" ") { it.text } }
    }

    /** Receipt photos are only needed while scanning, so old ones are removed. */
    private fun deleteOldPhotos(folder: File) {
        val oneDayAgo = System.currentTimeMillis() - 24 * 60 * 60 * 1000L
        folder.listFiles()?.filter { it.lastModified() < oneDayAgo }?.forEach { it.delete() }
    }

    private data class Piece(val text: String, val box: Rect)
}
