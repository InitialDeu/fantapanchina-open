package io.github.initialdeu.fantapanchina

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale

/** Una formazione: modulo, titolari e panchina (in ordine di preferenza), come [Giocatore.chiave]. */
data class Schierata(val modulo: String, val titolari: List<Int>, val panchina: List<Int>)

data class Riga(val giocatore: Giocatore, val reparto: Int, val fantavoto: Double?, val daPanchina: Boolean)

data class Punteggio(val modulo: String, val punti: Double, val righe: List<Riga>, val cambi: Int)

/** Calcoli sulle formazioni. Senza modificatori di lega. */
object Formazioni {

    /** Cambi automatici dalla panchina per chi resta senza voto. */
    const val MAX_CAMBI = 3

    /**
     * Titolari per reparto. "4-4-2" con 4 reparti → [1, 4, 4, 2] (il primo reparto, il portiere,
     * è sottinteso); "1-2-2-1-1" con 5 reparti → così com'è. Null se il modulo non torna.
     */
    fun conteggi(lega: Lega, modulo: String): List<Int>? {
        val n = modulo.split('-').map { it.trim().toIntOrNull() ?: return null }
        return when (n.size) {
            lega.reparti.size -> n
            lega.reparti.size - 1 -> listOf(1) + n
            else -> null
        }
    }

    private fun reparti(lega: Lega) = lega.reparti.values.toList()

    /** Moduli della lega che hanno senso con i suoi reparti. */
    private fun moduli(lega: Lega) = lega.moduli.filter { conteggi(lega, it) != null }

    /** Quanto conviene schierarlo secondo le probabili formazioni. */
    private fun previsione(p: Previsione?): Double = if (p == null) 0.0 else when (p.esito) {
        Esito.TITOLARE -> p.percentuale.toDouble()
        Esito.PANCHINA -> p.percentuale / 3.0
        Esito.INFORTUNATO, Esito.SQUALIFICATO -> -1.0
    }

    fun consigliata(lega: Lega, pr: Probabili): Schierata? {
        val moduli = moduli(lega)
        if (moduli.isEmpty()) return null
        val ordinati = reparti(lega).map { gs ->
            gs.sortedWith(
                compareByDescending<Giocatore> { previsione(pr.perId[it.idFonte]) }.thenByDescending { it.crediti }
            )
        }
        val modulo = moduli.maxBy { m ->
            conteggi(lega, m)!!.zip(ordinati).sumOf { (n, gs) -> gs.take(n).sumOf { previsione(pr.perId[it.idFonte]) } }
        }
        val n = conteggi(lega, modulo)!!
        return Schierata(
            modulo,
            ordinati.zip(n).flatMap { (gs, k) -> gs.take(k).map { it.chiave } },
            ordinati.zip(n).flatMap { (gs, k) -> gs.drop(k).map { it.chiave } }
        )
    }

    /**
     * La formazione da schierare in chiaro, un reparto per riga e poi la panchina in ordine:
     * "Portieri: Rossi", "Difensori: Bianchi, Verdi, ...", "Panchina: Neri, ...".
     */
    fun testo(lega: Lega, s: Schierata): String {
        val perChiave = lega.giocatori.associateBy { it.chiave }
        val righe = lega.reparti.map { (reparto, giocatori) ->
            val chiavi = giocatori.map { it.chiave }.toSet()
            "$reparto: " + s.titolari.filter { it in chiavi }.mapNotNull { perChiave[it]?.nome }.joinToString(", ")
        }
        return (listOf("Modulo ${s.modulo}") + righe +
            ("Panchina: " + s.panchina.mapNotNull { perChiave[it]?.nome }.joinToString(", "))).joinToString("\n")
    }

