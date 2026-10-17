package io.github.initialdeu.fantapanchina

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/** Una giornata di campionato: primo fischio d'inizio e calcio d'inizio dell'ultima partita. */
data class Giornata(val numero: Int, val inizio: Long, val fine: Long)

data class Calendario(
    /** La prima giornata che deve ancora cominciare: è quella da schierare. */
    val prossima: Giornata?,
    /** La giornata già cominciata e non ancora finita, se c'è. */
    val inCorso: Giornata?,
    val aggiornato: Long,
    /** Come la scrive TheSportsDB, es. "2026-2027". */
    val stagione: String = ""
)

/** Voto di una giornata. [voto] e [fantavoto] null = senza voto (entrato ma non giudicabile). */
data class Voto(val voto: Double?, val fantavoto: Double?, val bonus: String)

/** Voti di una giornata, dalla fonte dati della lega. Chi non c'è non è entrato in campo. */
data class Voti(val giornata: Int, val perId: Map<Int, Voto>)

enum class Esito { TITOLARE, PANCHINA, INFORTUNATO, SQUALIFICATO }

data class Previsione(val esito: Esito, val percentuale: Int, val squadra: String, val nota: String)

data class Probabili(
    val giornata: Int,
    /** Come lo scrive la fonte, es. "10/10/2026 - 14:12". */
    val aggiornamento: String,
    val perId: Map<Int, Previsione>
)

/** Download e calendario da TheSportsDB. Va chiamato fuori dal thread principale. */
object Rete {

    /**
     * Chiavi gratuite di TheSportsDB, in ordine. A ottobre 2026 la "123" è stata sospesa per un
     * periodo ("Free API temporarily disabled"): se una non risponde si passa alla successiva.
     * La "3" dà al massimo 5 partite per giornata, che bastano per il primo fischio.
     */
    private val CHIAVI_SPORTSDB = listOf(BuildConfig.THESPORTSDB_KEY, "123", "3").filter { it.isNotBlank() }.distinct()
    private const val AGENTE =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126 Mobile Safari/537.36"

    fun scarica(indirizzo: String): String {
        val c = URL(indirizzo).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000
        c.readTimeout = 20_000
        c.setRequestProperty("User-Agent", AGENTE)
        try {
            if (c.responseCode !in 200..299) error("HTTP ${c.responseCode} da $indirizzo")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    // ---- Calendario (TheSportsDB) ----

    private fun sportsDb(percorso: String): String {
        var errore: Exception? = null
        for (chiave in CHIAVI_SPORTSDB) {
            try {
                return scarica("https://www.thesportsdb.com/api/v1/json/$chiave/$percorso")
            } catch (e: Exception) {
                errore = e
            }
        }
        throw errore ?: IllegalStateException("TheSportsDB non raggiungibile")
    }

    /** TheSportsDB dà gli orari in UTC, senza fuso nel testo. */
    private fun istante(e: JSONObject): Long? {
        val ts = e.optString("strTimestamp").takeIf { it.length >= 19 && it != "null" }
            ?: run {
                val d = e.optString("dateEvent")
                val t = e.optString("strTime")
                if (d.length == 10 && t.length >= 8) "${d}T${t.take(8)}" else return null
            }
        val f = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.ROOT).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        return runCatching { f.parse(ts.take(19))?.time }.getOrNull()
    }

    private fun giornata(campionato: String, numero: Int, stagione: String): Giornata? {
        val eventi = JSONObject(
            sportsDb("eventsround.php?id=$campionato&r=$numero&s=$stagione")
        ).optJSONArray("events") ?: return null
        val orari = (0 until eventi.length()).mapNotNull { istante(eventi.getJSONObject(it)) }
        val inizio = orari.minOrNull() ?: return null
        // Un recupero giocato settimane dopo non deve tenere aperta la giornata.
        val fine = orari.filter { it - inizio < 7L * 24 * 60 * 60 * 1000 }.max()
        return Giornata(numero, inizio, fine)
    }

    /**
     * Campionati di TheSportsDB per paese e sport, in inglese come li vuole il sito (es. "Italy",
     * "Basketball"). Restituisce coppie id → nome; con la chiave gratuita al massimo 5.
     */
    fun campionati(paese: String, sport: String): List<Pair<String, String>> {
        val q = "c=" + java.net.URLEncoder.encode(paese.trim(), "UTF-8") + "&s=" + java.net.URLEncoder.encode(sport.trim(), "UTF-8")
        val o = JSONObject(sportsDb("search_all_leagues.php?$q"))
        val arr = o.optJSONArray("countries") ?: o.optJSONArray("leagues") ?: return emptyList()
        return (0 until arr.length()).map { arr.getJSONObject(it) }
            .map { it.optString("idLeague") to it.optString("strLeague") }
            .filter { it.first.isNotBlank() }
    }

    /** [campionato] = id del campionato su TheSportsDB (es. "4332" per la Serie A). */
    fun calendario(campionato: String): Calendario {
        val adesso = System.currentTimeMillis()
        // La versione gratuita dà solo la prossima partita: da lì ricavo giornata e stagione.
        val prossimaPartita = JSONObject(sportsDb("eventsnextleague.php?id=$campionato"))
            .optJSONArray("events")?.optJSONObject(0)
            ?: return Calendario(null, null, adesso)
        val numero = prossimaPartita.optString("intRound").toIntOrNull()
            ?: return Calendario(null, null, adesso)
        val stagione = prossimaPartita.optString("strSeason")

        val questa = giornata(campionato, numero, stagione) ?: return Calendario(null, null, adesso, stagione)
        if (questa.inizio > adesso) return Calendario(questa, null, adesso, stagione)

        // Giornata già cominciata: la prossima da schierare è quella dopo.
        return Calendario(giornata(campionato, numero + 1, stagione), questa, adesso, stagione)
    }
}

/** Ultimi dati scaricati, salvati sul telefono così l'app funziona anche offline. */
object Archivio {
    private const val FILE = "archivio"

