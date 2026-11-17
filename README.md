<div align="center">

# Sonara

**Il tuo YouTube Music, solo audio, con un motore audio da audiofilo.**

Client Android per YouTube Music senza account e senza video, con equalizzatore parametrico e AutoEQ,
uscita Hi-Res, testi sincronizzati, download offline, Android Auto e importazione delle playlist
pubbliche di Spotify, Deezer e YouTube.

[![Build APK](https://github.com/marco90-cyber/Sonara/actions/workflows/build.yml/badge.svg)](https://github.com/marco90-cyber/Sonara/actions/workflows/build.yml)
[![Ultima versione](https://img.shields.io/github/v/release/marco90-cyber/Sonara?label=ultima%20versione)](https://github.com/marco90-cyber/Sonara/releases/latest)
![Android 8.0+](https://img.shields.io/badge/Android-8.0%2B-3DDC84)
[![Licenza GPL-3.0](https://img.shields.io/badge/licenza-GPL--3.0-blue)](LICENSE)

[**Scarica l'APK**](https://github.com/marco90-cyber/Sonara/releases/latest)

</div>

<p align="center">
  <img src="docs/screenshots/1.png" width="200" alt="Home">
  <img src="docs/screenshots/4.png" width="200" alt="Lettore">
  <img src="docs/screenshots/6.png" width="200" alt="Equalizzatore">
  <img src="docs/screenshots/5.png" width="200" alt="Testi sincronizzati">
</p>

## Perché Sonara

- **Niente account, niente pubblicità, niente video.** Si apre e si ascolta. Tutto gira sul telefono, senza server intermedi.
- **Un motore audio vero.** Il player e il DSP sono quelli di MLPlayer: motore nativo in C++, equalizzatore parametrico, AutoEQ, uscita adattiva, USB esclusiva, bit-perfect.
- **Le tue playlist ovunque siano.** Incolla il link di una playlist pubblica di Spotify, Deezer o YouTube e la ritrovi in Sonara.
- **Anche offline e in auto.** Download in una cartella a scelta, Android Auto, copia di riserva per ritrovare tutto dopo una reinstallazione.

## Funzioni

### Ricerca e scoperta
- Ricerca su YouTube Music con i filtri **Brani**, **Artisti**, **Album** e **Playlist**, e la scheda del primo risultato in evidenza.
- **Home** con le ricerche recenti, gli ascoltati di recente, i suggerimenti di YouTube Music e la sezione **Importate**.
- Pagina di playlist, album e artista con copertina, **Riproduci**, **Casuale**, **Scarica** e segnalibro.
- **Radio** da un brano: parti da una canzone e lascia andare i consigli.
- **Preferiti**, **cronologia** e **segnalibro** per salvare playlist, album e artisti nella Libreria.

### Importa playlist da Spotify, Deezer e YouTube
- Incolla il link nella barra di ricerca (vanno bene anche i link brevi) oppure cerca per nome: nel filtro Playlist compaiono anche quelle pubbliche di **Deezer**, con la provenienza scritta sotto ogni risultato.
- Ogni brano di Spotify e Deezer viene cercato su YouTube Music e scelto confrontando **titolo, artista e durata**; i brani si vedono comparire man mano.
- L'importazione gira **in background** con una notifica di avanzamento (e il tasto Interrompi): continua anche con l'app chiusa.
- Le playlist importate stanno nella Home, nella sezione **Importate** (tieni premuto per rimuoverle) e finiscono nella copia di riserva.
- **Aggiornamento**: il pulsante nella pagina della playlist rilegge l'originale (brani nuovi aggiunti, spariti tolti, nuovo ordine), senza rifare tutte le ricerche. In alternativa, **controllo automatico** a intervalli (6 ore, 12 ore, 1 giorno, 2 giorni, 1 settimana), anche solo con il Wi-Fi, con notifica dei cambiamenti.
- Solo playlist **pubbliche**. Spotify si legge dalla pagina pubblica e dà circa i primi 100 brani; la ricerca per nome c'è solo per Deezer.

### Lettore e audio
- **Equalizzatore** a tre modi: grafico, **parametrico** (fino a 16 filtri) e **AutoEQ** con il database delle cuffie; preset salvabili e importabili.
- **Moduli DSP**: preamp, toni con frequenza e Q, bassi analogici, aria, stereo e surround, bilanciamento, crossfeed, loudness, chiarezza, blocco DC, saturazione morbida, headroom automatico, volume dinamico, **limiter look-ahead**, dissolvenze e **ReplayGain**.
- **Uscita Hi-Res**: uscita adattiva (bit e kHz in base al DAC o alle casse), **USB esclusiva** con driver diretto al DAC, uscita diretta e modo **bit-perfect** senza DSP.
- **VU meter** nel lettore e **analizzatore** con spettro, livelli, fase e PPM.
- Riquadro con qualità e formato dell'audio in riproduzione.
- **Testi sincronizzati** riga per riga o parola per parola, da più fonti: LRCLIB, Apple Music e QQ Music (tramite BetterLyrics), Kugou, Unison, Lyrics.ovh e, in modo sperimentale, YouTube.

### Libreria e offline
- **Download** dei brani in una cartella a tua scelta, con una sottocartella per ogni playlist, album o artista; notifica con quanto manca, stop con un tocco, menu per scaricare o eliminare l'intera raccolta.
- **Modifica di tag e copertina** sui brani scaricati.
- **Copia di riserva** nella cartella dei download (download, preferiti, salvati, cronologia, playlist importate): scegli la stessa cartella dopo una reinstallazione e ritrovi tutto.

### Android Auto
- Copertina e comandi, ricerca, e le tue playlist sincronizzate con l'app.

### App
- Tema **Material You** (MLTheme) con i colori presi dalla copertina, vibrazione al tocco regolabile.
- **Aggiornamenti** direttamente dall'app, dalle release di GitHub.
- Log dettagliato esportabile, per segnalare i problemi.

## Screenshot

<p align="center">
  <img src="docs/screenshots/2.png" width="180" alt="Ricerca">
  <img src="docs/screenshots/3.png" width="180" alt="Playlist">
  <img src="docs/screenshots/7.png" width="180" alt="Libreria">
  <img src="docs/screenshots/8.png" width="180" alt="Importazione">
  <img src="docs/screenshots/9.png" width="180" alt="Impostazioni">
</p>
<p align="center">
  <img src="docs/screenshots/10.png" width="420" alt="Android Auto">
</p>

## Installazione

1. Apri la pagina delle [**release**](https://github.com/marco90-cyber/Sonara/releases/latest) dal telefono.
2. Scarica l'ultimo file `Sonara-1.0.N.apk` e aprilo (Android chiederà il permesso di installare da questa fonte).
3. Al primo avvio, se vuoi scaricare i brani, scegli la cartella dove salvarli.

Requisiti: **Android 8.0 o successivo**. Le versioni successive si aggiornano da sole dalle Impostazioni › Aggiornamenti.

## Come si usa

| Voglio… | Faccio così |
|---|---|
| Cercare un brano o una playlist | Barra di ricerca in alto nella Home, poi scelgo il filtro |
| Importare una playlist | Incollo il link nella barra di ricerca, oppure cerco per nome nel filtro Playlist |
| Tenere aggiornate le importate | Pulsante ↻ nella playlist, oppure Impostazioni › Playlist importate › Aggiorna da sole |
| Ascoltare offline | Pulsante Scarica in una playlist, album o artista |
| Salvare una playlist | Segnalibro accanto a Riproduci |
| Cambiare il suono | Scheda EQ, oppure Impostazioni › Equalizzatore |
| Ritrovare tutto dopo una reinstallazione | Scelgo la stessa cartella dei download |

## Compilare da sorgente

L'app è scritta in **Kotlin** con **Jetpack Compose** (Material Design 3); il motore audio e il DSP sono in **C++** (JNI, Oboe).

- Servono Android Studio (o JDK 17), Android SDK 35, NDK e CMake.
- La build ufficiale la fa [GitHub Actions](.github/workflows/build.yml) a ogni push su `main`: esegue i test del DSP nativo e quelli Kotlin, firma l'APK con un keystore privato e lo pubblica nelle release.
- La release è firmata solo se sono presenti i segreti del keystore: per una build tua usa una firma tua.

Struttura del codice (`app/src/main/java/com/marco/mlplayer`):

| Cartella | Contenuto |
|---|---|
| `yt/` | YouTube Music (ricerca, stream), download, preferiti, copia di riserva, importazione delle playlist |
| `audio/` | Motore audio, DSP, equalizzatore, servizi di riproduzione, download e importazione |
| `lyrics/` | Fonti dei testi e loro formati |
| `ui/` | Schermate in Compose |
| `data/` | Impostazioni e modelli |
| `usbaudio/` | Modulo per l'uscita USB esclusiva |

## Contribuire

Segnalazioni e proposte sono benvenute: apri una [issue](https://github.com/marco90-cyber/Sonara/issues) (allega il log da Impostazioni › Debug › Esporta log) o scrivi nel gruppo Telegram di MLapps. Chi vuole dare una mano allo sviluppo è il benvenuto.

## Note legali

Sonara non è affiliata né approvata da YouTube, Google, Spotify o Deezer; i nomi appartengono ai rispettivi proprietari. L'app è pensata per uso personale e legge soltanto contenuti pubblici. Sei tu a rispettare i termini dei servizi e le leggi sul diritto d'autore del tuo Paese.

## Crediti

[NewPipeExtractor](https://github.com/TeamNewPipe/NewPipeExtractor), [Media3](https://github.com/androidx/media), [OkHttp](https://github.com/square/okhttp), [Oboe](https://github.com/google/oboe), [libsoxr](https://sourceforge.net/projects/soxr/), [jaudiotagger](https://github.com/ijabz/jaudiotagger), [Coil](https://github.com/coil-kt/coil), [AutoEQ](https://github.com/jaakkopasanen/AutoEq), [LRCLIB](https://lrclib.net), [BetterLyrics](https://github.com/boidushya/better-lyrics), [LastWave](https://github.com/Clash-Projects/LastWave-native), [Tryptify](https://github.com/tryptz/Tryptify) e l'Audio EQ Cookbook di Robert Bristow-Johnson. Dettagli e licenze in [NOTICE](NOTICE).

## Licenza

[GPL-3.0](LICENSE)
