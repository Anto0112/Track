# ATM Tracker — App Android

Tracker **tempo reale** fermate ATM Milano. Nessuna API Key richiesta.

## Apertura in Android Studio (Windows)

1. Decomprimi lo zip
2. **File → Open** → seleziona la cartella `ATMTracker`
3. Android Studio rileva `settings.gradle` e sincronizza Gradle automaticamente
4. Collega il telefono via USB (abilita Debug USB) oppure avvia un emulatore
5. Premi **▶ Run** (Shift+F10)

L'APK viene generato in `app/build/outputs/apk/debug/app-debug.apk`

## Generare APK da installare (senza PC collegato)

**Build → Build Bundle(s) / APK(s) → Build APK(s)**

Poi trasferisci l'APK sul telefono e installalo.  
*(Sul telefono: Impostazioni → Sicurezza → Installa app da sorgenti sconosciute)*

## Funzionalità

| Tab | Descrizione |
|-----|-------------|
| 🗺 Mappa | Tocca un punto → cerchio 400m → marker fermate vicine |
| 🔍 Indirizzo | Cerca una via → geocoding OSM → fermate su mappa |
| 🚌 Linea | Inserisci es. `45`, `38`, `S5` → tutte le fermate |

- Tap su marker/riga → dettaglio con linee, direzione, **tempo attesa**
- Attesa **verde** se GPS live disponibile, **nero** se nessun dato
- Bottom sheet: swipe verso il basso per aggiornare, auto-refresh ogni 30s

## Mappa: OpenStreetMap (OSMDroid)
Zero API Key, zero account Google, zero costi.

## API ATM (non ufficiale, no auth)
```
Base: https://giromilano.atm.it/proxy.tpportal/api/tpPortal/
GET  tpl/journeyPatterns/nearest?radius=400&Point.Y={lat}&Point.X={lng}
GET  tpl/journeyPatterns/{linea}|{dir}/stops
GET  geodata/pois/stops/{customerCode}    ← WaitMessage live
```
