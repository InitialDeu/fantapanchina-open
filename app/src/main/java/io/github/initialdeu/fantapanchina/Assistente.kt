package io.github.initialdeu.fantapanchina

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.errors.AnthropicIoException
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.errors.PermissionDeniedException
import com.anthropic.errors.RateLimitException
import com.anthropic.errors.UnauthorizedException
import com.anthropic.models.messages.JsonOutputFormat
import com.anthropic.models.messages.Message
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.StopReason
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** La formazione scelta dall'assistente, con il perché. */
data class Consiglio(
    val schierata: Schierata,
    val spiegazione: String,
    /** Motivo per ogni titolare, per [Giocatore.chiave]. */
    val motivi: Map<Int, String>,
    val giornata: Int,
    val creato: Long,
    /** Consigli di mercato (trasferimenti), se il gioco ne prevede. Vuoto = nessuno. */
    val mercato: String = ""
)

/** Chi risponde. */
enum class Provider(val etichetta: String, val modelloPredefinito: String, val serveUrl: Boolean) {
    /** Claude, tramite l'SDK ufficiale di Anthropic. */
    ANTHROPIC("Claude (Anthropic)", "claude-opus-5-5", false),
    /** OpenAI, API Chat Completions. Il modello lo sceglie l'utente. */
    OPENAI("OpenAI", "", false),
    /** Qualsiasi server compatibile con l'API OpenAI: Ollama, LM Studio, vLLM, un server tuo. */
    COMPATIBILE("Server compatibile OpenAI (fatto in casa)", "", true)
}

/**
 * L'assistente dentro l'app. Gli passa i dati che l'app ha già raccolto (rosa, probabili, voti,
 * notizie) e gli chiede la formazione e il commento al report. Il provider si sceglie in
 * Impostazioni assistente. Tutte le chiamate vanno fatte fuori dal thread principale.
 */
object Assistente {

    private const val PREFS = "assistente"
    private const val ALIAS = "fantapanchina_claude"
    private const val OPENAI_URL = "https://api.openai.com/v1"

