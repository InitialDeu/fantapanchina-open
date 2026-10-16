package io.github.initialdeu.fantapanchina

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/** Preferenze locali: per ora solo "ho schierato", legato alla giornata. */
object Prefs {
    private const val FILE = "fantapanchina"

    private fun p(ctx: Context) = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    // La chiave contiene la giornata: alla giornata dopo si riparte da "non schierata".
    fun schierata(ctx: Context, lega: String, giornata: Int): Boolean =
        p(ctx).getBoolean("schierata_${lega}_$giornata", false)

    fun setSchierata(ctx: Context, lega: String, giornata: Int, v: Boolean) =
        p(ctx).edit().putBoolean("schierata_${lega}_$giornata", v).apply()
}

object Promemoria {

    const val CANALE = "formazione"
    const val EXTRA_LEGA = "lega"
    const val EXTRA_GIORNATA = "giornata"
    const val EXTRA_TESTO = "testo"

    /** Quanto prima della deadline, e come si chiama quel momento. */
    val ANTICIPI: List<Pair<Long, String>> = listOf(
        24L * 60 * 60 * 1000 to "24 ore",
        6L * 60 * 60 * 1000 to "6 ore",
        1L * 60 * 60 * 1000 to "1 ora"
    )

    private val ORA = SimpleDateFormat("EEEE d MMMM 'alle' HH:mm", Locale.ITALIAN)

