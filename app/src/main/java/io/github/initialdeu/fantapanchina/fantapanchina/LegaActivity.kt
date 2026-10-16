package io.github.initialdeu.fantapanchina

import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.util.concurrent.Executors

/** Crea o modifica una lega a mano: dati, calendario, fonte, moduli, reparti e giocatori. */
class LegaActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_ID = "id"
        const val EXTRA_TIPO = "tipo"

        private val SPORT_THESPORTSDB = listOf(
            "Soccer", "Basketball", "Volleyball", "Ice Hockey", "American Football", "Baseball",
            "Rugby", "Handball", "Cricket", "Motorsport", "Cycling", "Tennis", "Golf", "Fighting"
        )
    }

    private var idEsistente: String? = null
    private lateinit var nome: EditText
    private lateinit var sport: EditText
    private lateinit var note: EditText
    private lateinit var moduli: EditText
    private lateinit var fonte: Spinner
    private lateinit var testoCalendario: TextView
    private lateinit var contenitoreReparti: LinearLayout

    private var calendario: String? = null
    private val reparti = mutableListOf<Pair<String, MutableList<Giocatore>>>()
    private val fonti = listOf<Pair<String?, String>>(null to "Nessuna") + Fonti.tutte
    private val lavoro = Executors.newSingleThreadExecutor()

    override fun onCreate(stato: Bundle?) {
        super.onCreate(stato)
        val esistente = intent.getStringExtra(EXTRA_ID)?.let { Leghe.perId(this, it) }
        val tipo = Fonti.tipi.getOrNull(intent.getIntExtra(EXTRA_TIPO, -1))
        idEsistente = esistente?.id

        val radice = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(18), dp(16), dp(40))
        }
        setContentView(ScrollView(this).apply { addView(radice) })
        rispettaBordi()

        radice.addView(TextView(this).apply {
            text = if (esistente != null) "Modifica lega" else "Nuova lega"
            textSize = 26f
            setTypeface(typeface, Typeface.BOLD)
        })

        nome = campo(radice, "Nome della lega", esistente?.nome.orEmpty())
        sport = campo(radice, "Sport (es. Calcio, Volley, Basket)", esistente?.sport ?: tipo?.sport.orEmpty())
        note = campo(radice, "Note (regole, crediti, modificatori…)", esistente?.note.orEmpty())

        etichetta(radice, "Calendario (TheSportsDB)")
        calendario = esistente?.calendario ?: tipo?.calendario
        testoCalendario = TextView(this).apply { textSize = 14f }
        radice.addView(testoCalendario)
        val rigaCal = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        rigaCal.addView(Button(this).apply {
            text = "Cerca campionato"
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            setOnClickListener { cercaCampionato() }
        })
        rigaCal.addView(Button(this).apply {
            text = "Nessuno"
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            setOnClickListener {
                calendario = null
                mostraCalendario()
            }
        })
        radice.addView(rigaCal)
        mostraCalendario()

        etichetta(radice, "Fonte dati")
        fonte = Spinner(this).apply {
            adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, fonti.map { it.second })
            val attuale = esistente?.fonte ?: tipo?.fonte
            setSelection(fonti.indexOfFirst { it.first == attuale }.coerceAtLeast(0))
        }
        radice.addView(fonte)

        etichetta(radice, "Moduli")
        moduli = campo(
            radice, "es. 3-4-3, 4-4-2 (vuoto = niente formazioni)",
            (esistente?.moduli ?: tipo?.moduli ?: emptyList()).joinToString(", ")
        )
        radice.addView(tenue(
            "Titolari per reparto separati da trattini, nell'ordine dei reparti qui sotto. Se il modulo ha " +
                "un numero in meno dei reparti, il primo reparto vale 1 (il portiere del calcio: 4-4-2)."
        ))

        etichetta(radice, "Rosa")
        if (esistente != null) {
            esistente.reparti.forEach { (r, gs) -> reparti += r to gs.toMutableList() }
        } else {
            (tipo?.reparti ?: listOf("Giocatori")).forEach { reparti += it to mutableListOf<Giocatore>() }
        }
        contenitoreReparti = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        radice.addView(contenitoreReparti)
        radice.addView(Button(this).apply {
            text = "Aggiungi reparto"
            setOnClickListener { chiediTesto("Nuovo reparto", "Nome del reparto", "") { n -> reparti += n to mutableListOf<Giocatore>(); disegnaReparti() } }
        })
        disegnaReparti()

        radice.addView(Button(this).apply {
            text = "Salva"
            setTypeface(typeface, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(20) }
            setOnClickListener { salva() }
        })
    }

    override fun onDestroy() {
        super.onDestroy()
        lavoro.shutdown()
    }

    private fun mostraCalendario() {
        testoCalendario.text = calendario?.let { "Campionato con id $it: deadline e promemoria dal suo calendario." }
            ?: "Nessun calendario: niente deadline né promemoria automatici."
    }

    private fun cercaCampionato() {
        val paese = EditText(this).apply { hint = "Paese in inglese (es. Italy)"; setText("Italy"); setSingleLine() }
        val sportScelta = Spinner(this).apply {
            adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, SPORT_THESPORTSDB)
        }
        val idManuale = EditText(this).apply {
            hint = "…oppure l'id, se lo conosci"
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        AlertDialog.Builder(this)
            .setTitle("Cerca campionato")
            .setView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(20), dp(8), dp(20), 0)
                addView(paese)
                addView(sportScelta)
                addView(idManuale)
            })
            .setPositiveButton("Cerca") { _, _ ->
                val id = idManuale.text.toString().trim()
                if (id.isNotEmpty()) {
                    calendario = id
                    mostraCalendario()
                    return@setPositiveButton
                }
                val p = paese.text.toString()
                val s = SPORT_THESPORTSDB[sportScelta.selectedItemPosition]
                lavoro.execute {
                    val esito = runCatching { Rete.campionati(p, s) }
                    runOnUiThread { if (!isDestroyed) mostraRisultati(esito) }
                }
            }
            .setNegativeButton("Annulla", null)
            .show()
    }

    private fun mostraRisultati(esito: Result<List<Pair<String, String>>>) {
        val trovati = esito.getOrElse {
            Toast.makeText(this, "Ricerca non riuscita: ${it.message}", Toast.LENGTH_LONG).show()
            return
        }
        if (trovati.isEmpty()) {
            Toast.makeText(this, "Nessun campionato trovato", Toast.LENGTH_LONG).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Scegli il campionato")
            .setItems(trovati.map { "${it.second} (${it.first})" }.toTypedArray()) { _, i ->
                calendario = trovati[i].first
                mostraCalendario()
            }
            .setNegativeButton("Annulla", null)
            .show()
    }

    private fun disegnaReparti() {
        contenitoreReparti.removeAllViews()
        reparti.forEachIndexed { ri, (nomeReparto, giocatori) ->
            val testa = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(14), 0, dp(2))
            }
            testa.addView(TextView(this).apply {
                text = "${nomeReparto.uppercase()} (${giocatori.size})"
                textSize = 13f
                letterSpacing = 0.06f
                setTypeface(typeface, Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            })
            testa.addView(Button(this).apply {
                text = "+ giocatore"
                setOnClickListener { modificaGiocatore(ri, -1) }
            })
            testa.addView(Button(this).apply {
                text = "…"
                setOnClickListener { opzioniReparto(ri) }
            })
            contenitoreReparti.addView(testa)
            giocatori.forEachIndexed { gi, g ->
                contenitoreReparti.addView(TextView(this).apply {
                    text = buildString {
                        append(g.nome)
                        append("  ·  ").append(g.crediti)
                        if (g.idFonte != 0) append("  ·  id ").append(g.idFonte)
                    }
                    textSize = 15f
                    setPadding(dp(4), dp(8), 0, dp(8))
                    setOnClickListener { modificaGiocatore(ri, gi) }
                })
            }
        }
    }

    private fun opzioniReparto(ri: Int) {
        AlertDialog.Builder(this)
            .setTitle(reparti[ri].first)
            .setItems(arrayOf("Rinomina", "Sposta su", "Elimina reparto")) { _, scelta ->
                when (scelta) {
                    0 -> chiediTesto("Rinomina reparto", "Nome", reparti[ri].first) { n ->
                        reparti[ri] = n to reparti[ri].second
                        disegnaReparti()
                    }
                    1 -> if (ri > 0) {
                        val r = reparti.removeAt(ri)
                        reparti.add(ri - 1, r)
                        disegnaReparti()
                    }
                    2 -> {
                        reparti.removeAt(ri)
                        disegnaReparti()
                    }
                }
            }
            .show()
    }

    private fun modificaGiocatore(ri: Int, gi: Int) {
        val g = reparti[ri].second.getOrNull(gi)
        val n = EditText(this).apply { hint = "Nome"; setText(g?.nome.orEmpty()); setSingleLine() }
        val c = EditText(this).apply {
            hint = "Crediti"
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(g?.crediti?.toString().orEmpty())
        }
        val id = EditText(this).apply {
            hint = "Id sulla fonte dati (facoltativo)"
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(g?.idFonte?.takeIf { it != 0 }?.toString().orEmpty())
        }
        val b = AlertDialog.Builder(this)
            .setTitle(if (g == null) "Nuovo giocatore" else "Modifica giocatore")
            .setView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(20), dp(8), dp(20), 0)
                addView(n)
                addView(c)
                addView(id)
            })
            .setPositiveButton("OK") { _, _ ->
                val nuovo = Giocatore(
                    n.text.toString().trim(), c.text.toString().toIntOrNull() ?: 0, id.text.toString().toIntOrNull() ?: 0
                )
                if (nuovo.nome.isEmpty()) return@setPositiveButton
                if (g == null) reparti[ri].second += nuovo else reparti[ri].second[gi] = nuovo
                disegnaReparti()
            }
            .setNegativeButton("Annulla", null)
        if (g != null) b.setNeutralButton("Elimina") { _, _ ->
            reparti[ri].second.removeAt(gi)
            disegnaReparti()
        }
        b.show()
    }

    private fun salva() {
        val n = nome.text.toString().trim()
        if (n.isEmpty()) return avviso("Dai un nome alla lega")
        val pieni = reparti.filter { it.first.isNotBlank() }
        if (pieni.isEmpty()) return avviso("Aggiungi almeno un reparto")
        val nomi = pieni.flatMap { it.second }.map { it.nome.lowercase() }
        if (nomi.size != nomi.toSet().size) return avviso("Ci sono due giocatori con lo stesso nome: distinguili (es. \"Rossi (Inter)\")")

        val lega = Lega(
            id = idEsistente ?: Leghe.nuovoId(this, n),
            nome = n,
            sport = sport.text.toString().trim().ifEmpty { "Altro" },
            note = note.text.toString().trim(),
            calendario = calendario,
            fonte = fonti[fonte.selectedItemPosition].first,
            moduli = moduli.text.toString().split(',', ';', ' ').map { it.trim() }.filter { it.isNotEmpty() },
            reparti = LinkedHashMap(pieni.associate { it.first to it.second.toList() })
        )
        val sbagliati = lega.moduli.filter { Formazioni.conteggi(lega, it) == null }
        if (sbagliati.isNotEmpty()) {
            return avviso("Questi moduli non tornano con ${lega.reparti.size} reparti: ${sbagliati.joinToString()}")
        }
        Leghe.salva(this, lega)
        Promemoria.programma(this, lega)
        Toast.makeText(this, "Lega salvata", Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun avviso(testo: String) {
        Toast.makeText(this, testo, Toast.LENGTH_LONG).show()
    }

    private fun chiediTesto(titolo: String, suggerimento: String, iniziale: String, fatto: (String) -> Unit) {
        val e = EditText(this).apply { hint = suggerimento; setText(iniziale); setSingleLine() }
        AlertDialog.Builder(this)
            .setTitle(titolo)
            .setView(LinearLayout(this).apply {
                setPadding(dp(20), dp(8), dp(20), 0)
                addView(e, LinearLayout.LayoutParams(-1, -2))
            })
            .setPositiveButton("OK") { _, _ -> e.text.toString().trim().takeIf { it.isNotEmpty() }?.let(fatto) }
            .setNegativeButton("Annulla", null)
            .show()
    }

    private fun campo(radice: LinearLayout, suggerimento: String, valore: String) = EditText(this).apply {
        hint = suggerimento
        setText(valore)
        radice.addView(this)
    }

    private fun etichetta(radice: LinearLayout, testo: String) {
        radice.addView(TextView(this).apply {
            text = testo.uppercase()
            textSize = 12f
            letterSpacing = 0.08f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(ContextCompat.getColor(context, R.color.tenue))
            setPadding(0, dp(18), 0, dp(4))
        })
    }

    private fun tenue(testo: String) = TextView(this).apply {
        text = testo
        textSize = 12f
        setTextColor(ContextCompat.getColor(context, R.color.tenue))
        visibility = View.VISIBLE
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
