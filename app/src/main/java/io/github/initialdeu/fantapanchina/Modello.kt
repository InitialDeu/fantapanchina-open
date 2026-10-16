package io.github.initialdeu.fantapanchina

/**
 * Un giocatore della rosa. [idFonte] è l'id del giocatore sulla fonte dati della lega (per
 * esempio l'ultimo numero nell'indirizzo della sua scheda sul sito della fonte):
 * serve ad abbinarlo a probabili e voti senza confondere omonimi. 0 = nessun abbinamento.
 */
data class Giocatore(val nome: String, val crediti: Int, val idFonte: Int = 0) {
    /** Il nome senza note tra parentesi, es. "Rossi (C)" → "Rossi". Serve a cercarlo nelle notizie. */
    val cognome: String get() = nome.substringBefore(" (").trim()

    /**
     * Identità del giocatore dentro la lega (formazioni, consigli, report): l'id della fonte se
     * c'è, altrimenti un numero negativo ricavato dal nome, così le leghe senza fonte funzionano.
     */
    val chiave: Int get() = if (idFonte != 0) idFonte else -(nome.lowercase().hashCode() and 0x3fffffff) - 1
}

data class Lega(
    val id: String,
    val nome: String,
    /** Sport in chiaro ("Calcio", "Volley", "Basket"...): finisce anche nelle richieste all'assistente. */
    val sport: String,
    val note: String,
    /** Id del campionato su TheSportsDB, per calendario e deadline. Null = niente calendario. */
    val calendario: String?,
    /** Id della fonte dati extra (vedi [Fonti]), es. una fonte di probabili formazioni. Null = nessuna. */
    val fonte: String?,
    /**
     * Moduli ammessi: titolari per reparto separati da "-", nell'ordine dei reparti. Se il modulo
     * ha un numero in meno dei reparti il primo reparto conta 1 (il portiere del calcio: "4-4-2").
     * Vuoto = l'app non costruisce formazioni né report per questa lega.
     */
    val moduli: List<String>,
    /** Chiave = nome del reparto, nell'ordine in cui va mostrato e in cui contano i moduli. */
    val reparti: LinkedHashMap<String, List<Giocatore>>
) {
    val giocatori: List<Giocatore> get() = reparti.values.flatten()
}

/** Moduli standard del fantacalcio classico. */
val MODULI_CALCIO = listOf("3-4-3", "3-5-2", "4-3-3", "4-4-2", "4-5-1", "5-3-2", "5-4-1")
