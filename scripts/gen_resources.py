#!/usr/bin/env python3
"""Generate the Android resources from cfs_core of the Qt version.

Produces (all committed, so the Gradle build needs no Python):
  app/src/main/res/values{,-de,-fr,-es,-nl,-it}/strings.xml
        every key of cfs_core.lang.STRINGS (+ level names, set names, help
        window texts, info text and a few Android-only strings)
  app/src/main/res/raw{,-de,...}/help.json
        cfs_core.help_content.HELP_CONTENT with the Android adaptations below
  app/src/main/assets/sets/sets.json
        per stone set: edge colour and the stone radii measured exactly like
        cfs_core.sets.SetArt.load_all() (rings for last move / win)
  app/src/main/assets/sets/set<N>/{back,red,yellow}.webp
        the 256 px HiRes tiles as WebP: quality 95 where that stays visually
        lossless (PSNR >= 38 dB against the PNG), otherwise lossless WebP
        (flat-colour sets such as set 10 would show ringing)

values/ (default) is English, as Android expects a default locale.

Usage (Python of the Qt version: Pillow with WebP support is needed):
  PYTHONDONTWRITEBYTECODE=1 "<Qt dir>/.venv/bin/python" scripts/gen_resources.py "<Qt dir>"
"""

import json
import os
import re
import sys
from xml.sax.saxutils import escape

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
RES = os.path.join(ROOT, "app", "src", "main", "res")
ASSETS = os.path.join(ROOT, "app", "src", "main", "assets")

LANGS = ("en", "de", "fr", "es", "nl", "it")
DEFAULT = "en"
WEBP_QUALITY = 95
PSNR_MIN = 38.0

# --------------------------------------------------------------------------
# Android-only strings (not part of the desktop program)
# --------------------------------------------------------------------------
ANDROID_STRINGS = {
    "a_book_loading": {
        "de": "Eröffnungsbuch wird vorbereitet …", "en": "Preparing the opening book …",
        "fr": "Préparation du livre d'ouvertures …", "es": "Preparando el libro de aperturas …",
        "nl": "Openingsboek wordt voorbereid …", "it": "Preparazione del libro di aperture …"},
    "a_eval_running": {
        "de": "Bewertung läuft …", "en": "Evaluating …", "fr": "Évaluation en cours …",
        "es": "Evaluando …", "nl": "Bezig met beoordelen …", "it": "Valutazione in corso …"},
    "a_no_file_app": {
        "de": "Keine App zur Dateiauswahl gefunden.", "en": "No app found to pick a file.",
        "fr": "Aucune application de sélection de fichiers trouvée.",
        "es": "No se encontró ninguna app para elegir archivos.",
        "nl": "Geen app gevonden om een bestand te kiezen.",
        "it": "Nessuna app trovata per scegliere un file."},
    "a_copied": {
        "de": "In die Zwischenablage kopiert.", "en": "Copied to the clipboard.",
        "fr": "Copié dans le presse-papiers.", "es": "Copiado al portapapeles.",
        "nl": "Naar het klembord gekopieerd.", "it": "Copiato negli appunti."},
    "a_lang_system": {
        "de": "Systemsprache", "en": "System language", "fr": "Langue du système",
        "es": "Idioma del sistema", "nl": "Systeemtaal", "it": "Lingua di sistema"},
    "a_big_board": {
        "de": "Großes Brett", "en": "Large board", "fr": "Grand plateau",
        "es": "Tablero grande", "nl": "Groot bord", "it": "Scacchiera grande"},
    "a_menu": {
        "de": "Menü", "en": "Menu", "fr": "Menu", "es": "Menú", "nl": "Menu", "it": "Menu"},
}

