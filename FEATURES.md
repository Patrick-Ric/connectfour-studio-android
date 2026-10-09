# ConnectFour Studio – Feature-Liste (Qt-Version → Android)

Grundlage: `FEATURES.md` der Qt-Version (Stand 08.10.2026) einschließlich der
dort dokumentierten Abweichungen A1–A9 von der Tk-Version, die unverändert
übernommen sind. Diese Liste markiert für jeden Punkt, was auf Android gilt.

Status:
`[x]` übernommen (gleiches Verhalten) ·
`[~]` angepasst (Begründung dahinter, Kennung **M1 …** → Abschnitt „Mobile Anpassungen“) ·
`[-]` weggelassen (mit Begründung).
Kürzel: (T) = JUnit-Test im Modul `core`, (E) = auf dem Emulator geprüft
(API 21 und API 24, siehe README „Testing on a device“).

## 1. Hauptfenster / Layout

- [~] Fenstertitel „ConnectFour Studio“ – als Titel der Aktionsleiste (E)
- [~] Brett links, Brett klebt oben – Hochformat: Brett oben über die ganze Breite; Querformat: Brett links, Höhe füllend (M1) (E)
- [x] Wertungszeile direkt unter dem Brett, pixelgenau unter den Spalten, 1-px-Trennlinien; Höhe 44 dp statt 44 px (E)
- [x] Wertungszeile ohne Analyse: Spaltennummern 1–7 (graue Felder), volle Spalte „X“ (E)
- [x] Wertungszeile mit Analyse: oben fett `+`/`=`/`-`, darunter Steinzahl bis Ende; grün/gelb/rot (Pastell) (E)
- [x] Tippen in die Wertungszeile führt den Spaltenzug aus (4-dp-Lücke dazwischen ignoriert)
- [x] Buttonleiste unter dem Brett: genau 7 gleich breite Buttons (Neu, <<, <, >, >>, Ziehen, Analyse), exakt Brettbreite; Schrift verkleinert sich bei langen Übersetzungen (E)
- [x] Feld „Am Zuge“ (Stein-Icon + Name: Mensch / Stufenname inkl. (p,s,w) / Match-Seite) (E)
- [x] Feld „Info“: Zug, Stufe, Tiefe, Wert, Knoten, Zeit, Tempo, Quelle – A9 übernommen (E)
- [x] Feld „Spielstand“ (immer sichtbar): Kopf, großes Ergebnis, (+/=/-)·Partien, Elo ab ≥0,5 Punkten je Seite, Buttons An/Aus + Reset (T)
- [x] Statuszeile unten (eingesunken), Start „Bereit.“ – beim allerersten Start zuerst „Eröffnungsbuch wird vorbereitet …“ (M6) (E)
- [~] Auto-Zoom mit der Fenstergröße – Zellgröße folgt Bildschirm und Ausrichtung, Info-Felder scrollen bei Bedarf (M1); feste „natürliche Startgröße“ entfällt, weil es keine frei skalierbaren Fenster gibt
- [~] Startargument `.4gp` – ersetzt durch „Stellung laden…“ über die Dateiauswahl (M4); ein Öffnen-mit-Intent ist nicht nötig, da .4gp keinen MIME-Typ hat

## 2. Brett-Darstellung / Animationen

- [~] Kacheln aus HiRes-Bildern – als WebP (q95 bzw. verlustfrei, optisch verlustfrei), Skalierung mit Halbierungsstufen + bilinear statt Lanczos (M7) (E)
- [~] Ghost-Stein – mit Maus beim Überfahren wie am Desktop; per Finger, solange der Finger auf dem Brett liegt (M2) (E)
- [~] Drop-Animation – Stein fällt flüssig mit Beschleunigung (6 Reihen ≈ 0,2 s) statt zeilenweise 16 ms; A1 (Zielfeld bleibt leer, Eingaben während der Animation ignoriert) übernommen (M3) (E)
- [x] Letzter Zug: weißer Ring knapp außerhalb der Steinkante (Radius je Farbe vermessen) (E)
- [x] Gewinnreihe(n): grüner Außen- + weißer Innenring (E)
- [x] Stein-Radien je Set vermessen – Werte werden zur Bauzeit mit dem Original-Algorithmus aus `cfs_core.sets` berechnet (`assets/sets/sets.json`)

## 3. Menü „Datei“ (Drei-Punkte-Menü → Datei)

- [x] Neues Spiel
- [x] Neu mit Zufallsstellung… (Dialog) (E)
- [~] Stellung laden… / speichern… (.4gp) – über das Storage Access Framework, byte-kompatibel zur Desktop-Version (M4) (T)
- [x] Schnell speichern (F3) / Schnell laden (F4) – Datei `quicksave.4gp` im internen App-Speicher; Meldung, wenn nichts gespeichert (E)
- [~] Ende – beendet die Activity und stoppt Engine/Match; der Prozess bleibt Android überlassen

