package io.github.initialdeu.fantapanchina

import android.content.Context

/**
 * Fonte di probabili formazioni e voti per una lega di calcio. Un'implementazione legge un sito
 * o un'API e restituisce i dati per id giocatore ([Giocatore.idFonte]).
 */
interface FonteCalcio {
    val id: String
    val nome: String
    fun probabili(): Probabili
    /** [stagione] come la scrive TheSportsDB, es. "2026-2027". */
    fun voti(stagione: String, giornata: Int): Voti
}

/** Un possibile infortunio di un giocatore, trovato in una notizia. */
data class Allarme(
    val giocatore: String,
    val titolo: String,
    val link: String,
    /** La frase della notizia che ne parla, per giudicare al volo. */
    val frase: String,
    val data: Long,
    val chiave: String
)

/** Fonte di notizie: avvisa quando un giocatore della rosa compare in una notizia di infortunio. */
interface FonteNotizie {
    val id: String
    val nome: String
    fun creaCanale(ctx: Context)
    /** Cerca notizie nuove per i giocatori di [leghe], le salva e manda le notifiche. */
    fun aggiorna(ctx: Context, leghe: List<Lega>)
    /** L'avviso più recente ancora valido per quel giocatore, se c'è. */
    fun recente(ctx: Context, giocatore: Giocatore): Allarme?
    /** L'utente dice che non è un infortunio: l'avviso non va più mostrato. */
    fun ignora(ctx: Context, allarme: Allarme)
}

/** Un tipo di lega pronto da scegliere quando se ne crea una: precompila i campi. */
data class TipoLega(
    val nome: String,
    val sport: String,
    val calendario: String?,
    val fonte: String?,
    val moduli: List<String>,
    val reparti: List<String>
)

/** Tipi sempre disponibili, che non dipendono da fonti dati particolari. */
val TIPI_BASE = listOf(
    TipoLega(
        "Calcio (calendario Serie A, senza probabili)", "Calcio", "4332", null, MODULI_CALCIO,
        listOf("Portieri", "Difensori", "Centrocampisti", "Attaccanti")
    ),
    TipoLega("Altro sport", "", null, null, emptyList(), listOf("Giocatori"))
)