# Info text: the desktop lines about Python/Qt replaced by the Android port.
INFO_TEXT = {
    "de": ("ConnectFour Studio für Android\n"
           "Engine: BitBully von Markus Thill\n"
           "Kotlin-Portierung des C++-Kerns (bitbully 0.0.79)\n"
           "Suchverfahren: iterative Vertiefung, MTD(f)/Null-Window,\n"
           "Bitboards, Transposition Table, 12-ply-dist-Buch\n"
           "GUI: Kotlin + Android-Framework (ohne Zusatzbibliotheken)\n"
           "Idee & Tests: Patrick Götz, Umsetzung: KI\n"
           "Open Source (GNU AGPL v3)"),
    "en": ("ConnectFour Studio for Android\n"
           "Engine: BitBully by Markus Thill\n"
           "Kotlin port of the C++ core (bitbully 0.0.79)\n"
           "Search: iterative deepening, MTD(f)/null-window,\n"
           "bitboards, transposition table, 12-ply-dist book\n"
           "GUI: Kotlin + Android framework (no extra libraries)\n"
           "Idea & testing: Patrick Götz, implementation: AI\n"
           "Open source (GNU AGPL v3)"),
    "es": ("ConnectFour Studio para Android\n"
           "Motor: BitBully by Markus Thill\n"
           "Adaptación a Kotlin del núcleo C++ (bitbully 0.0.79)\n"
           "Búsqueda: profundización iterativa, MTD(f)/null-window,\n"
           "bitboards, tabla de transposición, libro 12-ply-dist\n"
           "GUI: Kotlin + framework de Android (sin bibliotecas adicionales)\n"
           "Idea y pruebas: Patrick Götz, implementación: IA\n"
           "Código abierto (GNU AGPL v3)"),
    "fr": ("ConnectFour Studio pour Android\n"
           "Moteur : BitBully by Markus Thill\n"
           "Portage en Kotlin du noyau C++ (bitbully 0.0.79)\n"
           "Recherche : approfondissement itératif, MTD(f)/null-window,\n"
           "bitboards, table de transposition, livre 12-ply-dist\n"
           "GUI : Kotlin + framework Android (sans bibliothèque supplémentaire)\n"
           "Idée et tests : Patrick Götz, réalisation : IA\n"
           "Logiciel libre (GNU AGPL v3)"),
    "nl": ("ConnectFour Studio voor Android\n"
           "Engine: BitBully by Markus Thill\n"
           "Kotlin-port van de C++-kern (bitbully 0.0.79)\n"
           "Zoeken: iteratieve verdieping, MTD(f)/null-window,\n"
           "bitboards, transpositietabel, 12-ply-dist book\n"
           "GUI: Kotlin + Android-framework (zonder extra bibliotheken)\n"
           "Idee en tests: Patrick Götz, uitvoering: AI\n"
           "Open source (GNU AGPL v3)"),
    "it": ("ConnectFour Studio per Android\n"
           "Motore: BitBully by Markus Thill\n"
           "Port in Kotlin del nucleo C++ (bitbully 0.0.79)\n"
           "Ricerca: approfondimento iterativo, MTD(f)/null-window,\n"
           "bitboard, tabella di trasposizione, libro 12-ply-dist\n"
           "GUI: Kotlin + framework Android (senza librerie aggiuntive)\n"
           "Idea e test: Patrick Götz, realizzazione: IA\n"
           "Software libero (GNU AGPL v3)"),
}

