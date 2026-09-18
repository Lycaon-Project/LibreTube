package com.github.libretube.helpers

import android.util.Log
import com.github.libretube.BuildConfig
import com.github.libretube.util.NewPipeDownloaderImpl
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.StreamingService

object NewPipeExtractorInstance {
    private const val TAG = "NewPipeExtractor"

    // ✅ Optimisation 1 : LazyThreadSafetyMode.PUBLICATION
    // Plus rapide que le mode par défaut (SYNCHRONIZED) car
    // - Pas de verrouillage pour les lectures après initialisation
    // - Thread-safe pour l'initialisation unique
    @Volatile
    private var isInitialized = false

    val extractor: StreamingService by lazy(LazyThreadSafetyMode.PUBLICATION) {
        if (!isInitialized) {
            Log.w(TAG, "Extractor accessed before init() - initializing now")
            init()
        }
        NewPipe.getService(ServiceList.YouTube.serviceId)
    }

    /**
     * Initialise NewPipe avec le downloader personnalisé
     * Peut être appelée en toute sécurité plusieurs fois (idempotente)
     */
    fun init() {
        if (isInitialized) return

        synchronized(this) {
            if (isInitialized) return // Double-check pour éviter les races

            try {
                val startTime = System.currentTimeMillis()
                NewPipe.init(NewPipeDownloaderImpl())
                isInitialized = true

                if (BuildConfig.DEBUG) {
                    val duration = System.currentTimeMillis() - startTime
                    Log.d(TAG, "NewPipe initialized in ${duration}ms")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize NewPipe", e)
                throw e // Re-throw pour pas masquer les erreurs critiques
            }
        }
    }
}