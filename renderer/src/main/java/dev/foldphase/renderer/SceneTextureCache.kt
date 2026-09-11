package dev.foldphase.renderer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * Holds the two scene textures and guarantees they are decoded exactly once.
 *
 * Brief §13 and §22 are specific about this: never capture a screenshot per frame, never
 * decode a bitmap on the animation path, never allocate a large bitmap per frame.
 * *Capture or create it once, upload it, then transform it on the GPU.*
 *
 * This class is the enforcement point. Decoding happens off the main thread, at import
 * time, into a hardware bitmap; after that the animation path only ever reads an already
 * resident texture.
 */
class SceneTextureCache(private val context: Context) {

    @Volatile
    var coverTexture: Bitmap? = null
        private set

    @Volatile
    var innerTexture: Bitmap? = null
        private set

    private val dir: File by lazy {
        File(context.filesDir, "scene").apply { mkdirs() }
    }

    private fun fileFor(slot: Slot) = File(dir, "${slot.name.lowercase()}.png")

    enum class Slot { COVER, INNER }

    /**
     * Import a user-supplied screenshot (brief §16) and make it the texture for [slot].
     *
     * Copies the image into app storage so the texture survives the source URI's
     * permission grant being revoked, then decodes it once.
     */
    suspend fun import(uri: Uri, slot: Slot): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val target = fileFor(slot)
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(target).use { output -> input.copyTo(output) }
            } ?: error("Could not open $uri")
            decodeInto(slot, target)
        }
    }

    /** Load whatever was previously imported. Called once at startup, never per frame. */
    suspend fun loadPersisted(): Unit = withContext(Dispatchers.IO) {
        Slot.entries.forEach { slot ->
            val f = fileFor(slot)
            if (f.exists()) {
                runCatching { decodeInto(slot, f) }
                    .onFailure { Log.w(TAG, "Failed to decode persisted ${slot.name}", it) }
            }
        }
    }

    private fun decodeInto(slot: Slot, file: File) {
        val opts = BitmapFactory.Options().apply {
            // ARGB_8888 rather than HARDWARE: the shader samples these as input shaders,
            // and a hardware bitmap cannot be read back if a future feature needs it.
            // The memory cost of two full-screen textures is a few megabytes, which is
            // not worth trading flexibility for.
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val bmp = BitmapFactory.decodeFile(file.absolutePath, opts)
            ?: error("Decode returned null for ${file.name}")
        when (slot) {
            Slot.COVER -> coverTexture = bmp
            Slot.INNER -> innerTexture = bmp
        }
    }

    /**
     * Install a bitmap directly, bypassing disk. Used by the launcher, which renders its
     * own scene rather than importing a screenshot.
     */
    fun setTexture(slot: Slot, bitmap: Bitmap?) {
        when (slot) {
            Slot.COVER -> coverTexture = bitmap
            Slot.INNER -> innerTexture = bitmap
        }
    }

    fun hasBoth(): Boolean = coverTexture != null && innerTexture != null

    fun clear(slot: Slot) {
        setTexture(slot, null)
        runCatching { fileFor(slot).delete() }
    }

    private companion object {
        const val TAG = "SceneTextureCache"
    }
}
