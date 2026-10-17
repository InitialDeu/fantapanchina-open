package io.github.initialdeu.fantapanchina

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * Segnalazione di un contenuto generato dall'assistente (richiesta dalle regole di Google Play
 * sui contenuti IA). Il contenuto viene tolto subito dall'app; la segnalazione va a chi pubblica
 * l'app: all'indirizzo [BuildConfig.SEGNALAZIONI_URL] se c'è, altrimenti per email a
 * [BuildConfig.CONTATTO_EMAIL].
 *
 * [tipo] dice cos'è ("formazione", "commento"), [contenuto] è il testo segnalato e [togli] lo
 * rimuove dall'app.
 */
fun Activity.segnalaContenutoIa(tipo: String, contenuto: String, togli: () -> Unit) {
    val dp = { v: Int -> (v * resources.displayMetrics.density).toInt() }
    val motivi = listOf("Offensivo o inappropriato", "Falso o fuorviante", "Altro")
    val gruppo = RadioGroup(this)
    val ids = motivi.map { View.generateViewId() }
    motivi.forEachIndexed { i, m ->
        gruppo.addView(RadioButton(this).apply {
            id = ids[i]
            text = m
        })
    }
    gruppo.check(ids[0])
    val nota = EditText(this).apply { hint = "Aggiungi un dettaglio (facoltativo)" }

    AlertDialog.Builder(this)
        .setTitle("Segnala questo contenuto")
        .setView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(TextView(context).apply {
                text = "È stato generato dall'IA. Lo togliamo subito dall'app e la segnalazione arriva " +
                    "a chi la sviluppa, insieme al testo segnalato."
                textSize = 13f
            })
            addView(gruppo)
            addView(nota)
        })
        .setPositiveButton("Segnala") { _, _ ->
            val motivo = motivi[ids.indexOf(gruppo.checkedRadioButtonId).coerceAtLeast(0)]
            togli()
            invia(tipo, motivo, nota.text.toString().trim(), contenuto)
        }
        .setNegativeButton("Annulla", null)
        .show()
}

private fun Activity.invia(tipo: String, motivo: String, nota: String, contenuto: String) {
    val dati = JSONObject()
        .put("tipo", tipo)
        .put("motivo", motivo)
        .put("nota", nota)
        .put("contenuto", contenuto)
        .put("assistente", Assistente.provider(this).name)
        .put("modello", Assistente.modello(this))
        .put("versione_app", BuildConfig.VERSION_NAME)

    if (BuildConfig.SEGNALAZIONI_URL.isNotBlank()) {
        val app = applicationContext
        thread {
            val ok = runCatching {
                val c = URL(BuildConfig.SEGNALAZIONI_URL).openConnection() as HttpURLConnection
                try {
                    c.requestMethod = "POST"
                    c.connectTimeout = 15_000
                    c.readTimeout = 15_000
                    c.doOutput = true
                    c.setRequestProperty("Content-Type", "application/json")
                    c.outputStream.use { it.write(dati.toString().toByteArray()) }
                    c.responseCode in 200..399
                } finally {
                    c.disconnect()
                }
            }.getOrDefault(false)
            runOnUiThread {
                Toast.makeText(
                    app, if (ok) "Segnalazione inviata, grazie" else "Contenuto tolto; invio della segnalazione non riuscito",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
        return
    }

    if (BuildConfig.CONTATTO_EMAIL.isNotBlank()) {
        val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:"))
            .putExtra(Intent.EXTRA_EMAIL, arrayOf(BuildConfig.CONTATTO_EMAIL))
            .putExtra(Intent.EXTRA_SUBJECT, "Segnalazione contenuto IA: $motivo")
            .putExtra(Intent.EXTRA_TEXT, dati.toString(2))
        runCatching { startActivity(intent) }
            .onFailure { Toast.makeText(this, "Contenuto tolto. Nessuna app email per inviare la segnalazione.", Toast.LENGTH_LONG).show() }
        return
    }

    Toast.makeText(this, "Contenuto tolto dall'app", Toast.LENGTH_SHORT).show()
}