    /** Punti fatti da una formazione, con i cambi dalla panchina per chi non ha preso voto. */
    fun punteggio(lega: Lega, s: Schierata, voti: Voti): Punteggio {
        val reparti = reparti(lega)
        val perChiave = reparti.flatten().associateBy { it.chiave }
        val repartoDi = reparti.flatMapIndexed { i, gs -> gs.map { it.chiave to i } }.toMap()
        val fv = { chiave: Int -> perChiave[chiave]?.let { voti.perId[it.idFonte]?.fantavoto } }

        val usati = mutableSetOf<Int>()
        var cambi = 0
        val righe = s.titolari.mapNotNull { chiave ->
            val g = perChiave[chiave] ?: return@mapNotNull null
            val r = repartoDi.getValue(chiave)
            val suo = fv(chiave)
            if (suo != null) return@mapNotNull Riga(g, r, suo, false)
            val cambio = if (cambi < MAX_CAMBI) {
                s.panchina.firstOrNull { it !in usati && repartoDi[it] == r && fv(it) != null }
            } else null
            if (cambio == null) Riga(g, r, null, false)
            else {
                usati += cambio
                cambi++
                Riga(perChiave.getValue(cambio), r, fv(cambio), true)
            }
        }
        return Punteggio(s.modulo, righe.sumOf { it.fantavoto ?: 0.0 }, righe, cambi)
    }

    /** La formazione migliore possibile col senno di poi, tra i moduli ammessi. */
    fun migliore(lega: Lega, voti: Voti): Punteggio? {
        val moduli = moduli(lega)
        if (moduli.isEmpty()) return null
        val fv = { g: Giocatore -> voti.perId[g.idFonte]?.fantavoto }
        val ordinati = reparti(lega).map { gs -> gs.filter { fv(it) != null }.sortedByDescending { fv(it) } }
        val modulo = moduli.maxBy { m ->
            conteggi(lega, m)!!.zip(ordinati).sumOf { (n, gs) -> gs.take(n).sumOf { fv(it) ?: 0.0 } }
        }
        val righe = ordinati.zip(conteggi(lega, modulo)!!).flatMapIndexed { r, (gs, k) ->
            gs.take(k).map { Riga(it, r, fv(it), false) }
        }
        return Punteggio(modulo, righe.sumOf { it.fantavoto ?: 0.0 }, righe, 0)
    }
}

/**
 * Report di fine giornata, uno per giornata e per sport, salvati sul telefono. Per il calcio: la
 * formazione consigliata, la migliore possibile e i voti della rosa. Altri sport (volley) li
 * costruisce la loro fonte dati e li pubblica con [pubblica].
 *
 * Ogni report ha una chiave: "g007" per la giornata 7 del calcio, "v001" per la giornata 1 del volley.
 */
object Report {

    private const val PREFS = "report"
    private const val CANALE = "report"
    const val EXTRA_CHIAVE = "report"

    /** Dopo quanto dall'ultimo calcio d'inizio si prova il primo report (partita + voti). */
    private const val ATTESA = 4L * 60 * 60 * 1000

    /** Fino a quando si riscarica per avere i voti definitivi. */
    private const val DEFINITIVO = 48L * 60 * 60 * 1000

    private val NOME_FILE = Regex("""([a-z])(\d+)\.json""")

