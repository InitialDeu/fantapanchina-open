package io.github.initialdeu.fantapanchina

import android.app.Activity
import android.text.InputType
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog

/**
 * Dialogo per scegliere l'assistente: Claude, OpenAI o un server compatibile fatto in casa.
 * Le chiavi restano cifrate solo sul telefono. [fatto] viene chiamato dopo il salvataggio.
 */
fun Activity.impostazioniAssistente(fatto: () -> Unit) {
    val dp = { v: Int -> (v * resources.displayMetrics.density).toInt() }
    val provider = Provider.values()

    val spiega = TextView(this).apply {
        textSize = 13f
        setPadding(0, 0, 0, dp(8))
    }
    val scelta = Spinner(this).apply {
        adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, provider.map { it.etichetta })
        setSelection(provider.indexOf(Assistente.provider(context)))
    }
    val url = EditText(this).apply {
        hint = "Indirizzo, es. http://192.168.1.10:11434/v1"
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        setSingleLine()
        setText(Assistente.url(context))
    }
    val modello = EditText(this).apply { setSingleLine() }
    val chiave = EditText(this).apply {
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        setSingleLine()
    }

    fun aggiorna(p: Provider) {
        spiega.text = when (p) {
            Provider.ANTHROPIC ->
                "Chiave API da console.anthropic.com (o platform.claude.com) → API keys. A consumo, " +
                    "separata dall'abbonamento a Claude.ai. Modello predefinito: ${p.modelloPredefinito}."
            Provider.OPENAI ->
                "Chiave API da platform.openai.com → API keys. Scrivi il nome del modello che vuoi " +
                    "usare, come lo trovi nella documentazione di OpenAI."
            Provider.COMPATIBILE ->
                "Un server che parla l'API di OpenAI: Ollama, LM Studio, vLLM o uno tuo. Indirizzo fino " +
                    "a /v1 compreso, nome del modello e, se serve, la chiave."
        }
        url.visibility = if (p.serveUrl) View.VISIBLE else View.GONE
        modello.hint = if (p.modelloPredefinito.isNotEmpty()) "Modello (vuoto = ${p.modelloPredefinito})" else "Modello"
        modello.setText(Assistente.modello(this, p).takeIf { it != p.modelloPredefinito }.orEmpty())
        chiave.hint = if (Assistente.haChiave(this, p)) "Chiave salvata (lascia vuoto per tenerla)"
        else if (p == Provider.COMPATIBILE) "Chiave (facoltativa)" else "Chiave API"
        chiave.setText("")
    }
    scelta.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
        override fun onItemSelected(a: AdapterView<*>?, v: View?, pos: Int, id: Long) = aggiorna(provider[pos])
        override fun onNothingSelected(a: AdapterView<*>?) {}
    }
    aggiorna(Assistente.provider(this))

    val corpo = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20), dp(12), dp(20), 0)
        addView(scelta)
        addView(spiega)
        addView(url)
        addView(modello)
        addView(chiave)
        addView(TextView(context).apply {
            text = "L'assistente sceglie la formazione prima della deadline e commenta i report. Ogni " +
                "richiesta ha un costo sul tuo account del provider. Le chiavi restano cifrate solo su " +
                "questo telefono."
            textSize = 12f
            setPadding(0, dp(8), 0, 0)
        })
    }

    AlertDialog.Builder(this)
        .setTitle("Assistente")
        .setView(corpo)
        .setPositiveButton("Salva") { _, _ ->
            val p = provider[scelta.selectedItemPosition]
            Assistente.salvaImpostazioni(this, p, modello.text.toString(), url.text.toString(), chiave.text.toString())
            Toast.makeText(
                this,
                if (Assistente.attivo(this)) "${Assistente.nome(this)} collegato" else "Mancano dei dati per collegarlo",
                Toast.LENGTH_SHORT
            ).show()
            fatto()
        }
        .setNeutralButton("Scollega") { _, _ ->
            Assistente.scollega(this)
            fatto()
        }
        .setNegativeButton("Annulla", null)
        .show()
}
