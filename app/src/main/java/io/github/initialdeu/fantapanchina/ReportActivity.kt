package io.github.initialdeu.fantapanchina

import android.content.Intent
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Storico dei report di fine giornata. */
class ReportActivity : AppCompatActivity() {

    private lateinit var contenitore: LinearLayout
    private var giornate: List<Int> = emptyList()
    private var attuale: JSONObject? = null

    private val formatoData = SimpleDateFormat("d MMMM 'alle' HH:mm", Locale.ITALIAN)

    override fun onCreate(stato: Bundle?) {
        super.onCreate(stato)

        val radice = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(18), dp(16), dp(40))
        }
        setContentView(ScrollView(this).apply { addView(radice) })
        rispettaBordi()

        radice.addView(TextView(this).apply {
            text = "Report giornate"
            textSize = 26f
            setTypeface(typeface, Typeface.BOLD)
        })

        giornate = Report.elenco(this)
        if (giornate.isEmpty()) {
            radice.addView(tenue(
                "Ancora nessun report. Arriva da solo con una notifica qualche ora dopo l'ultima " +
                    "partita della giornata, e resta qui.", 14f
            ).apply { setPadding(0, dp(12), 0, 0) })
            return
        }

        val scelta = Spinner(this)
        scelta.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, giornate.map { "Giornata $it" }
        )
        radice.addView(scelta)

        radice.addView(Button(this).apply {
            text = "Condividi"
            setOnClickListener {
                val r = attuale ?: return@setOnClickListener
                startActivity(Intent.createChooser(
                    Intent(Intent.ACTION_SEND).setType("text/plain")
                        .putExtra(Intent.EXTRA_TEXT, Report.testo(r)),
                    "Condividi il report"
                ))
            }
        })

        contenitore = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        radice.addView(contenitore)

        scelta.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) = mostra(giornate[pos])
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        val richiesta = intent.getIntExtra(Report.EXTRA_GIORNATA, -1)
        scelta.setSelection(giornate.indexOf(richiesta).coerceAtLeast(0))
    }

    private fun mostra(giornata: Int) {
        contenitore.removeAllViews()
        val r = Report.leggi(this, giornata)
        attuale = r
        if (r == null) {
            contenitore.addView(tenue("Report illeggibile.", 14f))
            return
        }

        val stato = if (r.optBoolean("definitivo")) "Voti definitivi" else
            "Voti provvisori: il report si aggiorna da solo fino a 48 ore dopo la giornata"
        contenitore.addView(tenue("$stato · ${formatoData.format(Date(r.getLong("creato")))}", 12f)
            .apply { setPadding(0, dp(4), 0, 0) })

        r.optString("commento").takeIf { it.isNotEmpty() }?.let { commento ->
            contenitore.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), dp(12), dp(14), dp(12))
                background = ContextCompat.getDrawable(context, R.drawable.scheda)
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) }
                addView(TextView(context).apply {
                    text = "Il commento di " + r.optString("autore_commento").ifEmpty { "Claude" }
                    textSize = 16f
                    setTypeface(typeface, Typeface.BOLD)
                })
                addView(TextView(context).apply {
                    text = commento
                    textSize = 14f
                    setLineSpacing(0f, 1.15f)
                    setTextIsSelectable(true)
                    setPadding(0, dp(6), 0, 0)
                })
                addView(tenue("Generato dall'IA: può contenere errori.", 12f).apply { setPadding(0, dp(6), 0, 0) })
                addView(Button(context).apply {
                    text = "Segnala questo commento"
                    setOnClickListener {
                        segnalaContenutoIa("commento", commento) {
                            Report.rimuoviCommento(this@ReportActivity, giornata)
                            mostra(giornata)
                        }
                    }
                })
            })
        }

        val leghe = r.getJSONArray("leghe")
        for (i in 0 until leghe.length()) lega(leghe.getJSONObject(i))

        contenitore.addView(tenue(
            "Fantavoti della fonte dati della lega, senza modificatori di lega. Formazione consigliata " +
                "= quella che l'app (o l'assistente, se collegato) indicava prima della deadline, con fino a ${Formazioni.MAX_CAMBI} " +
                "cambi automatici dalla panchina.", 12f
        ).apply { setPadding(0, dp(22), 0, 0) })
    }

    private fun lega(l: JSONObject) {
        contenitore.addView(TextView(this).apply {
            text = l.getString("nome")
            textSize = 20f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(22), 0, dp(4))
        })

        val consigliata = l.optJSONObject("consigliata")
        val migliore = l.optJSONObject("migliore")
        val righe = mutableListOf<String>()
        if (consigliata != null) {
            val cambi = consigliata.optInt("cambi")
            val chi = Report.autoreConsigliata(consigliata)?.let { "Consigliata da $it" } ?: "Consigliata dall'app"
            righe += "$chi (${consigliata.getString("modulo")}): " +
                "${Report.numero(consigliata.getDouble("punti"))} punti" +
                if (cambi > 0) " · $cambi ${if (cambi == 1) "cambio" else "cambi"}" else ""
        } else {
            righe += "Nessuna formazione consigliata salvata prima della deadline."
        }
        if (migliore != null) {
            righe += "Migliore possibile (${migliore.getString("modulo")}): " +
                "${Report.numero(migliore.getDouble("punti"))} punti"
        }
        contenitore.addView(TextView(this).apply {
            text = righe.joinToString("\n")
            textSize = 14f
        })

        val titolari = consigliata?.optJSONArray("titolari")
            ?.let { a -> (0 until a.length()).map { a.getInt(it) }.toSet() } ?: emptySet()

        val rosa = l.getJSONArray("rosa")
        val conVoto = (0 until rosa.length()).map { rosa.getJSONObject(it) }.filter { it.has("fv") }
        if (conVoto.isNotEmpty()) {
            val top = conVoto.sortedByDescending { it.getDouble("fv") }
            fun elenco(xs: List<JSONObject>) =
                xs.joinToString(", ") { "${it.getString("nome")} ${Report.numero(it.getDouble("fv"))}" }
            contenitore.addView(tenue(
                "Migliori: ${elenco(top.take(3))}\nPeggiori: ${elenco(top.takeLast(3).reversed())}", 13f
            ).apply { setPadding(0, dp(6), 0, 0) })
        }

        var reparto = ""
        for (j in 0 until rosa.length()) {
            val g = rosa.getJSONObject(j)
            if (g.getString("reparto") != reparto) {
                reparto = g.getString("reparto")
                contenitore.addView(tenue(reparto.uppercase(Locale.ITALIAN), 12f).apply {
                    letterSpacing = 0.08f
                    setTypeface(typeface, Typeface.BOLD)
                    setPadding(0, dp(14), 0, dp(2))
                })
            }
            contenitore.addView(riga(g, g.optInt("id") in titolari))
        }
    }

    private fun riga(g: JSONObject, consigliato: Boolean): View {
        val stato = g.getString("stato")
        val riga = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, dp(6))
        }
        val colonna = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        }
        colonna.addView(TextView(this).apply {
            text = g.getString("nome")
            textSize = 15f
            if (consigliato) setTypeface(typeface, Typeface.BOLD)
            if (stato != "ok") {
                setTextColor(ContextCompat.getColor(context, R.color.tenue))
                if (stato == "ne") paintFlags = paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
            }
        })
        g.optString("bonus").takeIf { it.isNotEmpty() }?.let { colonna.addView(tenue(it, 12f)) }
        riga.addView(colonna)

        val fv = g.optDouble("fv")
        riga.addView(TextView(this).apply {
            text = when (stato) {
                "ne" -> "non entrato"
                "sv" -> "s.v."
                else -> "${Report.numero(g.getDouble("voto"))}  ·  fv ${Report.numero(fv)}"
            }
            textSize = 14f
            setTextColor(ContextCompat.getColor(context, when {
                stato != "ok" -> R.color.tenue
                fv >= 7 -> R.color.dentro
                fv < 6 -> R.color.fuori
                else -> R.color.dubbio
            }))
        })
        return riga
    }

    private fun tenue(testo: String, size: Float) = TextView(this).apply {
        text = testo
        textSize = size
        setTextColor(ContextCompat.getColor(context, R.color.tenue))
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
