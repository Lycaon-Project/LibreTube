package com.github.libretube.ui.base

import android.content.Context
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.view.View
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import com.github.libretube.R
import com.github.libretube.constants.PreferenceKeys
import com.github.libretube.helpers.LocaleHelper
import com.github.libretube.helpers.PreferenceHelper
import com.github.libretube.helpers.ThemeHelper
import com.github.libretube.helpers.ThemeHelper.getThemeMode
import com.github.libretube.helpers.WindowHelper
import java.util.Locale

/**
 * Activity that applies the LibreTube theme and the in-app language
 */
open class BaseActivity : AppCompatActivity() {

    open val isDialogActivity: Boolean = false

    // Cache de la préférence d'orientation pour éviter les recalculs
    // IMPORTANT : Cette propriété doit rester publique pour être accessible depuis PlayerFragment
    val screenOrientationPref: Int by lazy {
        when (PreferenceHelper.getString(
            PreferenceKeys.ORIENTATION,
            resources.getString(R.string.config_default_orientation_pref)
        )) {
            "portrait" -> ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT
            "landscape" -> ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE
            "auto" -> ActivityInfo.SCREEN_ORIENTATION_USER
            else -> ActivityInfo.SCREEN_ORIENTATION_USER // Fallback sécurisé
        }
    }

    /**
     * Whether the phone of the user has a cutout like a notch or not
     */
    var hasCutout: Boolean = false
        private set

    // Référence au listener pour pouvoir le nettoyer
    private var insetsListener: View.OnApplyWindowInsetsListener? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        // Appliquer le thème uniquement si nécessaire
        applyThemeIfNeeded()

        // Configurer l'orientation avant super.onCreate pour éviter les changements de configuration
        requestedOrientation = screenOrientationPref

        // Détecter les cutouts de manière asynchrone pour ne pas bloquer le premier rendu
        setupCutoutDetection()

        // Activer edge to edge uniquement si pas en mode dialog
        if (!isDialogActivity) {
            enableEdgeToEdge()
        }

        super.onCreate(savedInstanceState)
    }

    /**
     * Applique le thème uniquement si nécessaire
     */
    private fun applyThemeIfNeeded() {
        if (isDialogActivity) {
            ThemeHelper.applyDialogActivityTheme(this)
        } else {
            ThemeHelper.updateTheme(this)
        }
    }

    /**
     * Configure la détection des cutouts de manière optimisée
     */
    private fun setupCutoutDetection() {
        if (insetsListener != null) return

        insetsListener = View.OnApplyWindowInsetsListener { view, insets ->
            hasCutout = WindowHelper.hasCutout(view)
            view.onApplyWindowInsets(insets)
        }
        window.decorView.setOnApplyWindowInsetsListener(insetsListener)
    }

    override fun attachBaseContext(newBase: Context?) {
        val baseContext = newBase ?: return super.attachBaseContext(null)
        super.attachBaseContext(baseContext)

        val configuration = Configuration()
        var needsConfig = false

        // Gestion de la locale pour les versions antérieures à Android 13 (Tiramisu)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            @Suppress("DEPRECATION")
            val locale = LocaleHelper.getAppLocale()
            Locale.setDefault(locale)
            configuration.setLocale(locale)
            needsConfig = true
        }

        // IMPORTANT : l'appel au thème est conservé nécessaire au comportement de l'app
        // (changement de thème dynamique à la volée, ou Application non configurée)
        val uiPref = PreferenceHelper.getString(PreferenceKeys.THEME_MODE, "A")
        AppCompatDelegate.setDefaultNightMode(getThemeMode(uiPref))

        if (needsConfig) {
            applyOverrideConfiguration(configuration)
        }
    }

    /**
     * Nettoie les ressources pour éviter les fuites mémoire
     */
    override fun onDestroy() {
        super.onDestroy()
        if (insetsListener != null) {
            window.decorView.setOnApplyWindowInsetsListener(null)
            insetsListener = null
        }
    }

    /**
     * Méthode conservée pour compatibilité, mais utilise le cache
     */
    open fun requestOrientationChange() {
        requestedOrientation = screenOrientationPref
    }
}