    private fun p(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun cartella(ctx: Context) = File(ctx.filesDir, "report").apply { mkdirs() }

    private fun file(ctx: Context, chiave: String) = File(cartella(ctx), "$chiave.json")

    fun chiaveCalcio(giornata: Int) = String.format(Locale.ROOT, "g%03d", giornata)
    fun chiaveVolley(giornata: Int) = String.format(Locale.ROOT, "v%03d", giornata)

    fun creaCanale(ctx: Context) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CANALE, "Report di giornata", NotificationManager.IMPORTANCE_DEFAULT)
                .apply { description = "Il riepilogo a fine giornata" }
        )
    }

    /** Report salvati, dal più recente. */
    fun elenco(ctx: Context): List<String> =
        cartella(ctx).listFiles().orEmpty()
            .mapNotNull { NOME_FILE.matchEntire(it.name)?.let { m -> m.groupValues[1] + m.groupValues[2] } }
            .sortedByDescending { leggi(ctx, it)?.optLong("creato") ?: 0L }

    /** "Calcio · giornata 7", "Volley · giornata 1". */
    fun etichetta(report: JSONObject): String {
        val sport = report.optString("sport").ifEmpty { "calcio" }.replaceFirstChar { it.titlecase(Locale.ITALIAN) }
        return "$sport · giornata ${report.optInt("giornata")}"
    }

    /** Fa scrivere (o riscrivere) all'assistente il commento di un report già salvato. */
    fun commenta(ctx: Context, chiave: String) {
        val r = leggi(ctx, chiave) ?: error("Report non trovato")
        r.remove("commento")
        r.remove("autore_commento")
        val testo = Assistente.commentoReport(ctx, r)
        r.put("commento", testo).put("autore_commento", Assistente.nome(ctx))
        file(ctx, chiave).writeText(r.toString())
    }

    /**
     * Commenta i report recenti rimasti senza commento, per esempio quelli fatti prima di collegare
     * l'assistente. Al massimo due per volta, per non far partire tante richieste insieme.
     */
    fun commentaMancanti(ctx: Context): List<String> {
        if (!Assistente.attivo(ctx)) return emptyList()
        val errori = mutableListOf<String>()
        elenco(ctx).take(6)
            .filter { leggi(ctx, it)?.optString("commento").isNullOrEmpty() }
            .take(2)
            .forEach { k ->
                try {
                    commenta(ctx, k)
                } catch (e: Exception) {
                    errori += "Commento ${leggi(ctx, k)?.let { etichetta(it) } ?: k}: ${e.message}"
                }
            }
        return errori
    }

    /** Toglie il commento dell'assistente da un report (dopo una segnalazione). */
    fun rimuoviCommento(ctx: Context, chiave: String) {
        val r = leggi(ctx, chiave) ?: return
        r.remove("commento")
        r.remove("autore_commento")
        file(ctx, chiave).writeText(r.toString())
    }

    fun leggi(ctx: Context, chiave: String): JSONObject? =
        runCatching { JSONObject(file(ctx, chiave).readText()) }.getOrNull()

    fun esiste(ctx: Context, chiave: String) = file(ctx, chiave).exists()

    /**
     * Salva un report. Se è nuovo, o se diventa definitivo, chiede il commento all'assistente;
     * se è nuovo manda la notifica. Restituisce gli errori.
     */
    fun pubblica(ctx: Context, chiave: String, report: JSONObject, definitivo: Boolean): List<String> {
        val errori = mutableListOf<String>()
        val nuovo = !file(ctx, chiave).exists()
        val vecchio = leggi(ctx, chiave)
        val precedente = vecchio?.optString("commento").orEmpty()
        var commento = precedente
        var autore = vecchio?.optString("autore_commento").orEmpty()
        if (Assistente.attivo(ctx) && (nuovo || definitivo || precedente.isEmpty())) {
            try {
                commento = Assistente.commentoReport(ctx, report)
                autore = Assistente.nome(ctx)
            } catch (e: Exception) {
                errori += "Commento di ${Assistente.nome(ctx)}: ${e.message}"
            }
        }
        if (commento.isNotEmpty()) report.put("commento", commento).put("autore_commento", autore)
        file(ctx, chiave).writeText(report.toString())
        if (nuovo) notifica(ctx, chiave, report)
        return errori
    }

    /** Si segna le giornate viste nel calendario, per farne il report quando finiscono. */
    fun registra(ctx: Context, cal: Calendario) {
        val attese = JSONObject(p(ctx).getString("attese", "{}") ?: "{}")
        val fatte = p(ctx).getStringSet("definitivi", emptySet()) ?: emptySet()
        listOfNotNull(cal.inCorso, cal.prossima).forEach { g ->
            if (g.numero.toString() !in fatte) {
                attese.put(g.numero.toString(), JSONObject().put("fine", g.fine).put("stagione", cal.stagione))
            }
        }
        p(ctx).edit().putString("attese", attese.toString()).apply()
    }

    /** Fa (o rifà) i report del calcio per le giornate finite. Restituisce gli errori. */
    fun elabora(ctx: Context, fonte: FonteCalcio): List<String> {
        val errori = mutableListOf<String>()
        val attese = JSONObject(p(ctx).getString("attese", "{}") ?: "{}")
        val fatte = (p(ctx).getStringSet("definitivi", emptySet()) ?: emptySet()).toMutableSet()
        val adesso = System.currentTimeMillis()

        attese.keys().asSequence().toList().forEach { chiave ->
            val info = attese.getJSONObject(chiave)
            val fine = info.getLong("fine")
            if (adesso < fine + ATTESA) return@forEach
            val giornata = chiave.toInt()
            try {
                val voti = fonte.voti(info.getString("stagione"), giornata)
                val definitivo = adesso > fine + DEFINITIVO
                val report = costruisci(ctx, giornata, voti, definitivo, fonte)
                errori += pubblica(ctx, chiaveCalcio(giornata), report, definitivo)
                if (definitivo) {
                    attese.remove(chiave)
                    fatte += chiave
                }
            } catch (e: Exception) {
                errori += "Report giornata $giornata: ${e.message}"
            }
        }
        p(ctx).edit().putString("attese", attese.toString()).putStringSet("definitivi", fatte).apply()
        return errori
    }

    private fun righe(punteggio: Punteggio) = JSONArray().apply {
        punteggio.righe.forEach { r ->
            val o = JSONObject().put("nome", r.giocatore.nome).put("panchina", r.daPanchina)
            r.fantavoto?.let { o.put("fv", it) }
            put(o)
        }
    }

    private fun costruisci(ctx: Context, giornata: Int, voti: Voti, definitivo: Boolean, fonte: FonteCalcio): JSONObject {
        val leghe = JSONArray()
        Leghe.tutte(ctx).filter { it.fonte == fonte.id && it.moduli.isNotEmpty() }.forEach { lega ->
            val o = JSONObject().put("id", lega.id).put("nome", lega.nome)

            // Senza foto prima della deadline (app installata a giornata iniziata) niente confronto.
            Archivio.consigliata(ctx, lega, giornata)?.let { s ->
                val pt = Formazioni.punteggio(lega, s, voti)
                val daAssistente = Assistente.consiglio(ctx, lega, giornata)?.schierata == s
                val c = JSONObject().put("modulo", pt.modulo).put("punti", pt.punti)
                    .put("cambi", pt.cambi).put("righe", righe(pt))
                    .put("titolari", JSONArray(s.titolari))
                    .put("fonte", if (daAssistente) "assistente" else "app")
                if (daAssistente) c.put("autore", Assistente.nome(ctx))
                o.put("consigliata", c)
            }
            Formazioni.migliore(lega, voti)?.let { pt ->
                o.put("migliore", JSONObject().put("modulo", pt.modulo).put("punti", pt.punti).put("righe", righe(pt)))
            }

            val rosa = JSONArray()
            lega.reparti.forEach { (reparto, giocatori) ->
                giocatori.forEach { g ->
                    val v = voti.perId[g.idFonte]
                    val x = JSONObject().put("reparto", reparto).put("nome", g.nome).put("id", g.chiave)
                        .put("stato", if (v == null) "ne" else if (v.voto == null) "sv" else "ok")
                        .put("bonus", v?.bonus.orEmpty())
                    v?.voto?.let { x.put("voto", it) }
                    v?.fantavoto?.let { x.put("fv", it) }
                    rosa.put(x)
                }
            }
            leghe.put(o.put("rosa", rosa))
        }
        return JSONObject().put("giornata", giornata).put("creato", System.currentTimeMillis())
            .put("definitivo", definitivo).put("leghe", leghe)
    }

    fun numero(x: Double): String =
        if (x == Math.floor(x)) x.toInt().toString() else String.format(Locale.ITALIAN, "%.1f", x)

    private fun notifica(ctx: Context, chiave: String, report: JSONObject) {
        val leghe = report.getJSONArray("leghe")
        val testo = (0 until leghe.length()).joinToString(" · ") { i ->
            val l = leghe.getJSONObject(i)
            val pt = l.optJSONObject("consigliata") ?: l.optJSONObject("migliore")
            "${l.getString("nome")} ${pt?.let { numero(it.getDouble("punti")) } ?: "-"}"
        }
        val codice = 1000 + (chiave.hashCode() and 0x00FFFFFF)
        val apri = PendingIntent.getActivity(
            ctx, codice,
            Intent(ctx, ReportActivity::class.java).putExtra(EXTRA_CHIAVE, chiave),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(ctx, CANALE)
            .setSmallIcon(R.drawable.ic_notifica)
            .setContentTitle("${etichetta(report)} finita: il tuo report")
            .setContentText(testo)
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    testo + report.optString("commento").takeIf { it.isNotEmpty() }?.let { "\n\n$it" }.orEmpty()
                )
            )
            .setAutoCancel(true)
            .setContentIntent(apri)
            .build()
        Promemoria.mostra(ctx, codice, n)
    }

    /** Versione testo, da condividere o incollare dove vuoi. */
    /** Chi ha scelto la formazione consigliata, se è stato un assistente (i report vecchi dicono "claude"). */
    fun autoreConsigliata(c: JSONObject): String? = when (c.optString("fonte")) {
        "assistente" -> c.optString("autore").ifEmpty { "l'assistente" }
        "claude" -> "Claude"
        else -> null
    }

    fun testo(report: JSONObject): String {
        val sb = StringBuilder("FantaPanchina · ${etichetta(report)}")
        if (!report.optBoolean("definitivo")) sb.append(" (dati provvisori)")
        report.optString("nota").takeIf { it.isNotEmpty() }?.let { sb.append('\n').append(it) }
        sb.append('\n')
        report.optString("commento").takeIf { it.isNotEmpty() }?.let {
            val autore = report.optString("autore_commento").ifEmpty { "Claude" }
            sb.append("\nCommento di $autore:\n").append(it).append('\n')
        }
        val leghe = report.getJSONArray("leghe")
        for (i in 0 until leghe.length()) {
            val l = leghe.getJSONObject(i)
            sb.append("\n").append(l.getString("nome").uppercase(Locale.ITALIAN)).append('\n')
            l.optJSONObject("consigliata")?.let {
                val chi = autoreConsigliata(it)?.let { a -> "Consigliata da $a" } ?: "Consigliata"
                sb.append("$chi (${it.getString("modulo")}): ${numero(it.getDouble("punti"))}\n")
            }
            l.optJSONObject("migliore")?.let {
                sb.append("Migliore possibile (${it.getString("modulo")}): ${numero(it.getDouble("punti"))}\n")
            }
            val rosa = l.getJSONArray("rosa")
            for (j in 0 until rosa.length()) {
                val g = rosa.getJSONObject(j)
                val voto = when {
                    g.getString("stato") == "ne" -> "n.e."
                    g.getString("stato") == "sv" -> "s.v."
                    g.has("fpt") -> "${numero(g.getDouble("fpt"))} FPT"
                    else -> "${numero(g.getDouble("voto"))} (fv ${numero(g.getDouble("fv"))})"
                }
                val bonus = g.optString("bonus").takeIf { it.isNotEmpty() }?.let { " · $it" } ?: ""
                sb.append("- ${g.getString("nome")}: $voto$bonus\n")
            }
        }
        return sb.toString()
    }
}
