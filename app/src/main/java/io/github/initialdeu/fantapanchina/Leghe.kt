package io.github.initialdeu.fantapanchina

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.Normalizer
import java.util.Locale

/**
 * Le leghe dell'utente, salvate sul telefono in un file JSON. Al primo avvio si parte da
 * [Predefinite.LEGHE]; da lì in poi si aggiungono, modificano e importano dall'app.
 */
object Leghe {

    private const val FILE = "leghe.json"
    const val FORMATO = "fantapanchina-lega"

    @Volatile
    private var cache: List<Lega>? = null

    private fun file(ctx: Context) = File(ctx.filesDir, FILE)

    fun tutte(ctx: Context): List<Lega> {
        cache?.let { return it }
        synchronized(this) {
            cache?.let { return it }
            val f = file(ctx)
            val leghe = if (f.exists()) {
                runCatching { daJson(JSONObject(f.readText())) }.getOrElse { Predefinite.LEGHE }
            } else {
                Predefinite.LEGHE.also { salvaTutte(ctx, it) }
            }
            cache = leghe
            return leghe
        }
    }

    fun perId(ctx: Context, id: String): Lega? = tutte(ctx).firstOrNull { it.id == id }

    private fun salvaTutte(ctx: Context, leghe: List<Lega>) {
        val o = JSONObject().put("formato", FORMATO).put("versione", 1)
            .put("leghe", JSONArray().apply { leghe.forEach { put(json(it)) } })
        // Scrittura atomica: prima su un file temporaneo, poi lo rinomina.
        val tmp = File(ctx.filesDir, "$FILE.tmp")
        tmp.writeText(o.toString(2))
        tmp.renameTo(file(ctx))
        cache = leghe
    }

    /** Aggiunge la lega o, se esiste già con lo stesso id, la sostituisce. */
    fun salva(ctx: Context, lega: Lega) {
        val attuali = tutte(ctx)
        val nuove = if (attuali.any { it.id == lega.id }) attuali.map { if (it.id == lega.id) lega else it }
        else attuali + lega
        salvaTutte(ctx, nuove)
    }

    fun elimina(ctx: Context, id: String) {
        Promemoria.annulla(ctx, id)
        salvaTutte(ctx, tutte(ctx).filter { it.id != id })
    }

