package com.streamflixreborn.streamflix.utils

import android.content.Context
import android.util.Log
import android.webkit.WebView
import com.bumptech.glide.Glide
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File

object CacheUtils {
    private const val TAG = "CacheUtils"

    fun clearAppCache(context: Context) {
        Log.d(TAG, "Inizio pulizia cache completa...")
        
        // Pulizia cache interna
        try {
            context.cacheDir?.deleteRecursively()
            Log.d(TAG, "Cache interna eliminata.")
        } catch (e: Exception) {
            Log.e(TAG, "Errore eliminazione cache interna: ${e.message}")
        }

        // Pulizia Glide (Memory + Disk)
        try {
            Glide.get(context).clearMemory()
            // Uso di CoroutineScope su Dispatchers.IO per la cache su disco
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    Glide.get(context).clearDiskCache()
                    Log.d(TAG, "Cache Glide eliminata.")
                } catch (e: Exception) {
                    Log.e(TAG, "Errore eliminazione cache Glide: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Errore Glide: ${e.message}")
        }

        // Pulizia WebView
        try {
            WebView(context).apply {
                clearCache(true)
                destroy()
            }
            Log.d(TAG, "Cache WebView eliminata.")
        } catch (e: Exception) {
            Log.e(TAG, "Errore WebView: ${e.message}")
        }
    }

    fun getCacheSize(context: Context): Long {
        var size: Long = 0
        try {
            size += getFolderSize(context.cacheDir)
            size += getFolderSize(context.externalCacheDir)
        } catch (_: Exception) {}
        return size
    }

    private fun getFolderSize(file: File?): Long {
        if (file == null || !file.exists()) return 0
        if (!file.isDirectory) return file.length()

        var size: Long = 0
        file.listFiles()?.forEach { f ->
            size += if (f.isDirectory) getFolderSize(f) else f.length()
        }
        return size
    }

    fun autoClearIfNeeded(context: Context, thresholdMb: Long = 50) {
        val currentSize = getCacheSize(context)
        val thresholdBytes = thresholdMb * 1024 * 1024
        val currentMb = currentSize / (1024 * 1024)
        
        Log.d(TAG, "Controlled cache: Attuale = ${currentMb}MB, Soglia = ${thresholdMb}MB")
        
        if (currentSize > thresholdBytes) {
            Log.i(TAG, "Soglia superata! Avvio pulizia automatica...")
            clearAppCache(context)
        }
    }
}
