package io.github.initialdeu.fantapanchina

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.work.WorkManager

/** Versione, licenza, privacy, contatti e cancellazione dei dati. */
fun Activity.informazioni() {
    val dp = { v: Int -> (v * resources.displayMetrics.density).toInt() }
    val testo = buildString {
        append("FantaPanchina ").append(BuildConfig.VERSION_NAME).append("\n\n")
        append("Privacy in breve: l'app non ha un server suo e non raccoglie dati. Leghe, rose, ")
        append("formazioni e report restano sul telefono. Si collega solo a TheSportsDB per il ")
        append("calendario, alle fonti dati delle tue leghe e, se lo colleghi, all'assistente IA che ")
        append("scegli tu: a lui vanno la rosa e i dati della giornata, per avere la formazione e il ")
        append("commento. Le chiavi API sono cifrate e non escono dal telefono se non verso il loro ")
        append("provider.\n\n")
        append("I consigli dell'assistente sono generati dall'IA e possono essere sbagliati: ")
        append("controlla sempre prima di schierare.\n\n")
        append("Codice con licenza MIT. Nessuna garanzia.")
        if (BuildConfig.CONTATTO_EMAIL.isNotBlank()) append("\n\nContatti: ").append(BuildConfig.CONTATTO_EMAIL)
    }
    val corpo = ScrollView(this).apply {
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), 0)
            addView(TextView(context).apply {
                text = testo
                textSize = 14f
                setTextIsSelectable(true)
            })
            // Sorgenti e, se ci sono, le informazioni per sostenere il progetto.
            if (BuildConfig.PROGETTO_URL.isNotBlank()) {
                addView(Button(context).apply {
                    text = "Pagina del progetto"
                    setOnClickListener {
                        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(BuildConfig.PROGETTO_URL))) }
                    }
                })
            }
        })
    }
    val d = AlertDialog.Builder(this)
        .setTitle("Informazioni e privacy")
        .setView(corpo)
        .setNegativeButton("Cancella tutti i dati") { _, _ -> confermaCancellazione() }
        .setNeutralButton("Chiudi", null)
    if (BuildConfig.PRIVACY_URL.isNotBlank()) {
        d.setPositiveButton("Privacy policy") { _, _ ->
            runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(BuildConfig.PRIVACY_URL))) }
        }
    }
    d.show()
}

private fun Activity.confermaCancellazione() {
    AlertDialog.Builder(this)
        .setTitle("Cancellare tutti i dati?")
        .setMessage(
            "Spariscono leghe, rose, formazioni, report, impostazioni e chiavi dell'assistente. " +
                "Non si può annullare: se vuoi tenere una lega, esportala prima da Gestisci leghe."
        )
        .setPositiveButton("Cancella") { _, _ ->
            cancellaTutto(applicationContext)
            Toast.makeText(this, "Dati cancellati", Toast.LENGTH_SHORT).show()
            finishAffinity()
            startActivity(Intent(this, MainActivity::class.java))
        }
        .setNegativeButton("Annulla", null)
        .show()
}

/** Toglie tutto quello che l'app ha salvato: preferenze, file, avvisi e lavori in background. */
private fun cancellaTutto(ctx: Context) {
    Leghe.tutte(ctx).forEach { Promemoria.annulla(ctx, it.id) }
    WorkManager.getInstance(ctx).cancelAllWork()
    listOf("fantapanchina", "archivio", "report", "assistente", "claude", "volley").forEach {
        ctx.deleteSharedPreferences(it)
    }
    ctx.filesDir.listFiles()?.forEach { it.deleteRecursively() }
    Leghe.dimentica()
}