# --------------------------------------------------------------------------
# Help: replacements of desktop-only facts (each must match at least once)
# --------------------------------------------------------------------------
HELP_REPLACE = {
    "de": [
        (" im Benutzerordner (~/.config/connectfour-studio-qt bzw. CFS_USER_DIR). ",
         " im internen Speicher der App. "),
        ("(Echtzeit-Rückmeldung des C++-Kerns mit lesbaren Tausendertrennpunkten)",
         "(Echtzeit-Rückmeldung der Engine mit lesbaren Tausendertrennpunkten)"),
        (" – ein effizientes Python-Modul mit C++-Kern. ",
         " – hier als Kotlin-Portierung des C++-Kerns (bitbully 0.0.79) mit identischen Suchergebnissen. "),
        ("ConnectFour Studio verbindet eine schlanke Desktop-Oberfläche (Python, Qt 6/PySide6, "
         "Grafik-Rendering über Pillow) mit einem gelösten Spiel. ",
         "ConnectFour Studio für Android verbindet eine schlanke Oberfläche (Kotlin, reines "
         "Android-Framework ohne Zusatzbibliotheken) mit einem gelösten Spiel. "),
    ],
    "en": [
        (" in the user folder (~/.config/connectfour-studio-qt or CFS_USER_DIR), without an intermediate dialog. ",
         " in the app's internal storage, without an intermediate dialog. "),
        ("(real-time feedback from the C++ core, with readable thousands separators)",
         "(real-time feedback from the engine, with readable thousands separators)"),
        (" – an efficient Python module with a C++ core. ",
         " – here as a Kotlin port of the C++ core (bitbully 0.0.79) with identical search results. "),
        ("ConnectFour Studio combines a lean desktop interface (Python, Qt 6/PySide6, "
         "graphics rendering with Pillow) with a solved game. ",
         "ConnectFour Studio for Android combines a lean interface (Kotlin, plain Android "
         "framework without extra libraries) with a solved game. "),
    ],
    "es": [
        (" de la carpeta de usuario (~/.config/connectfour-studio-qt o CFS_USER_DIR), sin diálogo intermedio. ",
         " del almacenamiento interno de la app, sin diálogo intermedio. "),
        ("(información en tiempo real del núcleo C++, con separadores de millares legibles)",
         "(información en tiempo real del motor, con separadores de millares legibles)"),
        (", un módulo de Python eficiente con núcleo en C++. ",
         ", aquí como adaptación a Kotlin del núcleo C++ (bitbully 0.0.79) con resultados de búsqueda idénticos. "),
        ("ConnectFour Studio combina una interfaz de escritorio ligera (Python, Qt 6/PySide6, "
         "renderizado gráfico con Pillow) con un juego resuelto. ",
         "ConnectFour Studio para Android combina una interfaz ligera (Kotlin, framework de "
         "Android puro sin bibliotecas adicionales) con un juego resuelto. "),
    ],
    "fr": [
        (" du dossier utilisateur (~/.config/connectfour-studio-qt ou CFS_USER_DIR), sans boîte de dialogue intermédiaire. ",
         " de la mémoire interne de l'application, sans boîte de dialogue intermédiaire. "),
        ("(retour en temps réel du noyau C++, avec des séparateurs de milliers lisibles)",
         "(retour en temps réel du moteur, avec des séparateurs de milliers lisibles)"),
        (", un module Python efficace avec un noyau C++. ",
         ", ici sous forme de portage en Kotlin du noyau C++ (bitbully 0.0.79) aux résultats de recherche identiques. "),
        ("ConnectFour Studio associe une interface de bureau légère (Python, Qt 6/PySide6, "
         "rendu graphique avec Pillow) à un jeu résolu. ",
         "ConnectFour Studio pour Android associe une interface légère (Kotlin, framework "
         "Android pur sans bibliothèque supplémentaire) à un jeu résolu. "),
    ],
    "nl": [
        (" in de gebruikersmap (~/.config/connectfour-studio-qt of CFS_USER_DIR), zonder tussenliggende dialoog. ",
         " in de interne opslag van de app, zonder tussenliggende dialoog. "),
        ("(realtime terugmelding van de C++-kern, met leesbare duizendtalscheiding)",
         "(realtime terugmelding van de engine, met leesbare duizendtalscheiding)"),
        (" – een efficiënte Python-module met een C++-kern. ",
         " – hier als Kotlin-port van de C++-kern (bitbully 0.0.79) met identieke zoekresultaten. "),
        ("ConnectFour Studio combineert een slanke desktopinterface (Python, Qt 6/PySide6, "
         "grafische weergave met Pillow) met een opgelost spel. ",
         "ConnectFour Studio voor Android combineert een slanke interface (Kotlin, puur "
         "Android-framework zonder extra bibliotheken) met een opgelost spel. "),
    ],
    "it": [
        (" nella cartella utente (~/.config/connectfour-studio-qt o CFS_USER_DIR), senza finestra intermedia. ",
         " nella memoria interna dell'app, senza finestra intermedia. "),
        ("(feedback in tempo reale dal nucleo C++, con separatori delle migliaia leggibili)",
         "(feedback in tempo reale dal motore, con separatori delle migliaia leggibili)"),
        (" – un efficiente modulo Python con nucleo C++. ",
         " – qui come port in Kotlin del nucleo C++ (bitbully 0.0.79) con risultati di ricerca identici. "),
        ("ConnectFour Studio combina un'interfaccia desktop snella (Python, Qt 6/PySide6, "
         "resa grafica con Pillow) con un gioco risolto. ",
         "ConnectFour Studio per Android combina un'interfaccia snella (Kotlin, puro framework "
         "Android senza librerie aggiuntive) con un gioco risolto. "),
    ],
}