## 4. Menü „Ansicht“

- [x] Ghost-Stein, Drop-Animation, letzten Zug zeigen (Haken, Standard an, gemerkt – A5)
- [x] Spielstand ein/aus (Haken, synchron mit Button An/Aus), Spielstand reset
- [~] Neu: „Großes Brett“ (Haken, Standard aus, gemerkt) – Brett und Buttons ohne Seitenrand über die volle Breite, im Hochformat bis 82 % statt 70 % der Höhe (M1)

## 5. Menü „Einstellungen“

- [~] Computer-Stufe – Auswahlliste im Dialog statt Untermenü (Android erlaubt keine Untermenüs im Untermenü) (M5) (E)
- [x] Mensch-Computer / 2-Spieler / Computer-Computer (ausspielen) als Radio-Gruppe
- [~] Computer-Computer Match… – eigener Bildschirm statt nicht-modalem Fenster (M8) (E)
- [x] Stop Auto Play
- [x] vorheriges Set / nächstes Set
- [~] 20 Stein-Sets – Auswahlliste „Stein-Set N – Name“ im Dialog (M5)

## 6. Menü „Kommandos“

- [x] erster Zug, Zug zurück, Zug vor, letzter Zug
- [x] ziehen (Computer) – auch bei leerem Brett (Computer eröffnet, Mensch wird Rot)
- [~] alle Züge bewerten (1x) (F6) – Umschalter wie A2, rechnet aber im Hintergrund mit Live-Anzeige statt synchron im GUI-Thread (M6) (E)
- [x] Dauer-Analyse (F7) – Umschalter, synchron mit Button „Analyse“ (E)

## 7. Menü „Hilfe“

- [~] Inhalt (F1) – eigene Hilfe-Activity (M9) (E)
- [x] Info – Dialog mit kopierbarem Text (Kopieren/Schließen)
- [~] Sprache – Auswahlliste mit den 6 Sprachen plus „Systemsprache“, Wahl wird gemerkt (M10) (E)

## 8. Tastenkürzel (mit Hardware-Tastatur, z. B. Tablet/Chromebook)

- [x] 1–7 Spaltenzug, Pfeil links/rechts, Pfeil hoch/runter, Bild hoch/runter (Wrap-around)
- [x] Mausrad über dem Brett wechselt das Set
- [x] F1, F3/F4, F5, F6, F7; F10 neutralisiert
- [~] Escape – schließt Menüs/Dialoge (Android-Standard: Zurück-Taste) – A8 sinngemäß
- [-] Anzeige der Kürzel im Menü („Text\tKürzel“) – das Android-Optionsmenü kann auf API 21 keine Tastenbeschriftungen anzeigen; die Kürzel stehen in der Hilfe (Abschnitt „Tastenkürzel“)

## 9. Spielmodi / Ablauf

- [x] Mensch-Computer: nach Menschenzug antwortet der Computer automatisch (E)
- [x] Zugsperre während der Computer denkt („Computer denkt noch – bitte warten.“)
- [x] Navigation gesperrt während Denken/Selbstspiel/Match (mit Statusmeldung)
- [x] Ergebnis eines Computerzugs wird verworfen, wenn sich die Stellung geändert hat
- [~] Stop bricht Engine-Zug, Selbstspiel, Match und Analyse ab – zusätzlich innerhalb einer Suchtiefe (alle 1024 Knoten geprüft), nicht erst zwischen den Tiefen (M6) (T)
- [x] Neues Spiel / Laden während des Denkens stoppt zuerst
- [x] Partieende: Statuszeile „Spielende: …“, Wert-Feld ebenso (E)
- [x] Selbstspiel endet am Partieende automatisch (Modus zurück auf Mensch-Computer)

## 10. KI / Stufen (BitBully-Engine)

- [~] Engine BitBully – als 1:1-Kotlin-Portierung von bitbully 0.0.79 (C++-Kern): gleiche Scores **und** Knotenzahlen je Iterationstiefe wie die Python/C++-Engine (M11) (T)
- [x] Eröffnungsbuch fest `12-ply-dist` (bitbully-databases 0.0.2), Werte identisch (T)
- [x] Iterative Vertiefung 4, 6, 8 … 20, Voll; Live-Anzeige Tiefe/Knoten/Zeit/Tempo (T, E)
- [x] 14 Stufen (p, s, w) + 0 Verlierer + Zufall-Sonderfall (T)
- [x] Siegsschutz w, Verlustschutz s, Perfekt-Regeln, Verluststellungen (gewichtete Wahl Verlustlänge^8) (T)
- [x] Analyse (F6/F7) immer perfekt
- [x] Info „Quelle“: „Buch 12d“ bis 12 Steine, danach „berechnet“; „Tiefe“: letzte Iterationstiefe (T)
- [~] Transpositionstabelle 2^22 Einträge wie C++, auf Geräten mit wenig Heap 2^21/2^20 (exakte Ergebnisse unverändert, nur andere Knotenzahlen) (M11)

