# Pubblicare su Google Play

Guida passo passo per pubblicare FantaPanchina (versione open) su Google Play, con i testi pronti
e le risposte ai moduli di Play Console. I requisiti di Google cambiano: quando trovi una
differenza vale quello che dice Play Console.

## 1. Prima di iniziare

- **Account sviluppatore**: [play.google.com/console](https://play.google.com/console), 25 $ una
  tantum, verifica dell'identità con un documento.
- **Account personali nuovi**: prima di andare in produzione serve un **test chiuso con almeno 12
  tester per 14 giorni consecutivi**. Prepara 12 persone con un account Google e un telefono
  Android.
- **Dati pubblici**: nome dello sviluppatore, paese ed email di contatto compaiono nella scheda.
  Conviene un'email dedicata. Se monetizzi, come "operatore commerciale" (normativa europea DSA)
  diventano pubblici anche indirizzo e telefono.

## 2. Configurare il repo

In **Settings → Secrets and variables → Actions**:

**Secret**

| Nome | Contenuto |
|---|---|
| `KEYSTORE_BASE64` | la tua **chiave di caricamento** (upload key) in base64 |
| `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD` | dati della chiave |
| `THESPORTSDB_KEY` | facoltativo: chiave TheSportsDB a pagamento (vedi sotto) |

**Variabili**

| Nome | Contenuto |
|---|---|
| `PRIVACY_URL` | indirizzo della privacy policy, es. il link a `PRIVACY.md` su GitHub |
| `CONTATTO_EMAIL` | email di contatto mostrata nell'app |
| `SEGNALAZIONI_URL` | dove arrivano le segnalazioni dei contenuti IA (vedi punto 6) |
| `KEYSTORE_TYPE` | `jks` se la chiave è JKS (vuoto = PKCS12) |

Come creare la chiave è spiegato nel [README](README.md#firma-e-aggiornamenti). Conservala al
sicuro: con Play App Signing, se la perdi puoi chiederne il ripristino a Google, ma è una procedura
lenta.

**Privacy policy**: in `PRIVACY.md` sostituisci `CONTATTO_DA_INSERIRE` con la tua email. Google
accetta il link alla pagina su GitHub
(`https://github.com/<utente>/<repo>/blob/main/PRIVACY.md`).

**TheSportsDB**: le chiavi gratuite sono pensate per prove e progetti piccoli, e nel 2026 sono
state sospese per alcuni giorni. Per un'app pubblicata leggi i termini del servizio e valuta la
chiave a pagamento.

Ogni push su `main` produce l'artifact `fantapanchina-open-aab`: è il file da caricare su Play
Console. Il `versionCode` cresce da solo a ogni build.

## 3. Creare l'app su Play Console

- Nome: `FantaPanchina`
- Lingua predefinita: Italiano
- App o gioco: **App**
- Gratuita o a pagamento: a tua scelta. Una gratuita non può diventare a pagamento in seguito.
- Al primo caricamento accetta **Play App Signing**: Google custodisce la chiave di firma dell'app
  e tu carichi gli AAB firmati con la chiave di caricamento.

## 4. Scheda dello store

**Titolo** (max 30 caratteri)

```
FantaPanchina – fantasport
```

**Descrizione breve** (max 80)

```
Rose, deadline e formazione consigliata dall'IA per le tue leghe di fantasport
```

**Descrizione completa**

```
FantaPanchina ti aiuta a gestire le tue leghe di fantasport: calcio, volley, basket o qualsiasi
altro sport.

• Leghe di ogni sport: crea la rosa a mano o importala da un file JSON o CSV.
• Calendario e deadline: scegli il campionato e l'app sa quando si gioca la prossima giornata.
• Promemoria a 24 ore, 6 ore e 1 ora dalla deadline, con la formazione da schierare.
• Formazione consigliata da un assistente IA a tua scelta: Claude, OpenAI o un server tuo
  compatibile. Modulo, titolari con il motivo, panchina in ordine.
• Report di fine giornata con storico e commento.
• Funziona anche offline con gli ultimi dati scaricati.

L'assistente si collega con la tua chiave API del provider: le richieste hanno il costo previsto
dal provider. I consigli sono generati dall'IA e possono essere sbagliati.

Nessun account, nessuna pubblicità, nessun tracciamento: i tuoi dati restano sul telefono.

FantaPanchina è un'app indipendente e non è affiliata a leghe, siti di fantasport o provider di
intelligenza artificiale. Non prevede premi né denaro in gioco.
```

**Grafica** (nella cartella [`play/`](play))

- Icona 512 × 512: `play/icona-512.png`
- Immagine in evidenza 1024 × 500: `play/grafica-1024x500.png`
- Screenshot: almeno 2, da fare sul telefono (lista leghe, formazione, report).

**Categoria**: Sport. **Tag**: fantasy, gestione squadre.

**Marchi**: puoi nominare Claude, OpenAI e Anthropic per dire con cosa funziona l'app, ma non nel
titolo, nell'icona o nella grafica, e senza far pensare a una collaborazione. Non usare nomi di
leghe, campionati o giochi di fantasport registrati come marchio.

## 5. Contenuti dell'app (sezione "Norme")

| Voce | Risposta |
|---|---|
| Privacy policy | il tuo `PRIVACY_URL` |
| Annunci | No, l'app non contiene annunci |
| Accesso all'app | Tutte le funzioni sono disponibili senza credenziali. Spiega ai revisori che l'assistente IA richiede una chiave API del provider e che il resto funziona senza |
| Classificazione dei contenuti | Questionario IARC: categoria "Utilità/produttività", nessuna violenza, nessun contenuto per adulti, nessun gioco d'azzardo, nessuna interazione tra utenti |
| Pubblico di destinazione | 18 anni e più (scegliere fasce sotto i 13 anni attiva le regole "Famiglie") |
| App di notizie | No |
| App governative, finanziarie, sanitarie | No |

### Sicurezza dei dati

Risposte prudenti, coerenti con quello che l'app fa:

- **L'app raccoglie o condivide dati?** Sì.
- **Crittografia in transito**: Sì.
- **Gli utenti possono chiedere la cancellazione?** I dati sono solo sul dispositivo e si cancellano
  dall'app (Informazioni e privacy → Cancella tutti i dati) o disinstallandola. Non c'è un account.

| Tipo di dato | Raccolto | Condiviso | Facoltativo | Scopo |
|---|---|---|---|---|
| Altri contenuti generati dagli utenti (rose, note delle leghe) | Sì | Sì, con il provider IA scelto dall'utente | Sì | Funzionalità dell'app |
| Altre azioni nell'app (segnalazioni dei contenuti IA) | Sì | No | Sì | Funzionalità dell'app (moderazione) |

Non vanno dichiarati: posizione, identificativi del dispositivo, contatti, dati finanziari, dati di
salute, log degli arresti anomali (l'app non li raccoglie).

## 6. Norme sui contenuti generati dall'IA

Google chiede che si possano segnalare i contenuti generati dall'IA **senza uscire dall'app**.
L'app ha il pulsante "Segnala" sotto ogni formazione e commento dell'IA: il contenuto viene tolto
subito e la segnalazione parte verso `SEGNALAZIONI_URL`.

Imposta `SEGNALAZIONI_URL`: senza, l'app apre l'app email, cioè fa uscire l'utente. Un modo
gratuito è un Google Apps Script che scrive le segnalazioni in un foglio Google:

1. Crea un foglio Google, poi **Estensioni → Apps Script** e incolla:

   ```javascript
   function doPost(e) {
     const d = JSON.parse(e.postData.contents);
     SpreadsheetApp.getActiveSheet().appendRow([
       new Date(), d.tipo, d.motivo, d.nota, d.contenuto, d.assistente, d.modello, d.versione_app
     ]);
     return ContentService.createTextOutput("ok");
   }
   ```

2. **Esegui il deployment → Nuovo deployment → App web**, "Esegui come: me", "Chi ha accesso:
   chiunque". L'indirizzo che ottieni va in `SEGNALAZIONI_URL`.

Controlla il foglio ogni tanto: le regole chiedono di esaminare le segnalazioni.

## 7. Permessi

| Permesso | Perché |
|---|---|
| `INTERNET` | calendario, fonti dati, assistente |
| `POST_NOTIFICATIONS` | promemoria prima della deadline e avvisi |
| `SCHEDULE_EXACT_ALARM` | far arrivare il promemoria all'ora giusta prima della deadline. È concesso dall'utente nelle impostazioni; se Play Console chiede una dichiarazione, la motivazione è "promemoria programmati dall'utente legati a una scadenza" |
| `RECEIVE_BOOT_COMPLETED` | rimettere i promemoria dopo il riavvio del telefono |

## 8. Test e pubblicazione

1. **Test interno**: carica il primo AAB e provalo tu.
2. **Test chiuso**: aggiungi i 12 tester (lista email o gruppo Google), fai installare l'app e
   tienila in test per 14 giorni. Raccogli i loro commenti.
3. **Richiedi l'accesso alla produzione** da Play Console, rispondendo alle domande sul test.
4. **Produzione**: carica l'AAB e invialo in revisione. La prima revisione può richiedere alcuni
   giorni.

## 9. Monetizzazione

Le possibilità sono app a pagamento, acquisti in-app (con il sistema di pagamento di Google),
abbonamenti o annunci. Ognuna ha conseguenze fiscali diverse: informati con un commercialista
prima di attivarla. Google trattiene il 15% sulle vendite. Le donazioni dentro l'app tramite
servizi esterni non sono ammesse (solo per enti benefici verificati); un link alle donazioni sulla
pagina del repo invece va bene.