# New help section "Android" (inserted after the table of contents).
HELP_ANDROID = {
    "de": ("Bedienung auf Android", [
        "Diese Hilfe beschreibt alle Funktionen von ConnectFour Studio. Auf Smartphone und "
        "Tablet gilt sinngemäß „Tippen“ statt „Klicken“: Ein Fingertipp auf eine Spalte des "
        "Brettes oder auf ein Feld der Wertungszeile wirft dort einen Stein ein. Solange der "
        "Finger auf dem Brett liegt, zeigt der Ghost-Stein die Landeposition; gezogen wird beim "
        "Loslassen. Wer den Finger aus dem Brett hinausschiebt, bricht den Zug ab.",
        "Die Menüs Datei, Ansicht, Einstellungen, Kommandos und Hilfe öffnen sich über das "
        "Drei-Punkte-Menü (⋮) oben rechts. Computer-Stufe, Stein-Set und Sprache werden dort "
        "in einer Auswahlliste gewählt. Das Computer-Computer Match hat einen eigenen "
        "Bildschirm; mit „Zurück“ geht es zum Brett, das Match läuft weiter.",
        "Laden und Speichern von Stellungen nutzt die Dateiauswahl des Systems (Gerätespeicher, "
        "SD-Karte, Cloud-Anbieter); die .4gp-Dateien sind mit der Desktop-Version austauschbar. "
        "Schnellspeicher, Einstellungen und Sprache liegen im internen Speicher der App.",
        "Im Hochformat stehen Am Zuge, Info-Feld und Spielstand unter dem Brett, im Querformat "
        "rechts daneben; die Statuszeile steht immer unten. Mit Tastatur (Tablet, Chromebook) "
        "gelten alle Tastenkürzel, eine Maus zeigt den Ghost-Stein beim Überfahren und das "
        "Mausrad über dem Brett wechselt das Stein-Set. Partie und Analyse bleiben beim Drehen "
        "des Geräts und im Hintergrund erhalten.",
        "Schneller als am Desktop: Für alle Stellungen mit bis zu zwei Steinen sind die exakten Bewertungen in einem kleinen eingebauten Buch gespeichert – Analyse und Computerzug sind dort sofort fertig (Tiefe und Quelle: „Buch 2d“). Bis zwölf Steine endet die Suche, sobald alle Varianten das 12-ply-Buch erreichen (Tiefe: „Buch 12d“); die Bewertungen sind dieselben wie bei der Vollsuche."]),
    "en": ("Using ConnectFour Studio on Android", [
        "This help describes all functions of ConnectFour Studio. On phones and tablets, read "
        "“tap” for “click”: tapping a column of the board or a field of the evaluation row drops "
        "a stone there. While your finger rests on the board, the ghost stone shows where the "
        "stone will land; the move is made when you lift the finger. Sliding the finger off the "
        "board cancels the move.",
        "The File, View, Settings, Commands and Help menus open from the three-dot menu (⋮) at "
        "the top right. Computer level, stone set and language are chosen there from a list. The "
        "computer-computer match has its own screen; “Back” returns to the board while the match "
        "keeps running.",
        "Loading and saving positions uses the system file picker (device storage, SD card, "
        "cloud providers); .4gp files are interchangeable with the desktop version. Quick save, "
        "settings and language are kept in the app's internal storage.",
        "In portrait orientation the To Move, Info and Score boxes are below the board, in "
        "landscape to its right; the status bar is always at the bottom. With a keyboard "
        "(tablet, Chromebook) all keyboard shortcuts work, a mouse shows the ghost stone when "
        "hovering and the mouse wheel over the board changes the stone set. Game and analysis "
        "are kept when the device is rotated and while the app is in the background.",
        "Faster than on the desktop: for all positions with up to two stones the exact evaluations are stored in a small built-in book, so analysis and computer moves are instant there (depth and source: “Book 2d”). Up to twelve stones the search stops as soon as every line reaches the 12-ply book (depth: “Book 12d”); the evaluations are the same as with the full search."]),
    "fr": ("Utilisation sur Android", [
        "Cette aide décrit toutes les fonctions de ConnectFour Studio. Sur smartphone et "
        "tablette, lisez « toucher » au lieu de « cliquer » : toucher une colonne du plateau ou "
        "une case de la ligne d'évaluation y fait tomber un pion. Tant que le doigt reste sur le "
        "plateau, le pion fantôme indique où le pion atterrira ; le coup est joué quand le doigt "
        "se lève. Faire glisser le doigt hors du plateau annule le coup.",
        "Les menus Fichier, Affichage, Paramètres, Commandes et Aide s'ouvrent depuis le menu à "
        "trois points (⋮) en haut à droite. Le niveau de l'ordinateur, le jeu de pions et la "
        "langue y sont choisis dans une liste. Le match Ordinateur-Ordinateur a son propre "
        "écran ; « Retour » revient au plateau pendant que le match continue.",
        "Le chargement et l'enregistrement des positions utilisent le sélecteur de fichiers du "
        "système (mémoire de l'appareil, carte SD, services cloud) ; les fichiers .4gp sont "
        "compatibles avec la version de bureau. Sauvegarde rapide, paramètres et langue sont "
        "conservés dans la mémoire interne de l'application.",
        "En mode portrait, les cadres Au trait, Info et Score se trouvent sous le plateau, en "
        "mode paysage à sa droite ; la barre d'état est toujours en bas. Avec un clavier "
        "(tablette, Chromebook), tous les raccourcis clavier fonctionnent ; une souris affiche "
        "le pion fantôme au survol et la molette au-dessus du plateau change de jeu de pions. "
        "La partie et l'analyse sont conservées lors de la rotation de l'appareil et en "
        "arrière-plan.",
        "Plus rapide que sur ordinateur : pour toutes les positions comptant jusqu'à deux pions, les évaluations exactes sont enregistrées dans un petit livre intégré ; l'analyse et le coup de l'ordinateur y sont immédiats (profondeur et source : « Livre 2d »). Jusqu'à douze pions, la recherche s'arrête dès que toutes les variantes atteignent le livre 12-ply (profondeur : « Livre 12d ») ; les évaluations sont les mêmes qu'avec la recherche complète."]),
    "es": ("Uso en Android", [
        "Esta ayuda describe todas las funciones de ConnectFour Studio. En móviles y tabletas, "
        "lea «tocar» en lugar de «hacer clic»: tocar una columna del tablero o una casilla de "
        "la fila de evaluación deja caer una ficha allí. Mientras el dedo permanece sobre el "
        "tablero, la ficha fantasma muestra dónde caerá; la jugada se realiza al levantar el "
        "dedo. Deslizar el dedo fuera del tablero cancela la jugada.",
        "Los menús Archivo, Ver, Ajustes, Comandos y Ayuda se abren desde el menú de tres "
        "puntos (⋮) arriba a la derecha. El nivel del ordenador, el juego de fichas y el idioma "
        "se eligen allí de una lista. El partido Ordenador-Ordenador tiene su propia pantalla; "
        "«Atrás» vuelve al tablero mientras el partido continúa.",
        "Cargar y guardar posiciones usa el selector de archivos del sistema (almacenamiento "
        "del dispositivo, tarjeta SD, servicios en la nube); los archivos .4gp son compatibles "
        "con la versión de escritorio. El guardado rápido, los ajustes y el idioma se guardan "
        "en el almacenamiento interno de la app.",
        "En vertical, los recuadros Turno, Info y Marcador están debajo del tablero; en "
        "horizontal, a su derecha; la barra de estado está siempre abajo. Con teclado (tableta, "
        "Chromebook) funcionan todos los atajos de teclado; un ratón muestra la ficha fantasma "
        "al pasar por encima y la rueda sobre el tablero cambia el juego de fichas. La partida "
        "y el análisis se conservan al girar el dispositivo y en segundo plano.",
        "Más rápido que en el escritorio: para todas las posiciones con hasta dos fichas, las evaluaciones exactas están guardadas en un pequeño libro integrado, así que el análisis y la jugada del ordenador son inmediatos (profundidad y fuente: «Libro 2d»). Hasta doce fichas, la búsqueda termina en cuanto todas las variantes alcanzan el libro 12-ply (profundidad: «Libro 12d»); las evaluaciones son las mismas que con la búsqueda completa."]),
    "nl": ("Bediening op Android", [
        "Deze help beschrijft alle functies van ConnectFour Studio. Op telefoon en tablet geldt "
        "„tikken” in plaats van „klikken”: tikken op een kolom van het bord of op een vak van de "
        "beoordelingsrij laat daar een steen vallen. Zolang de vinger op het bord ligt, toont de "
        "ghost-steen waar de steen landt; de zet wordt gedaan bij het loslaten. Wie de vinger "
        "van het bord schuift, breekt de zet af.",
        "De menu's Bestand, Weergave, Instellingen, Commando's en Help openen via het "
        "driepuntsmenu (⋮) rechtsboven. Computerniveau, stenenset en taal worden daar uit een "
        "lijst gekozen. De computer-computerwedstrijd heeft een eigen scherm; „Terug” gaat naar "
        "het bord terwijl de wedstrijd doorloopt.",
        "Stellingen laden en opslaan gebruikt de bestandskiezer van het systeem (apparaatopslag, "
        "SD-kaart, cloudaanbieders); .4gp-bestanden zijn uitwisselbaar met de desktopversie. "
        "Snel opslaan, instellingen en taal staan in de interne opslag van de app.",
        "In staande stand staan Aan zet, Info en Stand onder het bord, liggend rechts ernaast; "
        "de statusregel staat altijd onderaan. Met een toetsenbord (tablet, Chromebook) werken "
        "alle sneltoetsen, een muis toont de ghost-steen bij aanwijzen en het muiswiel boven het "
        "bord wisselt de stenenset. Partij en analyse blijven behouden bij het draaien van het "
        "apparaat en op de achtergrond.",
        "Sneller dan op de desktop: voor alle stellingen met maximaal twee stenen staan de exacte beoordelingen in een klein ingebouwd boek, zodat analyse en computerzet daar direct klaar zijn (diepte en bron: „Boek 2d”). Tot twaalf stenen stopt de zoektocht zodra alle varianten het 12-ply-boek bereiken (diepte: „Boek 12d”); de beoordelingen zijn dezelfde als bij de volledige zoektocht."]),
    "it": ("Uso su Android", [
        "Questa guida descrive tutte le funzioni di ConnectFour Studio. Su smartphone e tablet "
        "si legga «toccare» invece di «fare clic»: toccando una colonna della scacchiera o una "
        "casella della riga di valutazione vi si fa cadere una pedina. Finché il dito resta "
        "sulla scacchiera, la pedina fantasma mostra dove atterrerà; la mossa avviene quando si "
        "solleva il dito. Trascinando il dito fuori dalla scacchiera la mossa viene annullata.",
        "I menu File, Visualizza, Impostazioni, Comandi e Aiuto si aprono dal menu a tre punti "
        "(⋮) in alto a destra. Livello del computer, set di pedine e lingua si scelgono lì da un "
        "elenco. La partita Computer-Computer ha una schermata propria; «Indietro» torna alla "
        "scacchiera mentre la partita continua.",
        "Il caricamento e il salvataggio delle posizioni usano il selettore di file del sistema "
        "(memoria del dispositivo, scheda SD, servizi cloud); i file .4gp sono compatibili con "
        "la versione desktop. Salvataggio rapido, impostazioni e lingua sono conservati nella "
        "memoria interna dell'app.",
        "In verticale i riquadri Al tratto, Info e Punteggio stanno sotto la scacchiera, in "
        "orizzontale alla sua destra; la barra di stato è sempre in basso. Con una tastiera "
        "(tablet, Chromebook) funzionano tutte le scorciatoie; un mouse mostra la pedina "
        "fantasma al passaggio e la rotella sopra la scacchiera cambia il set di pedine. Partita "
        "e analisi restano conservate ruotando il dispositivo e in background.",
        "Più veloce che sul desktop: per tutte le posizioni con al massimo due pedine le valutazioni esatte sono memorizzate in un piccolo libro integrato, quindi analisi e mossa del computer sono immediate (profondità e fonte: «Libro 2d»). Fino a dodici pedine la ricerca si ferma non appena tutte le varianti raggiungono il libro 12-ply (profondità: «Libro 12d»); le valutazioni sono le stesse della ricerca completa."]),
}