    /** Su Android 13 e successivi le notifiche vanno concesse: senza permesso non ci prova. */
    fun mostra(ctx: Context, id: Int, n: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) return
        NotificationManagerCompat.from(ctx).notify(id, n)
    }

    fun creaCanale(ctx: Context) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val canale = NotificationChannel(
            CANALE,
            "Promemoria formazione",
            NotificationManager.IMPORTANCE_HIGH
        ).apply { description = "Avvisi prima della deadline per schierare" }
        nm.createNotificationChannel(canale)
    }

    /** La giornata da schierare per questa lega, secondo l'ultimo calendario scaricato. */
    fun prossima(ctx: Context, lega: Lega): Giornata? =
        Archivio.calendario(ctx, lega.calendario)?.prossima

    /**
     * Cosa aggiungere al promemoria: la formazione da schierare (della fonte o dell'assistente) o
     * i giocatori con notizie di infortunio. Gli avvisi si rifanno a ogni aggiornamento, quindi il
     * testo segue gli ultimi dati.
     */
    private fun dettaglio(ctx: Context, lega: Lega, giornata: Giornata): String {
        val notizie = Fonti.notizie(lega.fonte)
        if (notizie != null) {
            val a = lega.giocatori.mapNotNull { notizie.recente(ctx, it) }
            return if (a.isEmpty()) "\n\nNessuna notizia di infortuni per i tuoi giocatori."
            else "\n\nPossibili infortuni: " + a.joinToString(", ") { it.giocatore } +
                ". Valuta il cambio dalla panchina."
        }
        val s = Archivio.consigliata(ctx, lega, giornata.numero)
            ?: Assistente.consiglio(ctx, lega, giornata.numero)?.schierata
            ?: Archivio.probabili(ctx)?.takeIf { Fonti.calcio(lega.fonte) != null && it.giornata == giornata.numero }
                ?.let { Formazioni.consigliata(lega, it) }
        return s?.let { "\n\n" + Formazioni.testo(lega, it) }.orEmpty()
    }

    /** Riprogramma i tre avvisi della lega. Salta quelli già passati. */
    fun programma(ctx: Context, lega: Lega) {
        annulla(ctx, lega.id)
        val giornata = prossima(ctx, lega) ?: return
        if (Prefs.schierata(ctx, lega.id, giornata.numero)) return

        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val esatta = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()

        ANTICIPI.forEachIndexed { i, (delta, etichetta) ->
            val quando = giornata.inizio - delta
            if (quando <= System.currentTimeMillis()) return@forEachIndexed
            val testo = "${lega.nome}, giornata ${giornata.numero}: mancano $etichetta al primo " +
                "fischio di ${ORA.format(Date(giornata.inizio))}." + dettaglio(ctx, lega, giornata)
            val pi = pending(ctx, lega.id, i, giornata.numero, testo)
            try {
                if (esatta) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, quando, pi)
                else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, quando, pi)
            } catch (e: SecurityException) {
                // Permesso revocato nel frattempo: meglio un avviso un po' in ritardo che niente.
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, quando, pi)
            }
        }
    }

    fun annulla(ctx: Context, legaId: String) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        ANTICIPI.indices.forEach { i -> am.cancel(pending(ctx, legaId, i, 0, "")) }
    }

    fun programmaTutte(ctx: Context) {
        // Le versioni con le leghe fisse usavano come codice la posizione in elenco (0-39).
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        (0 until 40).forEach { codice ->
            PendingIntent.getBroadcast(
                ctx, codice, Intent(ctx, Sveglia::class.java),
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
            )?.let { am.cancel(it) }
        }
        Leghe.tutte(ctx).forEach { programma(ctx, it) }
    }

    /** Codice stabile per lega e anticipo: non dipende dall'ordine delle leghe. */
    private fun codice(legaId: String, anticipo: Int) = 1000 + (legaId.hashCode() and 0x00FFFFFF) * 10 + anticipo

    private fun pending(ctx: Context, legaId: String, anticipo: Int, giornata: Int, testo: String) =
        PendingIntent.getBroadcast(
            ctx,
            codice(legaId, anticipo),
            Intent(ctx, Sveglia::class.java).apply {
                putExtra(EXTRA_LEGA, legaId)
                putExtra(EXTRA_GIORNATA, giornata)
                putExtra(EXTRA_TESTO, testo)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
}

/** Riceve l'allarme e mostra la notifica. */
class Sveglia : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val legaId = intent.getStringExtra(Promemoria.EXTRA_LEGA) ?: return
        val giornata = intent.getIntExtra(Promemoria.EXTRA_GIORNATA, 0)
        // Se nel frattempo ha schierato, resta zitta.
        if (Prefs.schierata(ctx, legaId, giornata)) return

        val testo = intent.getStringExtra(Promemoria.EXTRA_TESTO) ?: "Tocca schierare la formazione."
        val apri = PendingIntent.getActivity(
            ctx, 0,
            Intent(ctx, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val n = NotificationCompat.Builder(ctx, Promemoria.CANALE)
            .setSmallIcon(R.drawable.ic_notifica)
            .setContentTitle("Formazione da schierare")
            .setContentText(testo)
            .setStyle(NotificationCompat.BigTextStyle().bigText(testo))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(apri)
            .build()

        Promemoria.mostra(ctx, legaId.hashCode(), n)
    }
}

/** Ogni 6 ore riscarica i dati, rimette gli avvisi e fa i report, anche ad app chiusa. */
class Aggiornamento(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {

    override fun doWork(): Result {
        Report.creaCanale(applicationContext)
        Fonti.notizie.forEach { it.creaCanale(applicationContext) }
        val errori = Archivio.aggiorna(applicationContext)
        Promemoria.programmaTutte(applicationContext)
        return if (errori.isEmpty()) Result.success() else Result.retry()
    }

    companion object {
        fun pianifica(ctx: Context) {
            val richiesta = PeriodicWorkRequestBuilder<Aggiornamento>(6, TimeUnit.HOURS)
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
                )
                .build()
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(
                "aggiornamento", ExistingPeriodicWorkPolicy.KEEP, richiesta
            )
        }
    }
}

/** Gli allarmi non sopravvivono al riavvio: li rimette. */
class Riavvio : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Promemoria.creaCanale(ctx)
            Report.creaCanale(ctx)
            Fonti.notizie.forEach { it.creaCanale(ctx) }
            Promemoria.programmaTutte(ctx)
            Aggiornamento.pianifica(ctx)
        }
    }
}