    private fun p(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ---- Impostazioni ----

    fun provider(ctx: Context): Provider {
        migra(ctx)
        return runCatching { Provider.valueOf(p(ctx).getString("provider", null) ?: "") }.getOrDefault(Provider.ANTHROPIC)
    }

    fun modello(ctx: Context, prov: Provider = provider(ctx)): String =
        p(ctx).getString("modello_${prov.name}", null)?.ifBlank { null } ?: prov.modelloPredefinito

    /** Indirizzo base del server compatibile, es. "http://192.168.1.10:11434/v1". */
    fun url(ctx: Context): String = p(ctx).getString("url", "").orEmpty()

    fun salvaImpostazioni(ctx: Context, prov: Provider, modello: String, url: String, chiave: String?) {
        p(ctx).edit().putString("provider", prov.name).putString("modello_${prov.name}", modello.trim())
            .putString("url", url.trim().trimEnd('/')).apply()
        // Chiave vuota = lascia quella già salvata (il campo non la mostra mai).
        if (!chiave.isNullOrBlank()) salvaChiave(ctx, prov, chiave)
    }

    fun scollega(ctx: Context) {
        val prov = provider(ctx)
        p(ctx).edit().remove("api_${prov.name}").remove("provider").apply()
    }

    /** Nome da mostrare: "Claude", "OpenAI" o il modello del server compatibile. */
    fun nome(ctx: Context): String = when (val prov = provider(ctx)) {
        Provider.ANTHROPIC -> "Claude"
        Provider.OPENAI -> "OpenAI"
        Provider.COMPATIBILE -> modello(ctx, prov).ifBlank { "l'assistente" }
    }

    /** Pronto a rispondere: provider scelto e dati necessari presenti. */
    fun attivo(ctx: Context): Boolean {
        val prov = provider(ctx)
        if (p(ctx).getString("provider", null) == null) return false
        return when (prov) {
            Provider.ANTHROPIC -> chiave(ctx, prov) != null
            Provider.OPENAI -> chiave(ctx, prov) != null && modello(ctx, prov).isNotBlank()
            // Un server in casa può non volere chiave.
            Provider.COMPATIBILE -> url(ctx).isNotBlank() && modello(ctx, prov).isNotBlank()
        }
    }

    fun haChiave(ctx: Context, prov: Provider) = chiave(ctx, prov) != null

    /** Le versioni precedenti salvavano solo la chiave di Claude, in un altro file. */
    private fun migra(ctx: Context) {
        val vecchio = ctx.getSharedPreferences("claude", Context.MODE_PRIVATE)
        val v = vecchio.getString("api", null) ?: return
        p(ctx).edit().putString("api_${Provider.ANTHROPIC.name}", v)
            .putString("provider", p(ctx).getString("provider", null) ?: Provider.ANTHROPIC.name).apply()
        // I consigli già salvati restano dov'erano: li copio una volta.
        vecchio.all.forEach { (k, valore) ->
            if (k.startsWith("cons_") && valore is String && !p(ctx).contains(k)) p(ctx).edit().putString(k, valore).apply()
        }
        vecchio.edit().clear().apply()
    }

    // ---- Chiavi API: cifrate con una chiave del Keystore di Android, mai in chiaro su disco ----

    private fun chiaveKeystore(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build()
            )
        }.generateKey()
    }

    private fun salvaChiave(ctx: Context, prov: Provider, chiave: String) {
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, chiaveKeystore()) }
        val dati = c.iv + c.doFinal(chiave.trim().toByteArray())
        p(ctx).edit().putString("api_${prov.name}", Base64.encodeToString(dati, Base64.NO_WRAP)).apply()
    }

    private fun chiave(ctx: Context, prov: Provider): String? {
        migra(ctx)
        val s = p(ctx).getString("api_${prov.name}", null) ?: return null
        return runCatching {
            val dati = Base64.decode(s, Base64.NO_WRAP)
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, chiaveKeystore(), GCMParameterSpec(128, dati, 0, 12))
            String(c.doFinal(dati, 12, dati.size - 12))
        }.getOrNull()
    }

    // ---- Chiamata ----

    /** Manda la richiesta al provider scelto e restituisce il testo (JSON se c'è uno [schema]). */
    private fun chiedi(ctx: Context, sistema: String, utente: String, schema: Map<String, Any?>?): String =
        when (val prov = provider(ctx)) {
            Provider.ANTHROPIC -> chiediClaude(ctx, sistema, utente, schema)
            Provider.OPENAI -> chiediCompatibile(OPENAI_URL, chiave(ctx, prov), modello(ctx, prov), sistema, utente, schema)
            Provider.COMPATIBILE -> chiediCompatibile(url(ctx), chiave(ctx, prov), modello(ctx, prov), sistema, utente, schema)
        }

    /**
     * Claude con l'SDK ufficiale. Con i fallback lato server: se il modello declina, Anthropic
     * riprova da solo con un modello adatto invece di fermarsi.
     */
    private fun chiediClaude(ctx: Context, sistema: String, utente: String, schema: Map<String, Any?>?): String {
        val output = OutputConfig.builder().effort(OutputConfig.Effort.MEDIUM)
        if (schema != null) {
            output.format(
                JsonOutputFormat.builder().schema(
                    JsonOutputFormat.Schema.builder()
                        .putAllAdditionalProperties(schema.mapValues { JsonValue.from(it.value) })
                        .build()
                ).build()
            )
        }
        val params = MessageCreateParams.builder()
            .model(modello(ctx, Provider.ANTHROPIC))
            .maxTokens(16000L)
            .system(sistema)
            .outputConfig(output.build())
            .addUserMessage(utente)
            .putAdditionalHeader("anthropic-beta", "server-side-fallback-2026-07-01")
            .putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
            .build()
        val client = AnthropicOkHttpClient.builder()
            .apiKey(chiave(ctx, Provider.ANTHROPIC) ?: error("Chiave API di Claude non impostata"))
            .build()

        val risposta: Message = try {
            client.messages().create(params)
        } catch (e: UnauthorizedException) {
            error("Chiave API non valida")
        } catch (e: PermissionDeniedException) {
            error("La chiave non ha accesso al modello")
        } catch (e: RateLimitException) {
            error("Troppe richieste o credito esaurito, riprova più tardi")
        } catch (e: AnthropicServiceException) {
            error("Errore dell'API di Claude (${e.statusCode()})")
        } catch (e: AnthropicIoException) {
            error("Claude non raggiungibile: controlla la connessione")
        }

        when (risposta.stopReason().orElse(null)) {
            StopReason.REFUSAL -> error("Claude ha declinato la richiesta")
            StopReason.MAX_TOKENS -> error("Risposta di Claude troncata")
            else -> {}
        }
        return risposta.content().mapNotNull { it.text().orElse(null)?.text() }.joinToString("").trim()
    }

    /**
     * API Chat Completions, parlata da OpenAI e dai server fatti in casa. Con uno schema chiede
     * prima l'output strutturato (`json_schema`); se il server non lo supporta riprova chiedendo
     * il JSON nel testo. Il risultato viene comunque controllato da chi lo usa.
     */
    private fun chiediCompatibile(
        base: String, chiave: String?, modello: String, sistema: String, utente: String, schema: Map<String, Any?>?
    ): String {
        require(base.isNotBlank()) { "Indirizzo del server non impostato" }
        require(modello.isNotBlank()) { "Modello non impostato" }

        fun corpo(formato: JSONObject?, sistemaEffettivo: String): JSONObject {
            val o = JSONObject().put("model", modello).put(
                "messages", JSONArray()
                    .put(JSONObject().put("role", "system").put("content", sistemaEffettivo))
                    .put(JSONObject().put("role", "user").put("content", utente))
            )
            formato?.let { o.put("response_format", it) }
            return o
        }

        fun manda(o: JSONObject): Pair<Int, String> {
            val c = URL("${base.trimEnd('/')}/chat/completions").openConnection() as HttpURLConnection
            try {
                c.requestMethod = "POST"
                c.connectTimeout = 20_000
                // I modelli in locale possono essere lenti.
                c.readTimeout = 300_000
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                if (!chiave.isNullOrBlank()) c.setRequestProperty("Authorization", "Bearer $chiave")
                c.outputStream.use { it.write(o.toString().toByteArray()) }
                val codice = c.responseCode
                val testo = (if (codice in 200..299) c.inputStream else c.errorStream)
                    ?.bufferedReader()?.use { it.readText() }.orEmpty()
                return codice to testo
            } catch (e: IOException) {
                error("Server non raggiungibile: ${e.message}")
            } finally {
                c.disconnect()
            }
        }

        val schemaJson = schema?.let { JSONObject(it) }
        var (codice, testo) = manda(
            corpo(
                schemaJson?.let {
                    JSONObject().put("type", "json_schema").put(
                        "json_schema", JSONObject().put("name", "risposta").put("schema", it).put("strict", true)
                    )
                },
                sistema
            )
        )
        if (codice == 400 && schemaJson != null) {
            // Il server non conosce l'output strutturato: il formato lo chiedo a parole.
            val r = manda(corpo(null, "$sistema\n\nRispondi solo con un oggetto JSON valido che rispetti questo schema, senza altro testo:\n$schemaJson"))
            codice = r.first
            testo = r.second
        }
        when (codice) {
            in 200..299 -> {}
            401 -> error("Chiave API non valida")
            403 -> error("Accesso negato al modello")
            404 -> error("Modello o indirizzo non trovati")
            429 -> error("Troppe richieste o credito esaurito, riprova più tardi")
            else -> error("Errore del server ($codice)")
        }

        val scelta = JSONObject(testo).getJSONArray("choices").getJSONObject(0)
        val msg = scelta.getJSONObject("message")
        if (msg.optString("refusal").let { it.isNotBlank() && it != "null" }) error("Il modello ha declinato la richiesta")
        if (scelta.optString("finish_reason") == "length") error("Risposta troncata")
        val contenuto = msg.optString("content").trim()
        if (schemaJson == null) return contenuto
        // Alcuni modelli racchiudono il JSON tra ``` o ci mettono testo intorno.
        val inizio = contenuto.indexOf('{')
        val fine = contenuto.lastIndexOf('}')
        require(inizio >= 0 && fine > inizio) { "Il modello non ha risposto in JSON" }
        return contenuto.substring(inizio, fine + 1)
    }

    // ---- Formazione ----

    private fun sistemaFormazione(lega: Lega, conDati: Boolean, conExtra: Boolean): String {
        val calcio = lega.sport.equals("calcio", ignoreCase = true)
        val regole = if (calcio) {
            "un portiere più difensori, centrocampisti e attaccanti esattamente come il modulo scelto"
        } else {
            "per ogni reparto esattamente il numero di titolari indicato dal modulo scelto (i numeri " +
                "seguono l'ordine dei reparti; se sono uno in meno dei reparti il primo reparto ne ha uno)"
        }
        val criteri = if (conDati) {
            "Pesa soprattutto la probabilità di giocare titolare, poi la forma (ultimo voto), il valore " +
                "del giocatore (crediti) e l'avversario se lo conosci. Chi è infortunato o squalificato non " +
                "va titolare. Se le probabili sono di una giornata diversa da quella da schierare, dillo " +
                "nella spiegazione e ragiona con prudenza."
        } else if (conExtra) {
            "Nei dati_fonte trovi il rendimento delle ultime giornate (punteggi e statistiche) e le regole " +
                "del gioco: pesa la continuità di rendimento, quanto gioca davvero (set giocati) e gli avvisi " +
                "di infortunio. Se il gioco ha il capitano, indica nella spiegazione chi mettere capitano e " +
                "perché. Se i dati coprono poche giornate, dillo e ragiona con prudenza."
        } else {
            "Non hai probabili formazioni né voti: ragiona su valore dei giocatori (crediti), ruoli e " +
                "quello che sai di loro, e dichiara nella spiegazione che è una scelta senza dati aggiornati."
        }
        return """
            Sei l'assistente di fantasport dell'utente. Ti passo la sua rosa per una lega di ${lega.sport.ifBlank { "fantasport" }}
            e scegli la formazione da schierare per la prossima giornata.

            Regole: $regole, che deve essere tra quelli ammessi; ogni titolare va preso dal suo reparto.
            Poi tutti gli altri giocatori in panchina, nell'ordine in cui conviene farli entrare. Usa
            solo i nomi della rosa, scritti esattamente come te li passo.

            $criteri Tieni conto delle note della lega. Scrivi in italiano, motivi brevi (una riga) e
            una spiegazione di 2-4 frasi con i dubbi principali.

            Nel campo mercato: se le regole del gioco nei dati_fonte prevedono trasferimenti, proponi i
            cambi che convengono (al massimo quanti sono gratuiti), uno per riga nella forma
            "Fuori X, dentro Y: motivo", scegliendo tra i giocatori fuori rosa che ti passo e rispettando i
            ruoli. Proponi solo cambi che migliorano davvero la squadra: se non ne vale la pena scrivilo.
            Ricorda che i crediti dei giocatori fuori rosa vanno controllati nel gioco. Se il gioco non
            prevede trasferimenti, lascia il campo vuoto.
        """.trimIndent()
    }

    private val FORMATO = SimpleDateFormat("EEEE d MMMM 'alle' HH:mm", Locale.ITALIAN)

    /** [extra]: dati in più della fonte della lega (vedi [FonteNotizie.datiAssistente]). */
    fun formazione(
        ctx: Context, lega: Lega, giornata: Giornata?, pr: Probabili?, voti: Voti?, extra: String? = null
    ): Consiglio {
        require(lega.moduli.isNotEmpty()) { "La lega non ha moduli: aggiungili in Gestisci leghe" }
        val nomi = lega.giocatori
        val perNome = nomi.associateBy { it.nome }
        val conDati = pr != null

        val rosa = JSONArray()
        lega.reparti.forEach { (reparto, giocatori) ->
            giocatori.forEach { g ->
                val o = JSONObject().put("nome", g.nome).put("reparto", reparto).put("crediti", g.crediti)
                if (pr != null) {
                    pr.perId[g.idFonte]?.let {
                        o.put("squadra", it.squadra).put("previsione", it.esito.name.lowercase())
                            .put("percentuale_titolare", it.percentuale)
                        if (it.nota.isNotEmpty()) o.put("nota", it.nota)
                    } ?: o.put("previsione", "non presente nelle probabili")
                }
                if (voti != null) {
                    val v = voti.perId[g.idFonte]
                    o.put(
                        "ultima_giornata", when {
                            v == null -> "non entrato"
                            v.voto == null -> "senza voto"
                            else -> "voto ${v.voto}, fantavoto ${v.fantavoto}" +
                                v.bonus.takeIf { it.isNotEmpty() }?.let { " ($it)" }.orEmpty()
                        }
                    )
                }
                rosa.put(o)
            }
        }
        val dati = JSONObject()
            .put("lega", lega.nome)
            .put("sport", lega.sport)
            .put("note_lega", lega.note)
            .put("reparti_in_ordine", JSONArray(lega.reparti.keys.toList()))
            .put("moduli_ammessi", JSONArray(lega.moduli))
            .put("giornata_da_schierare", giornata?.numero ?: JSONObject.NULL)
            .put("deadline", giornata?.let { FORMATO.format(Date(it.inizio)) } ?: JSONObject.NULL)
            .put("probabili_della_giornata", pr?.giornata ?: JSONObject.NULL)
            .put("probabili_aggiornate", pr?.aggiornamento ?: JSONObject.NULL)
            .put("voti_della_giornata", voti?.giornata ?: JSONObject.NULL)
            .put("rosa", rosa)
        extra?.let { dati.put("dati_fonte", it) }

        val schema = mapOf(
            "type" to "object",
            "additionalProperties" to false,
            "required" to listOf("modulo", "titolari", "panchina", "spiegazione", "mercato"),
            "properties" to mapOf(
                "modulo" to mapOf("type" to "string", "enum" to lega.moduli),
                "titolari" to mapOf(
                    "type" to "array",
                    "items" to mapOf(
                        "type" to "object",
                        "additionalProperties" to false,
                        "required" to listOf("nome", "motivo"),
                        "properties" to mapOf(
                            "nome" to mapOf("type" to "string", "enum" to nomi.map { it.nome }),
                            "motivo" to mapOf("type" to "string")
                        )
                    )
                ),
                "panchina" to mapOf("type" to "array", "items" to mapOf("type" to "string", "enum" to nomi.map { it.nome })),
                "spiegazione" to mapOf("type" to "string"),
                "mercato" to mapOf("type" to "string")
            )
        )

        val chi = nome(ctx)
        val o = JSONObject(chiedi(ctx, sistemaFormazione(lega, conDati, extra != null), dati.toString(2), schema))
        val modulo = o.getString("modulo")
        val tit = o.getJSONArray("titolari").let { a -> (0 until a.length()).map { a.getJSONObject(it) } }
        val titolari = tit.mapNotNull { perNome[it.getString("nome")] }.distinct()

        // Controllo: modulo ammesso e giocatori giusti per reparto, altrimenti non la usiamo.
        require(modulo in lega.moduli) { "$chi ha scelto un modulo non ammesso ($modulo)" }
        val conteggi = Formazioni.conteggi(lega, modulo) ?: error("Il modulo $modulo non torna con i reparti della lega")
        lega.reparti.values.toList().zip(conteggi).forEach { (gs, n) ->
            require(titolari.count { it in gs } == n) { "La formazione di $chi non rispetta il $modulo" }
        }

        val panchinaScelta = o.getJSONArray("panchina").let { a -> (0 until a.length()).map { a.getString(it) } }
            .mapNotNull { perNome[it] }.filter { it !in titolari }.distinct()
        // Chi è stato dimenticato va in fondo, nell'ordine della rosa.
        val panchina = panchinaScelta + nomi.filter { it !in titolari && it !in panchinaScelta }

        return Consiglio(
            Schierata(modulo, titolari.map { it.chiave }, panchina.map { it.chiave }),
            o.getString("spiegazione"),
            tit.mapNotNull { t -> perNome[t.getString("nome")]?.let { it.chiave to t.getString("motivo") } }.toMap(),
            giornata?.numero ?: 0,
            System.currentTimeMillis(),
            o.optString("mercato").trim()
        )
    }

    // ---- Archivio dei consigli ----

    fun consiglio(ctx: Context, lega: Lega, giornata: Int): Consiglio? {
        migra(ctx)
        val s = p(ctx).getString("cons_${lega.id}_$giornata", null) ?: return null
        return runCatching {
            val o = JSONObject(s)
            fun ids(k: String) = o.getJSONArray(k).let { a -> (0 until a.length()).map { a.getInt(it) } }
            val m = o.getJSONObject("motivi")
            Consiglio(
                Schierata(o.getString("modulo"), ids("t"), ids("p")),
                o.getString("spiegazione"),
                m.keys().asSequence().associate { it.toInt() to m.getString(it) },
                o.getInt("giornata"), o.getLong("creato"), o.optString("mercato")
            )
        }.getOrNull()
    }

    /** Toglie un consiglio (dopo una segnalazione): torna la formazione calcolata dall'app. */
    fun eliminaConsiglio(ctx: Context, lega: Lega, giornata: Int) {
        p(ctx).edit().remove("cons_${lega.id}_$giornata").apply()
    }

    fun salvaConsiglio(ctx: Context, lega: Lega, c: Consiglio) {
        val m = JSONObject().apply { c.motivi.forEach { (id, t) -> put(id.toString(), t) } }
        val o = JSONObject().put("modulo", c.schierata.modulo)
            .put("t", JSONArray(c.schierata.titolari)).put("p", JSONArray(c.schierata.panchina))
            .put("spiegazione", c.spiegazione).put("motivi", m)
            .put("giornata", c.giornata).put("creato", c.creato).put("mercato", c.mercato)
        p(ctx).edit().putString("cons_${lega.id}_${c.giornata}", o.toString()).apply()
    }

    /**
     * Va chiesto in automatico? Solo per le leghe con probabili, e al massimo due volte per
     * giornata: appena escono le probabili della giornata da schierare, e di nuovo nelle ultime 8
     * ore prima della deadline per tenere conto delle ultime notizie.
     */
    fun daChiedere(ctx: Context, lega: Lega, giornata: Giornata, pr: Probabili?): Boolean {
        if (!attivo(ctx) || pr == null || pr.giornata != giornata.numero) return false
        val manca = giornata.inizio - System.currentTimeMillis()
        if (manca <= 0) return false
        val c = consiglio(ctx, lega, giornata.numero) ?: return true
        val ultime8 = giornata.inizio - 8L * 60 * 60 * 1000
        return manca < 8L * 60 * 60 * 1000 && c.creato < ultime8
    }

    // ---- Report ----

    private val SISTEMA_REPORT = """
        Sei l'assistente di fantasport dell'utente. Ti passo il report di fine giornata delle sue leghe:
        per ognuna la formazione consigliata (con i punti fatti), la migliore possibile e i voti
        di tutta la rosa (fv = fantavoto, n.e. = non entrato, s.v. = senza voto). Scrivi un commento
        breve in italiano, in testo semplice senza markdown: per ogni lega 2-4 frasi su com'è andata,
        chi ha deciso la giornata, cosa si poteva fare meglio e a chi fare attenzione la prossima
        volta. Niente premesse, vai dritto al punto.
    """.trimIndent()

    fun commentoReport(ctx: Context, report: JSONObject): String =
        chiedi(ctx, SISTEMA_REPORT, Report.testo(report), null)
}
