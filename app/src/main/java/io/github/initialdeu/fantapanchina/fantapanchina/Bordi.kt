package io.github.initialdeu.fantapanchina

import android.content.res.Configuration
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat

/**
 * Da Android 15 le app disegnano anche sotto barra di stato e barra di navigazione: sposta il
 * contenuto quanto serve e sceglie icone di sistema leggibili sul tema chiaro o scuro.
 */
fun AppCompatActivity.rispettaBordi() {
    WindowCompat.setDecorFitsSystemWindows(window, false)
    val contenuto = findViewById<View>(android.R.id.content)
    ViewCompat.setOnApplyWindowInsetsListener(contenuto) { v, insets ->
        val b = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
        v.setPadding(b.left, b.top, b.right, b.bottom)
        WindowInsetsCompat.CONSUMED
    }
    val scuro = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
        Configuration.UI_MODE_NIGHT_YES
    WindowCompat.getInsetsController(window, window.decorView).apply {
        isAppearanceLightStatusBars = !scuro
        isAppearanceLightNavigationBars = !scuro
    }
}