    private fun p(ctx: Context) = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    private fun json(g: Giornata?) =
        g?.let { JSONObject().put("n", it.numero).put("t", it.inizio).put("f", it.fine) }

    private fun giornata(o: JSONObject?) =
        o?.let { Giornata(it.getInt("n"), it.getLong("t"), it.optLong("f", it.getLong("t"))) }

    fun calendario(ctx: Context, campionato: String?): Calendario? {
        if (campionato == null) return null
        val s = p(ctx).getString("cal_$campionato", null) ?: return null
        return runCatching {
            val o = JSONObject(s)
            Calendario(
                giornata(o.optJSONObject("prossima")), giornata(o.optJSONObject("inCorso")),
                o.getLong("agg"), o.optString("stagione")
            )
        }.getOrNull()
    }

    private fun salvaCalendario(ctx: Context, campionato: String, c: Calendario) {
        val o = JSONObject().put("agg", c.aggiornato).put("stagione", c.stagione)
        json(c.prossima)?.let { o.put("prossima", it) }
        json(c.inCorso)?.let { o.put("inCorso", it) }
        p(ctx).edit().putString("cal_$campionato", o.toString()).apply()
    }

    fun probabili(ctx: Context): Probabili? {
        val s = p(ctx).getString("probabili", null) ?: return null
        return runCatching {
            val o = JSONObject(s)
            val arr = o.getJSONArray("g")
            val mappa = HashMap<Int, Previsione>()
            for (i in 0 until arr.length()) {
                val x = arr.getJSONObject(i)
                mappa[x.getInt("id")] = Previsione(
                    Esito.valueOf(x.getString("e")), x.getInt("p"), x.getString("s"), x.getString("n")
                )
            }
            Probabili(o.getInt("giornata"), o.getString("agg"), mappa)
        }.getOrNull()
    }

    private fun salvaProbabili(ctx: Context, pr: Probabili) {
        val arr = JSONArray()
        pr.perId.forEach { (id, v) ->
            arr.put(
                JSONObject().put("id", id).put("e", v.esito.name).put("p", v.percentuale)
                    .put("s", v.squadra).put("n", v.nota)
            )
        }
        val o = JSONObject().put("giornata", pr.giornata).put("agg", pr.aggiornamento).put("g", arr)
        p(ctx).edit().putString("probabili", o.toString()).apply()
    }

    /** Voti dell'ultima giornata conclusa, per mostrare la prestazione precedente. */
    fun votiPrecedenti(ctx: Context): Voti? {
        val s = p(ctx).getString("voti_prec", null) ?: return null
        return runCatching {
            val o = JSONObject(s)
            val arr = o.getJSONArray("v")
            val mappa = HashMap<Int, Voto>()
            for (i in 0 until arr.length()) {
                val x = arr.getJSONObject(i)
                mappa[x.getInt("id")] = Voto(
                    x.optDouble("voto").takeUnless { it.isNaN() },
                    x.optDouble("fv").takeUnless { it.isNaN() },
                    x.optString("b")
                )
            }
            Voti(o.getInt("giornata"), mappa)
        }.getOrNull()
    }

