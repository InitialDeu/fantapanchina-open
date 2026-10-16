package io.github.initialdeu.fantapanchina

/**
 * Registro delle fonti dati disponibili. Questa versione non ne include: restano calendario
 * (TheSportsDB) e assistente. Per aggiungerne una implementa [FonteCalcio] o [FonteNotizie]
 * (vedi FontiBase.kt) e mettila nelle liste qui sotto, con un [TipoLega] che la usi.
 */
object Fonti {

    val calcio: List<FonteCalcio> = emptyList()
    val notizie: List<FonteNotizie> = emptyList()

    fun calcio(id: String?): FonteCalcio? = calcio.firstOrNull { it.id == id }
    fun notizie(id: String?): FonteNotizie? = notizie.firstOrNull { it.id == id }

    /** Per i menu: id e nome di tutte le fonti. */
    val tutte: List<Pair<String, String>> get() = calcio.map { it.id to it.nome } + notizie.map { it.id to it.nome }

    val tipi: List<TipoLega> = TIPI_BASE
}