## 11. Dauer-Analyse

- [x] Hintergrundanalyse nach jedem Stellungswechsel, neueste Stellung gewinnt (E)
- [x] Live-Näherung je Tiefe, Status „Analyse: Spalte X (+n) in t s.“ – A4 übernommen; Fortschritt kommt tatsächlich live (M6)
- [x] Veraltete Ergebnisse werden verworfen (Sequenznummer + Stellungs-Snapshot)

## 12. Dialog „Neu mit Zufallsstellung“

- [x] Steine 1–9 (Standard 3, NumberPicker), Ergebnis Egal/Gewinn/Unentschieden/Verlust (Standard Gewinn), Hinweis grau (T, E)
- [x] Suche im Hintergrund (bis 400 Kandidaten, Prüfung per MTD(f)), Status „Suche Zufallsstellung …“ (T)
- [x] Danach: Mensch spielt die Farbe des nächsten Zugs, Stellung wird sofort bewertet

## 13. Computer-Computer Match

- [~] Nicht-modales Fenster – eigener Bildschirm; „Zurück“/„Schließen“ lässt das Match weiterlaufen (M8) (E)
- [x] Gelb/Rot: Mensch, Stufen 0–14, User (1)/(2) (eigene p,s,w) (E)
- [x] Partien 1–10000 (Standard 20), Farbwechsel (Standard an), Tempo Normal/Schnell/Nur Ergebnisse
- [x] User-(p,s,w)-Felder nur aktiv, wenn der jeweilige User-Key gewählt ist; Werte werden zusätzlich gemerkt (A5 sinngemäß)
- [x] Hinweistext, Live-Stand, kopierbares Endergebnis, Buttons Start/Stop/Kopieren/Schließen
- [x] Mit Mensch-Seite: kein Turbo, Mensch zieht per Tippen/Taste, 3 s Pause zwischen Partien
- [x] Statuszeile „Computer-Computer Match n/N | …“, Matchende: Ergebnis bleibt in der Statuszeile (A3) (T)
- [x] Punktezählung farbwechsel-sicher; Elo-Formel identisch (T)

## 14. Spielstand (Mensch vs. Computer)

- [x] Standard aus; An/Aus aktiviert 0-0 gegen aktuelle Stufe (T)
- [x] Zählt normale Partien und Match-Partien mit Mensch-Seite (Zuletzt-Zieher-Regel) (T)
- [x] Stufenwechsel setzt den Stand der neuen Stufe auf 0-0 (T)

## 15. Hilfe

- [~] Eigene Activity statt 660×560-Fenster, Titel lokalisiert (M9) (E)
- [x] Inhaltsverzeichnis mit Links, Querverweise, Sprung zu Abschnitten (E)
- [x] Überschriften blau/fett, Unterüberschriften, Fettdruck, Festschrift-Tabellen (Spielstärke, Kreuztabelle in zwei Blöcken – horizontal scrollbar)
- [x] Stein-Icons (gelb/rot) des aktuellen Sets oben
- [x] Suche: Feld + Button, Enter/„Suchen“ der Tastatur = weiter, Groß/Klein egal, Wrap, gelbe Markierung, „nichts gefunden“; Strg+F mit Tastatur
- [x] Textzoom A+/A− (70 %–180 %), Prozentanzeige; Strg +/−/0 mit Tastatur
- [-] Strg+Mausrad-Zoom – nur Tastatur/Buttons; auf Touchgeräten ist das Buttonpaar der Standardweg
- [x] Nur lesbar, Kopieren erlaubt (Text markierbar)
- [~] Inhalte in 6 Sprachen – Texte aus `cfs_core.help_content`, nur Desktop-Fakten ersetzt (Speicherort, Python/Qt → Kotlin/Android) und ein neuer Abschnitt „Bedienung auf Android“ (M9)

## 16. Info-Dialog

- [~] Kopierbarer Text in 6 Sprachen (Engine, Suchverfahren, GUI, Lizenz) – Zeilen zu Python/Qt durch die Android-Umsetzung ersetzt; Buttons Kopieren/Schließen

## 17. Sprachen