# --------------------------------------------------------------------------
def android_escape(text):
    """Escape a plain string for strings.xml."""
    s = escape(text)
    s = s.replace("\\", "\\\\").replace("'", "\\'").replace('"', '\\"')
    s = s.replace("\n", "\\n")
    if s.startswith("@") or s.startswith("?"):
        s = "\\" + s
    return s


def write_strings(lang, items):
    d = "values" if lang == DEFAULT else f"values-{lang}"
    os.makedirs(os.path.join(RES, d), exist_ok=True)
    out = ['<?xml version="1.0" encoding="utf-8"?>',
           "<!-- Generated by scripts/gen_resources.py from cfs_core.lang - do not edit. -->",
           '<resources xmlns:tools="http://schemas.android.com/tools">']
    for name, value, translatable in items:
        attrs = ""
        if "%" in value:
            attrs += ' formatted="false"'
        if not translatable:
            attrs += ' translatable="false"'
        out.append(f'    <string name="{name}"{attrs}>{android_escape(value)}</string>')
    out.append("</resources>")
    with open(os.path.join(RES, d, "strings.xml"), "w", encoding="utf-8") as f:
        f.write("\n".join(out) + "\n")


def gen_strings(lang_mod, levels_mod, sets_mod, hc):
    keys = list(lang_mod.STRINGS["de"])
    for k in keys:
        if not re.fullmatch(r"[a-z][a-z0-9_]*", k):
            raise SystemExit(f"bad key {k}")
    for code in LANGS:
        items = [("app_name", "ConnectFour Studio", False)] if code == DEFAULT else []
        for k in keys:
            v = lang_mod.STRINGS[code][k]
            # Only format spec used: {score:+d}; the app passes "+5" itself.
            v = v.replace("{score:+d}", "{score}")
            items.append((k, v, True))
        for key in levels_mod.STUFEN_ORDER:
            items.append((f"level_{key}", lang_mod.level_name(key, code), True))
        for no in range(1, 21):
            items.append((f"set_name_{no}", sets_mod.set_display_name(no, code), True))
        for key in ("copy", "close", "help_title", "find_label", "not_found", "find_btn"):
            items.append((f"help_ui_{key}", hc.ui_text(key, code), True))
        items.append(("info_text", INFO_TEXT[code], True))
        for k, tr in ANDROID_STRINGS.items():
            items.append((k, tr[code], True))
        write_strings(code, items)
    # Kotlin key -> resource map (the core asks for texts by key).
    names = keys + [f"level_{k}" for k in levels_mod.STUFEN_ORDER] + \
        [f"set_name_{n}" for n in range(1, 21)] + \
        [f"help_ui_{k}" for k in ("copy", "close", "help_title", "find_label", "not_found", "find_btn")] + \
        ["info_text"] + list(ANDROID_STRINGS)
    kt = ["// Generated by scripts/gen_resources.py - do not edit.",
          "package io.github.patrickric.connectfourstudio", "",
          "/** String key of cfs_core.lang -> Android string resource. */",
          "internal object StringKeys {",
          "    val MAP: Map<String, Int> = hashMapOf("]
    for n in names:
        kt.append(f'        "{n}" to R.string.{n},')
    kt += ["    )", "}", ""]
    p = os.path.join(ROOT, "app", "src", "main", "kotlin", "io", "github", "patrickric",
                     "connectfourstudio", "StringKeys.kt")
    with open(p, "w", encoding="utf-8") as f:
        f.write("\n".join(kt))


