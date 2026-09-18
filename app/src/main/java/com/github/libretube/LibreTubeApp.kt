package com.github.libretube

import android.app.Application
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.ExistingPeriodicWorkPolicy
import com.github.libretube.helpers.ImageHelper
import com.github.libretube.helpers.NewPipeExtractorInstance
import com.github.libretube.helpers.NotificationHelper
import com.github.libretube.helpers.PreferenceHelper
import com.github.libretube.helpers.ProxyHelper
import com.github.libretube.helpers.ShortcutHelper
import com.github.libretube.util.ExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch

class LibreTubeApp : Application() {

    // Scope global de l'application qui survit aux changements de configuration
    // Utilise Dispatchers.IO pour ne pas bloquer le thread principal
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Deferred pour les initialisations critiques qui peuvent être attendues au premier besoin
    // Permet au player d'attendre si nécessaire sans bloquer le démarrage
    lateinit var newPipeInit: Deferred<Unit>
        private set
    lateinit var proxyInit: Deferred<Unit>
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this

        // ✅ PHASE 1 : Initialisations synchrones ULTRA-RAPIDES (<10ms)
        // Ces opérations sont nécessaires immédiatement et très rapides
        PreferenceHelper.initialize(applicationContext)
        setupExceptionHandler()
        initializeNotificationChannels()

        // ✅ PHASE 2 : Initialisations asynchrones non-bloquantes
        // Ne bloquent PAS le premier frame rendu (critique pour le 120 Hz)
        appScope.launch {
            PreferenceHelper.migrate()
            ImageHelper.initializeImageLoader(this@LibreTubeApp)
            NotificationHelper.enqueueWork(
                context = this@LibreTubeApp,
                existingPeriodicWorkPolicy = ExistingPeriodicWorkPolicy.KEEP
            )
            ShortcutHelper.createShortcuts(this@LibreTubeApp)
        }

        // ✅ PHASE 3 : Initialisations deferred pour composants critiques
        // S'exécutent en arrière-plan, prêtes quand le player en aura besoin
        newPipeInit = appScope.async {
            NewPipeExtractorInstance.init()
        }

        proxyInit = appScope.async {
            ProxyHelper.fetchProxyUrl()
        }
    }

    /**
     * Configure le gestionnaire d'exceptions
     * Opération très rapide, peut rester synchrone
     */
    private fun setupExceptionHandler() {
        val defaultExceptionHandler = Thread.getDefaultUncaughtExceptionHandler()
        val exceptionHandler = ExceptionHandler(defaultExceptionHandler)
        Thread.setDefaultUncaughtExceptionHandler(exceptionHandler)
    }

    /**
     * Initialise les channels de notification uniquement s'ils n'existent pas
     * Opération rapide, peut rester synchrone
     */
    private fun initializeNotificationChannels() {
        val notificationManager = NotificationManagerCompat.from(this)
        val existingChannels = notificationManager.notificationChannels.map { it.id }
        val channelsToCreate = mutableListOf<NotificationChannelCompat>()

        if (DOWNLOAD_CHANNEL_NAME !in existingChannels) {
            channelsToCreate.add(
                NotificationChannelCompat.Builder(
                    DOWNLOAD_CHANNEL_NAME,
                    NotificationManagerCompat.IMPORTANCE_LOW
                )
                    .setName(getString(R.string.download_channel_name))
                    .setDescription(getString(R.string.download_channel_description))
                    .build()
            )
        }

        if (PLAYLIST_DOWNLOAD_ENQUEUE_CHANNEL_NAME !in existingChannels) {
            channelsToCreate.add(
                NotificationChannelCompat.Builder(
                    PLAYLIST_DOWNLOAD_ENQUEUE_CHANNEL_NAME,
                    NotificationManagerCompat.IMPORTANCE_LOW
                )
                    .setName(getString(R.string.download_playlist))
                    .setDescription(getString(R.string.enqueue_playlist_description))
                    .build()
            )
        }

        if (PLAYER_CHANNEL_NAME !in existingChannels) {
            channelsToCreate.add(
                NotificationChannelCompat.Builder(
                    PLAYER_CHANNEL_NAME,
                    NotificationManagerCompat.IMPORTANCE_LOW
                )
                    .setName(getString(R.string.player_channel_name))
                    .setDescription(getString(R.string.player_channel_description))
                    .build()
            )
        }

        if (PUSH_CHANNEL_NAME !in existingChannels) {
            channelsToCreate.add(
                NotificationChannelCompat.Builder(
                    PUSH_CHANNEL_NAME,
                    NotificationManagerCompat.IMPORTANCE_DEFAULT
                )
                    .setName(getString(R.string.push_channel_name))
                    .setDescription(getString(R.string.push_channel_description))
                    .build()
            )
        }

        if (channelsToCreate.isNotEmpty()) {
            notificationManager.createNotificationChannelsCompat(channelsToCreate)
        }
    }

    companion object {
        @Volatile
        lateinit var instance: LibreTubeApp
            private set

        const val DOWNLOAD_CHANNEL_NAME = "download_service"
        const val PLAYLIST_DOWNLOAD_ENQUEUE_CHANNEL_NAME = "playlist_download_enqueue"
        const val PLAYER_CHANNEL_NAME = "player_mode"
        const val PUSH_CHANNEL_NAME = "notification_worker"
    }
}