- [x] Alle 183 UI-Texte aus `cfs_core.lang` als Android-Ressourcen (`values-xx/strings.xml`, generiert), 6 Sprachen (T)
- [x] Sprachwechsel zur Laufzeit beschriftet alles neu (Activity wird neu aufgebaut, Spiel läuft weiter)
- [~] Startsprache: gespeicherte Wahl > Systemsprache, falls übersetzt > Englisch (Desktop: sonst Deutsch) (M10)
- [x] Zahlenformat je Sprache (Tausendertrenner, Dezimalkomma) (T)

## 18. Dateien / Nutzerdaten

- [x] `.4gp` = Ziffernkette (ASCII, ohne Zeilenende); Laden ignoriert Fremdzeichen, illegale Züge und Züge nach Partieende (T)
- [~] Quicksave und Einstellungen intern (`files/quicksave.4gp`, SharedPreferences statt `settings.json`/`lang.cfg`); Sicherung per Android-Backup nur für diese beiden (M4)
- [-] „Zuletzt benutzter Ordner“ der Datei-Dialoge – übernimmt die Dateiauswahl des Systems selbst

## 19. Packaging

- [-] AppImage / Windows-Build – ersetzt durch APK (Gradle), F-Droid-Metadaten, reproduzierbarer Build (siehe README/DECISIONS)

## Neu auf Android

- Zustand bleibt bei Drehung, Sprachwechsel und im Hintergrund erhalten (Controller im Application-Objekt; nach Prozess-Ende Wiederherstellung von Partie, Modus, Analyse und Spielstand aus dem Instance-State)
- Hoch- und Querformat, Tablets (Layout skaliert mit der Zellgröße)
- Themed Icon (Android 13+), adaptives Icon (Android 8+), PNG-Icon für ältere Versionen

## Mobile Anpassungen (Begründungen)

- **M1 Layout:** Ein Telefon hat kein frei skalierbares Fenster. Im Hochformat
  steht das Brett oben (max. 70 % der Höhe), die drei Felder darunter in zwei
  Spalten; im Querformat links/rechts wie am Desktop. Die Felder scrollen,
  wenn der Platz nicht reicht. Im Querformat auf Telefonen (Höhe < 480 dp)
  ist die Aktionsleiste ausgeblendet; das Menü öffnet ein ⋮-Button oben in der
  rechten Spalte, die Statuszeile steht dort unten – das Brett wird so etwa
  ein Drittel größer. Leere Zeilen des Spielstands werden ausgeblendet.
- **M2 Ghost-Stein per Finger:** Touch hat kein „Überfahren“. Der Ghost-Stein
  erscheint beim Berühren und folgt dem Finger; gezogen wird beim Loslassen,
  Herausschieben aus dem Brett bricht ab. Ein einfaches Antippen zieht sofort.
- **M3 Animation:** zeilenweise 16-ms-Sprünge wirken auf hochauflösenden
  Displays ruckelig; die Fallbewegung läuft im Bildtakt (Choreographer) mit
  ähnlicher Dauer.
- **M4 Dateien:** Android-Apps haben keinen freien Dateisystemzugriff. Laden/
  Speichern laufen über das Storage Access Framework (keine Berechtigung
  nötig, auch Cloud-Speicher), interne Daten liegen im App-Speicher.
- **M5 Auswahllisten:** Das Optionsmenü erlaubt nur eine Untermenü-Ebene, daher
  Stufe, Stein-Set und Sprache als Dialog mit Radio-Liste.
- **M6 Hintergrund-Rechnen:** Auf Android darf der GUI-Thread nie blockieren
  (ANR). F6 rechnet deshalb im Hintergrund; Fortschritt wird live gemeldet
  (die Python-Version sammelte ihn bis zum Ende der Suche). Abbruch wirkt
  innerhalb einer Tiefe. Das Eröffnungsbuch wird beim ersten Start einmalig
  entpackt (Statusmeldung).
- **M7 Bilder:** WebP statt PNG (4,1 MB → ca. 0,7 MB), siehe ASSETS.md.
- **M8 Match:** Android hat keine nicht-modalen Fenster nebeneinander; das
  Match hat einen eigenen Bildschirm und läuft beim Verlassen weiter.
- **M9 Hilfe:** eigene Activity mit Suche/Zoom; Inhalt wie Desktop plus ein
  Abschnitt zur Touch-Bedienung (Desktop-Begriffe wie „Klick“ gelten sinngemäß).
- **M10 Sprache:** Android-Ressourcen brauchen eine Standardsprache; das ist
  Englisch (international verständlicher Fallback statt Deutsch).
- **M11 Engine:** Python/C++ sind auf Android nicht ohne native Bibliotheken
  nutzbar; der Kotlin-Port vermeidet NDK-Code (F-Droid-freundlich, eine APK für
  alle CPU-Architekturen).
