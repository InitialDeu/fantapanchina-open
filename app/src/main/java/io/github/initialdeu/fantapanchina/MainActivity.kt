package io.github.initialdeu.fantapanchina

import android.Manifest
import android.app.AlarmManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var scelta: Spinner
    private lateinit var note: TextView
    private lateinit var testoDeadline: TextView
    private lateinit var bottoneAggiorna: Button
    private lateinit var bottoneSchierata: Button
    private lateinit var bottoneAssistente: Button
    private lateinit var avvisoEsatti: TextView
    private lateinit var testoFonti: TextView
    private lateinit var contenitore: LinearLayout

    private var leghe: List<Lega> = emptyList()
    private var idx = 0
    private var inAggiornamento = false
    private var assistenteAlLavoro = false
    private var ultimiErrori: List<String> = emptyList()
    private val lavoro = Executors.newSingleThreadExecutor()

    private val formato = SimpleDateFormat("EEEE d MMMM 'alle' HH:mm", Locale.ITALIAN)
    private val formatoBreve = SimpleDateFormat("d/M HH:mm", Locale.ITALIAN)

    private val lega: Lega? get() = leghe.getOrNull(idx)

    override fun onCreate(stato: Bundle?) {
        super.onCreate(stato)
        setContentView(R.layout.activity_main)
        rispettaBordi()

        scelta = findViewById(R.id.scelta)
        note = findViewById(R.id.note)
        testoDeadline = findViewById(R.id.testo_deadline)
        bottoneAggiorna = findViewById(R.id.bottone_aggiorna)
        bottoneSchierata = findViewById(R.id.bottone_schierata)
        bottoneAssistente = findViewById(R.id.bottone_claude)
        avvisoEsatti = findViewById(R.id.avviso_esatti)
        testoFonti = findViewById(R.id.testo_fonti)
        contenitore = findViewById(R.id.contenitore)

        Promemoria.creaCanale(this)
        Report.creaCanale(this)
        Fonti.notizie.forEach { it.creaCanale(this) }
        chiediNotifiche()
        Aggiornamento.pianifica(this)

        scelta.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                idx = pos
                disegna()
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }

        bottoneAggiorna.setOnClickListener { aggiorna() }
        findViewById<Button>(R.id.bottone_report).setOnClickListener {
            startActivity(Intent(this, ReportActivity::class.java))
        }
        findViewById<Button>(R.id.bottone_leghe).setOnClickListener {
            startActivity(Intent(this, LegheActivity::class.java))
        }
        bottoneAssistente.setOnClickListener { impostazioniAssistente { disegna() } }
        findViewById<Button>(R.id.bottone_info).setOnClickListener { informazioni() }
        bottoneSchierata.setOnClickListener {
            val l = lega ?: return@setOnClickListener
            val giornata = Promemoria.prossima(this, l) ?: return@setOnClickListener
            val nuovo = !Prefs.schierata(this, l.id, giornata.numero)
            Prefs.setSchierata(this, l.id, giornata.numero, nuovo)
            Promemoria.programma(this, l)
            disegna()
        }

        caricaLeghe()
    }

    override fun onResume() {
        super.onResume()
        // Le leghe possono essere cambiate in Gestisci leghe.
        if (Leghe.tutte(this) != leghe) caricaLeghe()
        // Dati vecchi di più di mezz'ora: riscarica da solo.
        if (System.currentTimeMillis() - Archivio.ultimoAggiornamento(this) > 30 * 60 * 1000L) {
            aggiorna()
        } else {
            disegna()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        lavoro.shutdown()
    }

    private fun caricaLeghe() {
        val attuale = lega?.id
        leghe = Leghe.tutte(this)
        scelta.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, leghe.map { it.nome })
        idx = leghe.indexOfFirst { it.id == attuale }.coerceAtLeast(0)
        if (leghe.isNotEmpty()) scelta.setSelection(idx)
        disegna()
    }

    private fun aggiorna() {
        if (inAggiornamento) return
        inAggiornamento = true
        disegna()
        lavoro.execute {
            val errori = Archivio.aggiorna(applicationContext)
            Promemoria.programmaTutte(applicationContext)
            runOnUiThread {
                inAggiornamento = false
                ultimiErrori = errori
                if (!isDestroyed) disegna()
            }
        }
    }

    /** Chiede all'assistente la formazione della prossima giornata per la lega aperta. */
    private fun chiediAssistente() {
        val l = lega ?: return
        if (assistenteAlLavoro) return
        assistenteAlLavoro = true
        disegna()
        val giornata = Promemoria.prossima(this, l)
        val conFonte = Fonti.calcio(l.fonte) != null
        lavoro.execute {
            val esito = runCatching {
                val c = Assistente.formazione(
                    applicationContext, l, giornata,
                    if (conFonte) Archivio.probabili(applicationContext) else null,
                    if (conFonte) Archivio.votiPrecedenti(applicationContext) else null
                )
                Assistente.salvaConsiglio(applicationContext, l, c)
                Promemoria.programmaTutte(applicationContext)
            }
            runOnUiThread {
                assistenteAlLavoro = false
                esito.exceptionOrNull()?.let {
                    Toast.makeText(this, "${Assistente.nome(this)}: ${it.message}", Toast.LENGTH_LONG).show()
                }
                if (!isDestroyed) disegna()
            }
        }
    }

    private fun chiediNotifiche() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    private fun puoProgrammareEsatti(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        val am = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        return am.canScheduleExactAlarms()
    }

    private fun disegna() {
        bottoneAggiorna.text = if (inAggiornamento) "Aggiornamento…" else "Aggiorna"
        bottoneAggiorna.isEnabled = !inAggiornamento
        bottoneAssistente.text = if (Assistente.attivo(this)) "Assistente: ${Assistente.nome(this)}" else "Collega un assistente"
        avvisoEsatti.visibility = if (puoProgrammareEsatti()) View.GONE else View.VISIBLE
        avvisoEsatti.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM))
            }
        }

        val l = lega
        contenitore.removeAllViews()
        if (l == null) {
            note.text = ""
            testoDeadline.text = "Nessuna lega. Creane una o importala da file con \"Gestisci leghe\"."
            bottoneSchierata.isEnabled = false
            testoFonti.text = ""
            return
        }
        note.text = listOf(l.sport, l.note).filter { it.isNotBlank() }.joinToString(" · ")

        val cal = Archivio.calendario(this, l.calendario)
        val giornata = cal?.prossima
        val fatta = giornata != null && Prefs.schierata(this, l.id, giornata.numero)

        val righe = mutableListOf<String>()
        cal?.inCorso?.let { righe += "Giornata ${it.numero} in corso." }
        righe += when {
            l.calendario == null -> "Nessun calendario collegato: aggiungilo in Gestisci leghe per avere deadline e promemoria."
            cal == null -> "Calendario non ancora scaricato."
            giornata == null -> "Nessuna giornata in programma nel calendario."
            fatta -> "Giornata ${giornata.numero} schierata. Promemoria disattivati."
            else -> "Giornata ${giornata.numero}: deadline ${formato.format(Date(giornata.inizio))} " +
                "(primo fischio).\nAvvisi a 24h, 6h e 1h prima."
        }
        testoDeadline.text = righe.joinToString("\n")
        bottoneSchierata.text = if (fatta) "Annulla \"ho schierato\"" else "Ho schierato"
        bottoneSchierata.isEnabled = giornata != null

        val fonteCalcio = Fonti.calcio(l.fonte)
        val notizie = Fonti.notizie(l.fonte)
        val probabili = if (fonteCalcio != null) Archivio.probabili(this) else null
        val voti = if (fonteCalcio != null) Archivio.votiPrecedenti(this) else null
        val consiglio = Assistente.consiglio(this, l, giornata?.numero ?: 0)
        val consigliata = consiglio?.schierata ?: probabili?.let { Formazioni.consigliata(l, it) }
        testoFonti.text = fonti(l, cal, probabili, voti, consigliata, fonteCalcio, notizie)

        if (consigliata != null || l.moduli.isNotEmpty()) {
            contenitore.addView(schedaFormazione(l, consigliata, consiglio, probabili?.giornata, giornata?.numero))
        }
        l.reparti.forEach { (reparto, giocatori) ->
            contenitore.addView(intestazione(reparto))
            giocatori
                .map { it to probabili?.perId?.get(it.idFonte) }
                .sortedByDescending { (_, prev) -> punteggio(prev) }
                .forEach { (gioc, prev) ->
                    val titolare = consigliata?.titolari?.contains(gioc.chiave) == true
                    val allarme = notizie?.recente(this, gioc)
                    val motivo = consiglio?.motivi?.get(gioc.chiave)
                    contenitore.addView(riga(l, gioc, prev, voti, titolare, allarme, motivo))
                }
        }
    }

    private fun fonti(
        l: Lega, cal: Calendario?, probabili: Probabili?, voti: Voti?, consigliata: Schierata?,
        fonteCalcio: FonteCalcio?, notizie: FonteNotizie?
    ): String {
        val righe = mutableListOf<String>()
        when {
            notizie != null -> righe += "L'app legge le notizie di ${notizie.nome} e segna in rosso chi compare " +
                "in una notizia di infortunio (ultimi 14 giorni). Tocca il giocatore per aprire l'articolo."
            fonteCalcio != null && probabili != null -> righe += "Probabili formazioni giornata ${probabili.giornata} " +
                "(${fonteCalcio.nome}), aggiornate ${probabili.aggiornamento}."
            fonteCalcio != null -> righe += "Probabili formazioni non ancora scaricate."
            else -> righe += "Nessuna fonte dati per questa lega: calendario e assistente."
        }
        consigliata?.let { righe += "In grassetto i titolari della formazione da schierare (${it.modulo})." }
        if (fonteCalcio != null && Assistente.attivo(this)) {
            righe += "${Assistente.nome(this)} sceglie e commenta da solo la formazione appena escono le probabili " +
                "della giornata, e la ricontrolla nelle ultime 8 ore prima della deadline."
        }
        voti?.let { righe += "Sotto ogni nome il voto della giornata ${it.giornata} (voto · fantavoto)." }
        cal?.let { righe += "Calendario controllato il ${formatoBreve.format(Date(it.aggiornato))}." }
        if (ultimiErrori.isNotEmpty()) {
            righe += "Ultimo aggiornamento non riuscito, mostro i dati salvati:"
            righe += ultimiErrori
        }
        return righe.joinToString("\n")
    }

    /** Ordine dentro il reparto: titolari più probabili in alto, out in fondo. */
    private fun punteggio(p: Previsione?): Int = if (p == null) 50 else when (p.esito) {
        Esito.TITOLARE -> 200 + p.percentuale
        Esito.PANCHINA -> 100 + p.percentuale
        Esito.INFORTUNATO, Esito.SQUALIFICATO -> 0
    }

    private fun intestazione(testo: String) = TextView(this).apply {
        text = testo.uppercase(Locale.ITALIAN)
        textSize = 13f
        letterSpacing = 0.08f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(ContextCompat.getColor(context, R.color.tenue))
        setPadding(0, dp(18), 0, dp(6))
    }

    /** Il riquadro con la formazione pronta da copiare e schierare, o il pulsante per chiederla. */
    private fun schedaFormazione(l: Lega, s: Schierata?, consiglio: Consiglio?, giornataProbabili: Int?, daSchierare: Int?): View {
        val chi = Assistente.nome(this)
        val scheda = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(8))
            background = ContextCompat.getDrawable(context, R.drawable.scheda)
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) }
        }
        scheda.addView(TextView(this).apply {
            text = if (consiglio != null) "Formazione di $chi" else "Formazione da schierare"
            textSize = 17f
            setTypeface(typeface, Typeface.BOLD)
        })
        if (consiglio == null && giornataProbabili != null && daSchierare != null && daSchierare != giornataProbabili) {
            scheda.addView(TextView(this).apply {
                text = "Le probabili della giornata $daSchierare non sono ancora uscite: questa è " +
                    "basata su quelle della giornata $giornataProbabili. Si aggiorna da sola."
                textSize = 12f
                setTextColor(ContextCompat.getColor(context, R.color.dubbio))
                setPadding(0, dp(4), 0, 0)
            })
        }
        if (s != null) {
            val testo = Formazioni.testo(l, s)
            scheda.addView(TextView(this).apply {
                text = testo
                textSize = 14f
                setLineSpacing(0f, 1.2f)
                setTextIsSelectable(true)
                setPadding(0, dp(8), 0, 0)
            })
            scheda.addView(Button(this).apply {
                text = "Copia"
                setOnClickListener {
                    val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("Formazione ${l.nome}", "${l.nome}\n$testo"))
                    Toast.makeText(context, "Formazione copiata", Toast.LENGTH_SHORT).show()
                }
            })
        } else {
            scheda.addView(TextView(this).apply {
                text = "Nessuna formazione ancora: chiedila all'assistente."
                textSize = 13f
                setPadding(0, dp(4), 0, 0)
            })
        }

        // Il commento dell'assistente sulla formazione: perché questi titolari e quali dubbi.
        if (consiglio != null) {
            scheda.addView(TextView(this).apply {
                text = "Il commento di $chi"
                textSize = 15f
                setTypeface(typeface, Typeface.BOLD)
                setPadding(0, dp(12), 0, 0)
            })
            scheda.addView(TextView(this).apply {
                text = consiglio.spiegazione
                textSize = 14f
                setLineSpacing(0f, 1.15f)
                setTextIsSelectable(true)
                setPadding(0, dp(4), 0, 0)
            })
            scheda.addView(TextView(this).apply {
                text = "Scritto il ${formatoBreve.format(Date(consiglio.creato))}. Il motivo di ogni titolare è " +
                    "sotto il suo nome. Generato dall'IA: può contenere errori, controlla prima di schierare."
                textSize = 12f
                setTextColor(ContextCompat.getColor(context, R.color.tenue))
                setPadding(0, dp(4), 0, 0)
            })
        } else if (Assistente.attivo(this) && Fonti.calcio(l.fonte) != null && daSchierare != null) {
            scheda.addView(TextView(this).apply {
                text = if (giornataProbabili == daSchierare) {
                    "$chi sta per commentare questa formazione: arriva con il prossimo aggiornamento, " +
                        "oppure chiedila subito qui sotto."
                } else {
                    "$chi commenterà la formazione appena escono le probabili della giornata $daSchierare."
                }
                textSize = 13f
                setTextColor(ContextCompat.getColor(context, R.color.tenue))
                setPadding(0, dp(10), 0, 0)
            })
        }

        if (consiglio != null && s != null) {
            scheda.addView(Button(this).apply {
                text = "Segnala questa formazione"
                setOnClickListener {
                    val contenuto = Formazioni.testo(l, s) + "\n\n" + consiglio.spiegazione + "\n\n" +
                        consiglio.motivi.entries.joinToString("\n") { (k, m) ->
                            "${l.giocatori.firstOrNull { it.chiave == k }?.nome ?: k}: $m"
                        }
                    segnalaContenutoIa("formazione", contenuto) {
                        Assistente.eliminaConsiglio(this@MainActivity, l, consiglio.giornata)
                        disegna()
                    }
                }
            })
        }
        if (l.moduli.isNotEmpty()) {
            scheda.addView(Button(this).apply {
                text = when {
                    assistenteAlLavoro -> "$chi sta scegliendo…"
                    !Assistente.attivo(context) -> "Collega un assistente per fargliela scegliere"
                    consiglio != null -> "Rifalla con $chi"
                    else -> "Chiedi a $chi"
                }
                isEnabled = !assistenteAlLavoro
                setOnClickListener {
                    if (Assistente.attivo(context)) chiediAssistente() else impostazioniAssistente { disegna() }
                }
            })
        }
        return scheda
    }

    private fun riga(
        l: Lega, gioc: Giocatore, prev: Previsione?, voti: Voti?, titolare: Boolean, allarme: Allarme?, motivo: String?
    ): View {
        val colore = when {
            allarme != null -> R.color.fuori
            prev == null -> R.color.tenue
            prev.esito == Esito.INFORTUNATO || prev.esito == Esito.SQUALIFICATO -> R.color.fuori
            prev.esito == Esito.TITOLARE && prev.percentuale >= 70 -> R.color.dentro
            prev.esito == Esito.TITOLARE || prev.percentuale >= 40 -> R.color.dubbio
            else -> R.color.fuori
        }
        val fuori = prev?.esito == Esito.INFORTUNATO || prev?.esito == Esito.SQUALIFICATO

        val riga = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(9), 0, dp(9))
        }

        val pallino = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(10), dp(10)).apply { rightMargin = dp(10) }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(ContextCompat.getColor(context, colore))
            }
        }

        val colonna = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        }

        colonna.addView(TextView(this).apply {
            text = gioc.nome
            textSize = 16f
            if (titolare) setTypeface(typeface, Typeface.BOLD)
            if (fuori) {
                paintFlags = paintFlags or android.graphics.Paint.STRIKE_THRU_TEXT_FLAG
                setTextColor(ContextCompat.getColor(context, R.color.tenue))
            }
        })

        val precedente = if (voti == null || gioc.idFonte == 0) null else {
            val v = voti.perId[gioc.idFonte]
            "G${voti.giornata}: " + when {
                v == null -> "non entrato"
                v.voto == null || v.fantavoto == null -> "s.v."
                else -> "${Report.numero(v.voto)} · ${Report.numero(v.fantavoto)}" +
                    v.bonus.takeIf { it.isNotEmpty() }?.let { " ($it)" }.orEmpty()
            }
        }
        val notizia = allarme?.let { "Notizia del ${formatoBreve.format(Date(it.data))}: ${it.frase}" }
        val perAssistente = motivo?.let { "${Assistente.nome(this)}: $it" }
        val dettaglio = listOfNotNull(prev?.squadra, prev?.nota?.takeIf { it.isNotEmpty() }, precedente, notizia, perAssistente)
            .joinToString(" · ")
        if (dettaglio.isNotEmpty()) {
            colonna.addView(TextView(this).apply {
                text = dettaglio
                textSize = 12f
                setTextColor(ContextCompat.getColor(context, R.color.tenue))
            })
        }

        val etichetta = TextView(this).apply {
            text = if (allarme != null) "infortunio?" else if (prev == null) {
                if (Fonti.calcio(l.fonte) != null && gioc.idFonte != 0) "non in lista" else ""
            } else when (prev.esito) {
                Esito.TITOLARE -> "titolare ${prev.percentuale}%"
                Esito.PANCHINA -> "panchina ${prev.percentuale}%"
                Esito.INFORTUNATO -> "infortunato"
                Esito.SQUALIFICATO -> "squalificato"
            }
            textSize = 12f
            setTextColor(ContextCompat.getColor(context, colore))
            setPadding(dp(8), 0, dp(10), 0)
        }

        val crediti = TextView(this).apply {
            text = gioc.crediti.toString()
            textSize = 14f
            setTextColor(ContextCompat.getColor(context, R.color.tenue))
        }

        if (allarme != null) {
            riga.setOnClickListener {
                androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle("${gioc.nome}: possibile infortunio")
                    .setMessage("${allarme.frase}\n\n${allarme.titolo}")
                    .setPositiveButton("Apri l'articolo") { _, _ ->
                        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(allarme.link))) }
                    }
                    .setNeutralButton("Non è un infortunio") { _, _ ->
                        Fonti.notizie(l.fonte)?.ignora(this, allarme)
                        disegna()
                    }
                    .setNegativeButton("Chiudi", null)
                    .show()
            }
        }

        riga.addView(pallino)
        riga.addView(colonna)
        riga.addView(etichetta)
        riga.addView(crediti)
        return riga
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