    private fun salvaVotiPrecedenti(ctx: Context, v: Voti) {
        val arr = JSONArray()
        v.perId.forEach { (id, x) ->
            val o = JSONObject().put("id", id).put("b", x.bonus)
            x.voto?.let { o.put("voto", it) }
            x.fantavoto?.let { o.put("fv", it) }
            arr.put(o)
        }
        p(ctx).edit()
            .putString("voti_prec", JSONObject().put("giornata", v.giornata).put("v", arr).toString())
            .apply()
    }

    /** La formazione che l'app consigliava per quella giornata, fotografata prima della deadline. */
    fun consigliata(ctx: Context, lega: Lega, giornata: Int): Schierata? {
        val s = p(ctx).getString("cons_${lega.id}_$giornata", null) ?: return null
        return runCatching {
            val o = JSONObject(s)
            fun ids(k: String) = o.getJSONArray(k).let { a -> (0 until a.length()).map { a.getInt(it) } }
            Schierata(o.getString("modulo"), ids("t"), ids("p"))
        }.getOrNull()
    }

    private fun salvaConsigliata(ctx: Context, lega: Lega, giornata: Int, s: Schierata) {
        val o = JSONObject().put("modulo", s.modulo)
            .put("t", JSONArray(s.titolari)).put("p", JSONArray(s.panchina))
        p(ctx).edit().putString("cons_${lega.id}_$giornata", o.toString()).apply()
    }

    fun ultimoAggiornamento(ctx: Context): Long = p(ctx).getLong("ultimo", 0L)

    /**
     * Scarica tutto quello che serve e lo salva. Se un pezzo fallisce gli altri vanno avanti
     * e resta l'ultima versione buona. Restituisce gli errori, vuoto se è andato tutto bene.
     */
    fun aggiorna(ctx: Context): List<String> {
        val errori = mutableListOf<String>()
        val leghe = Leghe.tutte(ctx)
        val adesso = System.currentTimeMillis()

        leghe.mapNotNull { it.calendario }.distinct().forEach { id ->
            try {
                salvaCalendario(ctx, id, Rete.calendario(id))
            } catch (e: Exception) {
                errori += "Calendario ($id): ${e.message}"
            }
        }

        // Probabili e voti: una fonte di calcio alla volta, quella della prima lega che ne usa una.
        val calcio = leghe.filter { Fonti.calcio(it.fonte) != null }
        val fonte = calcio.firstOrNull()?.let { Fonti.calcio(it.fonte) }
        if (fonte != null) {
            try {
                salvaProbabili(ctx, fonte.probabili())
            } catch (e: Exception) {
                errori += "Probabili (${fonte.nome}): ${e.message}"
            }
            val cal = calendario(ctx, calcio.first().calendario)
            val pr = probabili(ctx)

            // Finché la deadline non è passata, la foto della formazione consigliata si aggiorna.
            val prossima = cal?.prossima
            if (prossima != null && pr != null && pr.giornata == prossima.numero && adesso < prossima.inizio) {
                calcio.filter { it.moduli.isNotEmpty() }.forEach { lega ->
                    // L'assistente, se collegato, sceglie quando escono le probabili e ricontrolla vicino alla deadline.
                    if (Assistente.daChiedere(ctx, lega, prossima, pr)) {
                        try {
                            Assistente.salvaConsiglio(
                                ctx, lega, Assistente.formazione(ctx, lega, prossima, pr, votiPrecedenti(ctx))
                            )
                        } catch (e: Exception) {
                            errori += "${Assistente.nome(ctx)} (${lega.nome}): ${e.message}"
                        }
                    }
                    val scelta = Assistente.consiglio(ctx, lega, prossima.numero)?.schierata
                        ?: Formazioni.consigliata(lega, pr)
                    scelta?.let { salvaConsigliata(ctx, lega, prossima.numero, it) }
                }
            }

            if (cal != null && cal.stagione.isNotEmpty()) {
                val ultima = ((cal.inCorso ?: cal.prossima)?.numero ?: 1) - 1
                if (ultima >= 1) {
                    try {
                        salvaVotiPrecedenti(ctx, fonte.voti(cal.stagione, ultima))
                    } catch (e: Exception) {
                        errori += "Voti giornata $ultima: ${e.message}"
                    }
                }
                Report.registra(ctx, cal)
                errori += Report.elabora(ctx, fonte)
            }
        }

        Fonti.notizie.forEach { n ->
            val sue = leghe.filter { it.fonte == n.id }
            if (sue.isNotEmpty()) {
                try {
                    n.aggiorna(ctx, sue)
                } catch (e: Exception) {
                    errori += "Notizie (${n.nome}): ${e.message}"
                }
            }
        }

        if (errori.isEmpty()) p(ctx).edit().putLong("ultimo", System.currentTimeMillis()).apply()
        return errori
    }
}
