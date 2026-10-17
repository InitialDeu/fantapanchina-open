# FantaPanchina (open)

App Android per gestire le rose dei fantasport — calcio, volley, basket o qualsiasi altro sport —
con promemoria prima della deadline, formazione consigliata da un assistente IA a tua scelta e
report di fine giornata.

Questa è la versione aperta: uno scheletro pronto da compilare e da estendere. Non contiene rose
né fonti dati legate a siti specifici; il calendario viene da un'API pubblica.

## Cosa fa

- **Leghe di qualsiasi sport**, create a mano dall'app o importate da file JSON o CSV. Per ogni
  lega: reparti, giocatori con crediti, moduli ammessi, note sulle regole.
- **Calendario e deadline** da [TheSportsDB](https://www.thesportsdb.com): cerchi il campionato
  per paese e sport, e l'app ricava la prossima giornata e il primo fischio d'inizio.
- **Promemoria** a 24 ore, 6 ore e 1 ora dalla deadline, con la formazione da schierare nel testo.
  "Ho schierato" li spegne per quella giornata.
- **Assistente IA** che sceglie la formazione (modulo, titolari con il motivo, panchina in
  ordine) e commenta i report. Puoi collegare:
  - **Claude** (Anthropic), con l'SDK ufficiale;
  - **OpenAI**, API Chat Completions;
  - **un server fatto in casa** compatibile con l'API OpenAI: Ollama, LM Studio, vLLM o il tuo.
  La formazione restituita viene controllata (modulo ammesso, giocatori della rosa, numero giusto
  per reparto) prima di essere mostrata.
- **Report di fine giornata** con storico, per le leghe che hanno una fonte di voti.
- Funziona offline con gli ultimi dati scaricati e si aggiorna da solo ogni 6 ore.
- **Segnalazione dei contenuti IA** con un tocco, e **cancellazione di tutti i dati** da
  Informazioni e privacy.

## Google Play

L'app è pronta per Google Play: target Android 16, build AAB nel workflow, privacy policy
([`PRIVACY.md`](PRIVACY.md)), segnalazione dei contenuti IA dall'app, cancellazione dei dati,
icona e grafica per la scheda in [`play/`](play). Tutti i passaggi, con i testi della scheda e le
risposte ai moduli di Play Console, sono in [`GOOGLE_PLAY.md`](GOOGLE_PLAY.md).

## Compilare senza installare niente

1. Fai un fork o usa questo repo come template.
2. Vai su **Actions**: a ogni push su `main` il workflow "Compila APK" produce l'artifact
   `fantapanchina-open-apk`. Puoi anche lanciarlo a mano con **Run workflow**.
3. Scarica l'APK, passalo sul telefono e installalo (serve "Installa app sconosciute").

### Firma e aggiornamenti

Senza configurare nulla l'APK è firmato con una chiave di debug generata a ogni build: si
installa, ma la build successiva non si installa sopra. Per gli aggiornamenti crea una tua chiave
e aggiungi questi secret al repo (Settings → Secrets and variables → Actions):

| Secret | Contenuto |
|---|---|
| `KEYSTORE_BASE64` | il keystore (PKCS12 o JKS) in base64 |
| `KEYSTORE_PASSWORD` | password del keystore |
| `KEY_ALIAS` | alias della chiave |
| `KEY_PASSWORD` | password della chiave |

Un keystore si crea anche senza Android Studio, per esempio con `keytool` del JDK:

```bash
keytool -genkeypair -v -keystore firma.p12 -storetype PKCS12 -alias fantapanchina \
  -keyalg RSA -keysize 4096 -validity 10950
base64 -w0 firma.p12   # il risultato va in KEYSTORE_BASE64
```

Tieni il keystore al sicuro e fuori dal repo: senza, l'app installata non si aggiorna più.

### In locale

Servono JDK 17 e l'Android SDK (API 36): `gradle assembleRelease`, e l'APK finisce in
`app/build/outputs/apk/release/`.

## Importare una lega

Da **Gestisci leghe → Importa da file**. Esempi pronti nella cartella [`esempi/`](esempi).

**CSV** — una riga per giocatore, con intestazione; separatore virgola o punto e virgola:

```csv
reparto,nome,crediti,id
Playmaker,Rossi,30,
Centri,Viola,32,
```

`id` è facoltativo: serve solo se la lega usa una fonte dati che abbina i giocatori per id. Nome
e sport della lega li chiede l'app; moduli e calendario si aggiungono poi dall'editor.

**JSON** — lo stesso formato che l'app produce con **Esporta**:

```json
{
  "formato": "fantapanchina-lega",
  "versione": 1,
  "lega": {
    "nome": "Lega di esempio",
    "sport": "Calcio",
    "note": "500 crediti",
    "calendario": "4332",
    "moduli": ["4-4-2", "4-3-3"],
    "reparti": [
      { "nome": "Portieri", "giocatori": [ { "nome": "Portiere A", "crediti": 20 } ] }
    ]
  }
}
```

Si può importare anche un array di leghe. I moduli indicano i titolari per reparto nell'ordine
dei reparti; se hanno un numero in meno dei reparti, il primo reparto vale 1 (il portiere del
calcio: `4-4-2`).

## Assistente

Da **Collega un assistente** scegli il provider, il modello e la chiave:

- **Claude**: chiave da [console.anthropic.com](https://console.anthropic.com). Modello
  predefinito `claude-opus-5-5`, con i fallback lato server di Anthropic attivi.
- **OpenAI**: chiave da [platform.openai.com](https://platform.openai.com) e nome del modello.
- **Server compatibile**: indirizzo fino a `/v1` compreso (es. `http://192.168.1.10:11434/v1`
  per Ollama in rete locale), nome del modello e chiave se il server la chiede. L'app chiede
  l'output strutturato (`json_schema`); se il server non lo supporta, chiede il JSON nel testo.

Le chiavi restano solo sul telefono, cifrate con il Keystore di Android. Ogni richiesta ha un
costo sul tuo account del provider (zero con un server tuo).

## Aggiungere una fonte dati

Le fonti sono moduli. In `FontiBase.kt` ci sono due interfacce:

- `FonteCalcio` — probabili formazioni e voti per id giocatore. Con questa l'app colora la rosa,
  mostra il voto della giornata precedente, fa i report e l'assistente sceglie in automatico
  quando escono le probabili;
- `FonteNotizie` — avvisi quando un giocatore compare in una notizia di infortunio.

Implementane una e registrala in `Fonti.kt`, insieme a un `TipoLega` che la usi. Prima di leggere
le pagine di un sito controlla che i suoi termini d'uso lo consentano.

## Struttura

| File | Cosa contiene |
|---|---|
| `Modello.kt` | `Lega`, `Giocatore` |
| `Leghe.kt` | archivio delle leghe sul telefono, import ed export JSON/CSV |
| `Rete.kt` | download, calendario TheSportsDB, archivio dei dati scaricati e aggiornamento |
| `FontiBase.kt`, `Fonti.kt` | interfacce e registro delle fonti dati |
| `Assistente.kt` | provider IA, richieste, controllo della formazione |
| `Report.kt` | calcolo delle formazioni e report di fine giornata |
| `Promemoria.kt` | notifiche, sveglie, aggiornamento in background |
| `MainActivity.kt`, `LegheActivity.kt`, `LegaActivity.kt`, `ReportActivity.kt` | schermate |

## Privacy

L'app non ha un server suo e non raccoglie dati. Contatta solo TheSportsDB per il calendario, il
provider IA che scegli tu (con i dati della rosa necessari alla richiesta) ed eventuali fonti che
aggiungi. Niente backup su cloud, niente traffico in chiaro.

## Licenza

[MIT](LICENSE): puoi usare, modificare e ridistribuire il codice, anche in app tue, mantenendo
l'avviso di copyright. Nessuna garanzia: i consigli dell'assistente e i dati delle fonti possono
essere sbagliati.
