# Documentazione Refactoring Architetturale Streamflix

Questa documentazione descrive l'evoluzione tecnica del progetto, evidenziando le differenze tra la struttura originale e l'attuale architettura ottimizzata.

**IMPORTANTE:** Tutte le modifiche descritte in questa documentazione partono dalla **base stabile di Streamflix 1.7.96**.

## 1. Analisi del Progetto Originale (Stato Iniziale)
Prima dell'intervento, il progetto presentava diverse sfide architetturali che influivano su performance e manutenibilità:

*   **Gestione Rete (OkHttp/Retrofit):** Ogni provider gestiva le proprie istanze di rete in modo isolato. Questo frammentava la configurazione DNS (DoH) e rendeva difficile applicare policy di sicurezza o timeout globali.
*   **Esecuzione Sequenziale:** Le richieste di rete per popolare la Home Page o i dettagli di un titolo avvenivano in modo seriale. L'app attendeva la fine di una richiesta prima di iniziare la successiva, causando colli di bottiglia visibili all'utente.
*   **Ciclo di Vita e Memory Leak:** L'inizializzazione di componenti core (Preferenze, Database) dipendeva spesso dal Context delle Activity, con il rischio di perdite di memoria durante la rotazione dello schermo o il cambio di sezione.

## 2. Evoluzione Architetturale (Stato Attuale)

### 🚀 Performance: Parallelismo Universale
È stato introdotto un uso intensivo di `coroutineScope` e `async/await` in tutti i provider.
- **Prima:** La Home Page veniva caricata sezione per sezione.
- **Dopo:** Tutte le sezioni vengono richieste simultaneamente. Il tempo di caricamento totale è ora pari a quello della singola richiesta più lenta. In `Cine24hProvider.kt`, anche le categorie della home sono ora caricate in parallelo.

### 🏗️ Infrastruttura: NetworkClient Centralizzato
Creato `NetworkClient.kt` per standardizzare ogni comunicazione in uscita.
- **User-Agent Unificato (Pixel 7):** Per prevenire blocchi Cloudflare, viene forzato un User-Agent mobile moderno (`NetworkClient.USER_AGENT`) condiviso tra OkHttp e WebView. Questo spinge i sistemi anti-bot a servire sfide più semplici e compatibili.
- **Cookie Jar Reattivo:** Un unico gestore sincronizza istantaneamente le sessioni tra il browser interno e le chiamate API, eseguendo un `flush()` forzato dopo ogni interazione riuscita.

### 🛡️ Mitigazione Anti-Bot: Virtual Cursor & Global Key Interception (Android TV)
Introdotto un sistema avanzato per superare Cloudflare su dispositivi senza touch (TV).
- **Global Key Interception:** In `WebViewResolver.kt`, il contenitore principale intercetta i tasti del telecomando tramite `dispatchKeyEvent`. Questo impedisce alla WebView di "rubare" il focus e garantisce che il mirino rimanga sempre controllabile dall'utente.
- **Virtual Cursor (Mirino Rosso):** Un mirino ad alta visibilità (`elevation = 100f`) permette all'utente di puntare con precisione il checkbox di verifica.
- **Precision Mouse Emulation:** Al click del tasto OK, il sistema simula una sequenza mouse reale (`Hover Move` -> `Mouse Down` -> `Mouse Up`). Questo segnale è interpretato da Cloudflare come interazione umana autentica, superando i blocchi che ignorano i semplici tocchi simulati.
- **Full-Screen Bypass UI & Multi-language Support:** La finestra di verifica è ora a tutto schermo (`NoActionBar_Fullscreen`) con un layout `RelativeLayout` ancorato. Le istruzioni del mirino sono localizzate in tutte le lingue dell'app (IT, ES, AR, FR, DE, EN).

## 3. Limiti Tecnici e Sfide: Android TV 9 e inferiori (Browser Obsoleto)
**ATTENZIONE:** Su sistemi con **Android TV 9.0 o versioni inferiori**, l'app è strutturalmente impossibilitata a funzionare con i provider protetti utilizzando i metodi standard a causa dell'obsolescenza del motore browser integrato.
- **Chrome Legacy (v66 o meno):** Le versioni di Chrome integrate in questi OS sono ormai troppo vecchie (spesso del 2018). Cloudflare identifica questi motori come insicuri o potenziali bot, rendendo nullo qualsiasi tentativo di sblocco automatico o tramite D-Pad standard.
- **Mancanza di Focus Nativo:** Il quadratino di verifica Cloudflare non è focalizzabile. Senza il sistema di mirino e la simulazione fisica del mouse introdotta in questo refactoring, l'app rimarrebbe bloccata infinitamente sulla pagina di verifica.
- **Soluzione Unica:** L'emulazione del mouse via software e il puntamento manuale col mirino rappresentano l'unica via tecnica per permettere la visione dei contenuti su hardware TV datato.

## 4. Riepilogo dei Miglioramenti

| Area | Stato Precedente | Stato Attuale | Impatto |
| :--- | :--- | :--- | :--- |
| **Versione Base** | - | **Streamflix 1.7.96** | Punto di partenza stabile |
| **Interazione TV** | Blocco Telecomando | Virtual Cursor & Global Intercept | Controllo totale e sblocco garantito |
| **Bypass UI** | Layout instabile | Full-Screen RelativeLayout | Istruzioni localizzate e visibili |
| **Velocità** | Sequenziale | Parallela (async/await) | Caricamento categorie simultaneo |
| **Anti-Bot** | Sfide TV complesse | UA Mobile Pixel 7 Sync | Sfide più semplici e veloci |
| **Rete** | Timeout lunghi | Adaptive Timeouts (3s check) | Passaggio immediato al bypass |

---
*Documentazione aggiornata: Ottimizzazione Legacy WebView Android TV 9 e Localizzazione Guida Mirino (2026).*