    /** Un id nuovo, leggibile e non ancora usato, ricavato dal nome. */
    fun nuovoId(ctx: Context, nome: String): String {
        val base = Normalizer.normalize(nome, Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
            .ifEmpty { "lega" }
            .take(30)
        val usati = tutte(ctx).map { it.id }.toSet()
        if (base !in usati) return base
        var n = 2
        while ("$base-$n" in usati) n++
        return "$base-$n"
    }

    // ---- JSON: lo stesso formato serve per il salvataggio e per import/export ----

    fun json(l: Lega): JSONObject {
        val reparti = JSONArray()
        l.reparti.forEach { (nome, giocatori) ->
            reparti.put(
                JSONObject().put("nome", nome).put("giocatori", JSONArray().apply {
                    giocatori.forEach { g ->
                        val o = JSONObject().put("nome", g.nome).put("crediti", g.crediti)
                        if (g.idFonte != 0) o.put("id", g.idFonte)
                        put(o)
                    }
                })
            )
        }
        val o = JSONObject().put("id", l.id).put("nome", l.nome).put("sport", l.sport).put("note", l.note)
            .put("moduli", JSONArray(l.moduli)).put("reparti", reparti)
        l.calendario?.let { o.put("calendario", it) }
        l.fonte?.let { o.put("fonte", it) }
        return o
    }

    private fun lega(o: JSONObject): Lega {
        val reparti = LinkedHashMap<String, List<Giocatore>>()
        val arr = o.getJSONArray("reparti")
        for (i in 0 until arr.length()) {
            val r = arr.getJSONObject(i)
            val gs = r.getJSONArray("giocatori")
            reparti[r.getString("nome")] = (0 until gs.length()).map { j ->
                val g = gs.getJSONObject(j)
                Giocatore(g.getString("nome").trim(), g.optInt("crediti", 0), g.optInt("id", 0))
            }
        }
        val moduli = o.optJSONArray("moduli")?.let { a -> (0 until a.length()).map { a.getString(it).trim() } }
            ?: emptyList()
        return Lega(
            id = o.optString("id").ifBlank { "" },
            nome = o.getString("nome").trim(),
            sport = o.optString("sport", "Altro").ifBlank { "Altro" },
            note = o.optString("note"),
            calendario = o.optString("calendario").ifBlank { null },
            fonte = o.optString("fonte").ifBlank { null },
            moduli = moduli,
            reparti = reparti
        )
    }

    /** Accetta il file completo ({"leghe": [...]}), una sola lega, o un array di leghe. */
    private fun daJson(o: JSONObject): List<Lega> = when {
        o.has("leghe") -> o.getJSONArray("leghe").let { a -> (0 until a.length()).map { lega(a.getJSONObject(it)) } }
        o.has("lega") -> listOf(lega(o.getJSONObject("lega")))
        else -> listOf(lega(o))
    }

    /** Il contenuto da scrivere in un file di esportazione per una lega. */
    fun esporta(l: Lega): String =
        JSONObject().put("formato", FORMATO).put("versione", 1).put("lega", json(l)).toString(2)

    /**
     * Legge un file importato. JSON (una o più leghe, anche esportate da un altro telefono) o CSV
     * con intestazione `reparto,nome,crediti,id` (id facoltativo); per il CSV nome e sport della
     * lega arrivano da [nomeCsv] e [sportCsv]. Gli id vengono rifatti per non sovrascrivere leghe
     * esistenti. Restituisce le leghe lette, da salvare con [salva].
     */
    fun importa(ctx: Context, testo: String, nomeCsv: String, sportCsv: String): List<Lega> {
        val pulito = testo.removePrefix("\uFEFF").trim()
        val lette = if (pulito.startsWith("{") || pulito.startsWith("[")) {
            if (pulito.startsWith("[")) JSONArray(pulito).let { a -> (0 until a.length()).map { lega(a.getJSONObject(it)) } }
            else daJson(JSONObject(pulito))
        } else {
            listOf(daCsv(pulito, nomeCsv, sportCsv))
        }
        require(lette.isNotEmpty()) { "Nessuna lega nel file" }
        val usati = tutte(ctx).map { it.id }.toMutableSet()
        return lette.map { l ->
            require(l.nome.isNotBlank()) { "Lega senza nome nel file" }
            require(l.reparti.isNotEmpty()) { "La lega ${l.nome} non ha giocatori" }
            var id = nuovoId(ctx, l.nome)
            var n = 2
            while (id in usati) id = nuovoId(ctx, "${l.nome} ${n++}")
            usati += id
            l.copy(id = id)
        }
    }

    private fun daCsv(testo: String, nome: String, sport: String): Lega {
        val righe = testo.lines().map { it.trim() }.filter { it.isNotEmpty() }
        require(righe.size >= 2) { "CSV vuoto" }
        val sep = if (righe[0].count { it == ';' } > righe[0].count { it == ',' }) ';' else ','
        val intest = righe[0].split(sep).map { it.trim().trim('"').lowercase(Locale.ROOT) }
        val iRep = intest.indexOf("reparto").also { require(it >= 0) { "Al CSV manca la colonna 'reparto'" } }
        val iNome = intest.indexOf("nome").also { require(it >= 0) { "Al CSV manca la colonna 'nome'" } }
        val iCred = intest.indexOf("crediti")
        val iId = intest.indexOf("id")
        val reparti = LinkedHashMap<String, MutableList<Giocatore>>()
        righe.drop(1).forEach { riga ->
            val c = riga.split(sep).map { it.trim().trim('"') }
            val rep = c.getOrNull(iRep).orEmpty().ifEmpty { return@forEach }
            val n = c.getOrNull(iNome).orEmpty().ifEmpty { return@forEach }
            reparti.getOrPut(rep) { mutableListOf() } += Giocatore(
                n, c.getOrNull(iCred)?.toIntOrNull() ?: 0, c.getOrNull(iId)?.toIntOrNull() ?: 0
            )
        }
        require(reparti.isNotEmpty()) { "Nessun giocatore nel CSV" }
        return Lega("", nome.ifBlank { "Lega importata" }, sport.ifBlank { "Altro" }, "", null, null, emptyList(),
            LinkedHashMap(reparti.mapValues { it.value.toList() }))
    }
}
