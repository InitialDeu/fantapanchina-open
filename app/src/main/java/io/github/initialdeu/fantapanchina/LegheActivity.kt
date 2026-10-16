package io.github.initialdeu.fantapanchina

import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

/** Elenco delle leghe: crea, modifica, importa da file, esporta, elimina. */
class LegheActivity : AppCompatActivity() {

    private lateinit var elenco: LinearLayout
    private var daEsportare: Lega? = null

    private val apriFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { importa(it) }
    }

    private val creaFile = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val l = daEsportare
        if (uri != null && l != null) {
            runCatching {
                contentResolver.openOutputStream(uri)?.use { it.write(Leghe.esporta(l).toByteArray()) }
            }.onSuccess { Toast.makeText(this, "Lega esportata", Toast.LENGTH_SHORT).show() }
                .onFailure { Toast.makeText(this, "Esportazione non riuscita: ${it.message}", Toast.LENGTH_LONG).show() }
        }
        daEsportare = null
    }

    override fun onCreate(stato: Bundle?) {
        super.onCreate(stato)
        val radice = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(18), dp(16), dp(40))
        }
        setContentView(ScrollView(this).apply { addView(radice) })
        rispettaBordi()

        radice.addView(TextView(this).apply {
            text = "Leghe"
            textSize = 26f
            setTypeface(typeface, Typeface.BOLD)
        })
        radice.addView(Button(this).apply {
            text = "Nuova lega"
            setOnClickListener { sceltaTipo() }
        })
        radice.addView(Button(this).apply {
            text = "Importa da file (JSON o CSV)"
            setOnClickListener { apriFile.launch(arrayOf("application/json", "text/*", "application/octet-stream")) }
        })
        radice.addView(tenue(
            "JSON: un file esportato da FantaPanchina (anche da un altro telefono). CSV: una riga per " +
                "giocatore con intestazione reparto,nome,crediti,id (id facoltativo, è quello della fonte " +
                "dati). Puoi usare la virgola o il punto e virgola."
        ))
        elenco = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        radice.addView(elenco)
    }

    override fun onResume() {
        super.onResume()
        disegna()
    }

    private fun disegna() {
        elenco.removeAllViews()
        val leghe = Leghe.tutte(this)
        if (leghe.isEmpty()) elenco.addView(tenue("Nessuna lega per ora.").apply { setPadding(0, dp(16), 0, 0) })
        leghe.forEach { l ->
            val scheda = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), dp(12), dp(14), dp(6))
                background = ContextCompat.getDrawable(context, R.drawable.scheda)
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) }
            }
            scheda.addView(TextView(this).apply {
                text = l.nome
                textSize = 18f
                setTypeface(typeface, Typeface.BOLD)
            })
            val dettagli = listOfNotNull(
                l.sport.ifBlank { null },
                "${l.giocatori.size} giocatori",
                l.calendario?.let { "calendario $it" },
                l.fonte?.let { id -> Fonti.tutte.firstOrNull { it.first == id }?.second ?: id },
                l.moduli.takeIf { it.isNotEmpty() }?.joinToString(" ")
            ).joinToString(" · ")
            scheda.addView(tenue(dettagli))
            val pulsanti = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            fun pulsante(t: String, azione: () -> Unit) = Button(this).apply {
                text = t
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
                setOnClickListener { azione() }
            }
            pulsanti.addView(pulsante("Modifica") {
                startActivity(Intent(this, LegaActivity::class.java).putExtra(LegaActivity.EXTRA_ID, l.id))
            })
            pulsanti.addView(pulsante("Esporta") {
                daEsportare = l
                creaFile.launch("${l.id}.json")
            })
            pulsanti.addView(pulsante("Elimina") { conferma(l) })
            scheda.addView(pulsanti)
            elenco.addView(scheda)
        }
    }

    private fun sceltaTipo() {
        val tipi = Fonti.tipi
        AlertDialog.Builder(this)
            .setTitle("Che lega è?")
            .setItems(tipi.map { it.nome }.toTypedArray()) { _, i ->
                startActivity(Intent(this, LegaActivity::class.java).putExtra(LegaActivity.EXTRA_TIPO, i))
            }
            .setNegativeButton("Annulla", null)
            .show()
    }

    private fun conferma(l: Lega) {
        AlertDialog.Builder(this)
            .setTitle("Eliminare ${l.nome}?")
            .setMessage("Spariscono la rosa e i promemoria di questa lega. I report già fatti restano.")
            .setPositiveButton("Elimina") { _, _ ->
                Leghe.elimina(this, l.id)
                disegna()
            }
            .setNegativeButton("Annulla", null)
            .show()
    }

    private fun importa(uri: Uri) {
        val testo = runCatching {
            contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
        }.getOrNull()
        if (testo.isNullOrBlank()) {
            Toast.makeText(this, "File vuoto o illeggibile", Toast.LENGTH_LONG).show()
            return
        }
        val json = testo.trimStart('\uFEFF', ' ', '\n', '\r', '\t').let { it.startsWith("{") || it.startsWith("[") }
        if (json) salvaImportate(testo, "", "") else chiediDatiCsv(testo)
    }

    /** Il CSV contiene solo i giocatori: nome e sport della lega li chiedo qui. */
    private fun chiediDatiCsv(testo: String) {
        val nome = EditText(this).apply { hint = "Nome della lega" }
        val sport = EditText(this).apply { hint = "Sport (es. Basket)" }
        AlertDialog.Builder(this)
            .setTitle("Lega da CSV")
            .setView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(20), dp(8), dp(20), 0)
                addView(nome)
                addView(sport)
            })
            .setPositiveButton("Importa") { _, _ -> salvaImportate(testo, nome.text.toString(), sport.text.toString()) }
            .setNegativeButton("Annulla", null)
            .show()
    }

    private fun salvaImportate(testo: String, nome: String, sport: String) {
        runCatching { Leghe.importa(this, testo, nome, sport) }
            .onSuccess { lette ->
                lette.forEach { Leghe.salva(this, it) }
                Promemoria.programmaTutte(this)
                Toast.makeText(this, "Importate: ${lette.joinToString { it.nome }}", Toast.LENGTH_LONG).show()
                disegna()
            }
            .onFailure { Toast.makeText(this, "Importazione non riuscita: ${it.message}", Toast.LENGTH_LONG).show() }
    }

    private fun tenue(testo: String) = TextView(this).apply {
        text = testo
        textSize = 13f
        setTextColor(ContextCompat.getColor(context, R.color.tenue))
        setPadding(0, dp(4), 0, dp(4))
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