def gen_help(hc):
    for code in LANGS:
        items = hc.HELP_CONTENT[code]
        repl = list(HELP_REPLACE[code])
        used = [0] * len(repl)

        def fix(text):
            for i, (old, new) in enumerate(repl):
                if old in text:
                    text = text.replace(old, new)
                    used[i] += 1
            return text

        out = []
        for kind, *rest in items:
            if kind in ("head", "sub"):
                out.append({"t": kind, "id": rest[0], "text": rest[1]})
            elif kind == "para":
                parts = []
                for p in rest:
                    if isinstance(p, tuple) and len(p) == 3 and p[0] == "LINK":
                        parts.append({"l": fix(p[1]), "to": p[2]})
                    elif isinstance(p, tuple) and len(p) == 2:
                        parts.append({"b": fix(p[0])})
                    else:
                        parts.append({"s": fix(str(p))})
                out.append({"t": "para", "parts": parts})
            elif kind == "mono":
                out.append({"t": "mono", "text": rest[0]})
            elif kind == "table" and rest and rest[0] == "kreuz14":
                out.append({"t": "mono", "text": hc.kreuz_block([1, 2, 3, 4, 5, 6, 7], code)})
                out.append({"t": "mono", "text": hc.kreuz_block([8, 9, 10, 11, 12, 13, 14], code)})
            else:
                raise SystemExit(f"unknown help item {kind}")
        if not all(used):
            missing = [repl[i][0] for i, u in enumerate(used) if not u]
            raise SystemExit(f"{code}: help replacement not applied: {missing}")
        # Android section after the table of contents (+ link in the TOC).
        title, paras = HELP_ANDROID[code]
        assert out[0]["t"] == "head" and out[1]["t"] == "para"
        out[1]["parts"] = [{"l": title, "to": "android"}, {"s": " · "}] + out[1]["parts"]
        section = [{"t": "head", "id": "android", "text": title}]
        section += [{"t": "para", "parts": [{"s": p}]} for p in paras]
        out = out[:2] + section + out[2:]
        d = "raw" if code == DEFAULT else f"raw-{code}"
        os.makedirs(os.path.join(RES, d), exist_ok=True)
        with open(os.path.join(RES, d, "help.json"), "w", encoding="utf-8") as f:
            json.dump(out, f, ensure_ascii=False, separators=(",", ":"))


def psnr(a, b):
    import math
    from PIL import ImageChops, ImageStat
    st = ImageStat.Stat(ImageChops.difference(a, b))
    mse = sum(v * v for v in st.rms) / 3
    return 10 * math.log10(255 * 255 / max(mse, 1e-9))


def gen_sets(sets_mod):
    from PIL import Image
    raw = sets_mod.SetArt.load_all()
    order = sets_mod.set_menu_order(raw)
    meta = {"order": order, "sets": {}}
    for s in order:
        d = raw[s]
        meta["sets"][str(s)] = {"edge": d["edge"], "stone_r_y": round(d["stone_r_y"], 6),
                                "stone_r_r": round(d["stone_r_r"], 6),
                                "stone_r": round(d["stone_r"], 6)}
        od = os.path.join(ASSETS, "sets", f"set{s}")
        os.makedirs(od, exist_ok=True)
        for name in ("back", "red", "yellow"):
            im = d[name].convert("RGB")
            path = os.path.join(od, f"{name}.webp")
            im.save(path, "WEBP", quality=WEBP_QUALITY, method=6)
            if psnr(im, Image.open(path).convert("RGB")) < PSNR_MIN:
                im.save(path, "WEBP", lossless=True, quality=100, method=6)
    with open(os.path.join(ASSETS, "sets", "sets.json"), "w") as f:
        json.dump(meta, f, indent=1, sort_keys=True)


def main(qt_dir):
    sys.path.insert(0, qt_dir)
    from cfs_core import help_content as hc
    from cfs_core import lang as lang_mod
    from cfs_core import levels as levels_mod
    from cfs_core import sets as sets_mod
    gen_strings(lang_mod, levels_mod, sets_mod, hc)
    gen_help(hc)
    gen_sets(sets_mod)
    print("resources generated")


if __name__ == "__main__":
    if len(sys.argv) != 2:
        raise SystemExit(__doc__)
    main(sys.argv[1])
