# Entscheidungen – Android-Port von ConnectFour Studio

Kurzprotokoll der Entscheidungen beim Port der Qt-Version (Oktober 2026).
Feature-Abgleich und mobile Abweichungen stehen in `FEATURES.md`.

## Platzhalter, die vor der Veröffentlichung anzupassen sind

- **Application-ID:** `io.github.patrickric.connectfourstudio` (Platzhalter nach
  dem GitHub-Konto `Patrick-Ric`; Bindestriche sind in Paketnamen nicht
  erlaubt). Ändern in `app/build.gradle.kts` (`applicationId`). Der
  Kotlin-Namespace (`namespace`, Paketname der Quellen) darf gleich bleiben.
  Die App-ID taucht außerdem in `docs/fdroid-metadata.yml` (Dateiname und
  Inhalt) und im README auf.
- **Version:** `versionCode = 1`, `versionName = "1.0.0"` (erste Android-
  Veröffentlichung; die Desktop-Version heißt 2.0.0, die Zählung ist
  unabhängig). Jede Veröffentlichung braucht einen höheren `versionCode` und
  eine Datei `fastlane/metadata/android/*/changelogs/<versionCode>.txt`.

## Technik

- **Nur Android-Framework:** Kotlin, `android.app.Activity`, Framework-Views,
  `AlertDialog`, Optionsmenü, `SharedPreferences`, `org.json`. Keine AndroidX-,
  Material- oder sonstigen Laufzeitbibliotheken. Einzige Laufzeitabhängigkeit
  ist die Kotlin-Standardbibliothek (unvermeidbar bei Kotlin; R8 entfernt
  Ungenutztes). Testabhängigkeit: JUnit 4.13.2 (nur `core`, nur Tests).
- **Versionen (fest, ohne `+`):** Gradle 9.8.1 (Wrapper mit SHA-256-Prüfsumme),
  Android Gradle Plugin 9.4.1 mit eingebautem Kotlin, Kotlin 2.4.21,
  compileSdk/targetSdk 37 (höchste installierte Plattform), Build-Tools
  36.0.0, minSdk 21. Java-Bytecode 17.
- **JDK 21 für Gradle:** `gradle/gradle-daemon-jvm.properties`
  (`toolchainVersion=21`) lässt Gradle selbst ein JDK 21 wählen – unabhängig
  vom System-Standard (hier Java 26). Kein maschinenspezifischer Pfad im
  Repository; zusätzlich kann `JAVA_HOME` gesetzt werden (README).
- **Module:** `core` ist reines Kotlin/JVM ohne Android-Abhängigkeit (Spiel,
  Engine, Stufen, Match, Texte als Schnittstelle) und wird mit JUnit getestet;
  `app` enthält nur Oberfläche und Android-Anbindung.

## Engine

- **Kotlin-Port statt nativer Bibliothek:** BitBully (C++, AGPL v3) wurde 1:1
  nach Kotlin übertragen (`core/.../engine/`): Bitboards, Zugsortierung,
  Negamax mit Transpositionstabelle (EXACT/LOWER/UPPER, ETC, Spiegelung),
  MTD(f), Rollouts für die Tiefenbegrenzung, Buchzugriff. Grund: keine
  NDK-Builds pro CPU-Architektur, eine kleine APK, leichter für F-Droid zu
  bauen. Gleiche Hashfunktion und TT-Größe (2^22) ⇒ die Tests vergleichen
  nicht nur Scores, sondern auch die **Knotenzahlen jeder Iterationstiefe**
  mit der Python/C++-Engine (`scripts/gen_engine_reference.py`) – alle gleich.
  Die JVM rechnet dabei etwa so schnell wie der C++-Kern.
- **Eröffnungsbuch:** `12-ply-dist` aus bitbully-databases 0.0.2 (MIT) wird
  verlustfrei als `.cfb` gepackt (Schlüssel-Deltas als Varint + Werte, zlib):
  21 MB → 5,4 MB in der APK (`scripts/encode_book.py`, Asset ohne
  Doppelkompression). Beim ersten Start wird es einmalig in `no_backup`
  entpackt und dann per `mmap` gelesen – kein 21-MB-Block im Java-Heap.
- **Transpositionstabelle:** 2^22 Einträge (48 MB, parallele Arrays) wie in C++
  bei großem Heap (`largeHeap`), sonst 2^21 bzw. 2^20. Exakte Ergebnisse sind
  davon unabhängig; nur Knotenzahlen und Zwischenwerte begrenzter Tiefen
  können sich minimal unterscheiden.
- **Abbruch:** Die Suche fragt alle 1024 Knoten das Abbruch-Flag ab (die
  C++-Engine konnte nur zwischen den Tiefen stoppen). Abgebrochene Tiefen
  werden verworfen, die Logik „letzte vollständige Tiefe zählt“ bleibt.
- **Iteration endet am Buch (Abweichung vom Desktop):** Bei weniger als 12
  Steinen erreicht jede Variante ab Tiefe 12 − Steinzahl das 12-ply-Buch; ab
  dort sind die Werte exakt und jede weitere Tiefe (bis 20 und „Voll“)
  wiederholte nur dieselbe Suche (der TT-Cache greift wegen der Tiefen-
  Budgets nicht). Die Android-Version bricht dort ab und zeigt als Tiefe
  „Buch 12d“. Bewertungen und Zugwahl sind identisch; in der Eröffnung
  braucht die Analyse nur noch 10–28 % der Knoten (4–10× schneller). Die
  Tests prüfen das an allen Referenzstellungen (gleiche Werte wie die volle
  Python-Suche, Knotenzahl = Python-Suche bis zur Buchtiefe). Die Qt-Version
  rechnet noch alle Tiefen.
- **Fortschritt live:** Die Python-Version sammelte die Fortschrittsmeldungen
  bis zum Ende der Suche; hier werden sie sofort (gedrosselt auf 200 ms) aus
  dem Worker-Thread an die Oberfläche gemeldet.
- **Zufall:** `kotlin.random.Random` statt Python-`random`; Zugwahl-Logik und
  Wahrscheinlichkeiten identisch, die konkreten Zufallsfolgen natürlich nicht.
- **Hintergrund-Threads:** Engine-Zug, Dauer-Analyse, F6-Bewertung und
  Zufallssuche laufen in eigenen Threads (Engine-Lock wie in Python),
  Ergebnisse gehen per `Handler` in den Hauptthread; veraltete Ergebnisse
  werden über Sequenznummer und Stellungs-Snapshot verworfen.

## Oberfläche

- **Controller im Application-Objekt:** Die Ablaufsteuerung (Port von
  `MainWindow`, gleiche Methodennamen) überlebt Drehung und Sprachwechsel; die
  Activities sind nur Ansichten. Nach einem Prozess-Ende im Hintergrund werden
  Partie (inkl. Zug-vor-Stapel), Modus, Analyse und Spielstand aus dem
  Instance-State wiederhergestellt; ein laufendes Match nicht (es würde ohne
  Zuschauer weiterlaufen).
- **Optionsmenü** mit der Desktop-Menüstruktur (Datei, Ansicht, Einstellungen,
  Kommandos, Hilfe). Untermenüs zweiter Ebene gibt es auf Android nicht →
  Stufe, Stein-Set und Sprache als Auswahldialog.
- **Eigene Activities** für Match (Einstellungen + Live-Stand) und Hilfe; keine
  eigene Einstellungs-Activity, weil alle Einstellungen des Desktop-Programms
  bereits im Menü (Haken/Radio) bzw. im Match-Bildschirm liegen.
- **Sprachwahl in der App:** Kontext-Wrapping per `createConfigurationContext`
  (funktioniert ab API 17, ohne AndroidX/AppCompat). Standardressourcen
  (`values/`) sind Englisch; Startsprache = gespeicherte Wahl, sonst
  Systemsprache falls übersetzt, sonst Englisch (Desktop: Deutsch als
  Fallback – für ein internationales F-Droid-Publikum ist Englisch sinnvoller).
  Die Menüeinträge zeigen die Tastenkürzel nicht an (siehe FEATURES.md).
- **Texte:** Alle Schlüssel aus `cfs_core.lang` werden 1:1 als
  `strings.xml` erzeugt (`scripts/gen_resources.py`), dazu Stufen- und
  Set-Namen sowie 5 neue Android-Texte in allen 6 Sprachen. Die einzige
  Format-Angabe `{score:+d}` wird zu `{score}`, das Vorzeichen setzt der Code.
  Lint-Hinweise zu „...“ und „-“ sind abgeschaltet, weil die Texte identisch
  zum Desktop bleiben sollen.
- **Hilfe:** Inhalt aus `cfs_core.help_content` als `res/raw-xx/help.json`
  (sprachabhängige Ressourcen). Ersetzt wurden nur Desktop-Fakten (Speicherort
  des Quicksave, „Python-Modul/Qt“), ergänzt ein Abschnitt „Bedienung auf
  Android“. Hinweise auf Maus/Tasten bleiben stehen (gelten sinngemäß bzw. mit
  Tastatur wörtlich).
- **Keine Berechtigungen:** kein Internet, kein Speicherzugriff (SAF), kein
  Tracking, keine Werbung, keine proprietären Komponenten.
- **Backup:** nur Einstellungen und Quicksave (Backup-Regeln für Android 6–11
  und 12+); das entpackte Buch liegt in `no_backup`.

## Bilder

- 20 Stein-Sets aus `data/images` (256-px-HiRes) als WebP: Qualität 95, wo der
  PSNR gegenüber dem PNG ≥ 38 dB bleibt, sonst verlustfrei (z. B. Set 10 mit
  harten Farbkanten). 4,1 MB → ca. 0,7 MB. Keine Verkleinerung unter 256 px,
  damit Tablets scharf bleiben. Die Stein-Radien für die Ringe werden mit dem
  Original-Algorithmus zur Bauzeit vermessen (`assets/sets/sets.json`).
- **Lizenz der Bilder:** Die Stein-Sets und das Icon gehören dem Autor
  (Rechteinhaber Patrick Götz). Entscheidung: Sie stehen unter derselben
  Lizenz wie der Code (**GNU AGPL v3**), damit Quellen und Assets einheitlich
  lizenziert sind und kein Lizenzmix entsteht. CC0 wäre ebenfalls möglich;
  falls gewünscht, nur `ASSETS.md` ändern. Details in `ASSETS.md`.
- **Icon:** adaptives Vektor-Icon im Stil des Desktop-Icons (blaues Quadrat,
  4×4-Steine) mit Monochrom-Ebene; PNG-Fallback für API < 26 und das
  F-Droid-Icon (512 px) erzeugt `scripts/gen_icons.py` mit derselben Geometrie.

## Reproduzierbarer Build / F-Droid

- `dependenciesInfo { includeInApk = false; includeInBundle = false }`,
  `vcsInfo.include = false` (keine `version-control-info.textproto`),
  keine Zeitstempel/Build-Daten im Code oder in Ressourcen, R8 deterministisch.
- Release-APK wird **unsigniert** gebaut (F-Droid signiert selbst bzw. prüft
  gegen eine vom Entwickler signierte APK, siehe README).
- Generierte Dateien (Strings, Hilfe, Bilder, Buch-Asset, Referenzdaten) sind
  eingecheckt; der Gradle-Build braucht kein Python. Die Skripte unter
  `scripts/` dokumentieren und reproduzieren die Erzeugung aus den Quellen der
  Qt-Version bzw. bitbully-databases.

## Tests

- JUnit (`./gradlew test`): Port von `tests/test_game.py` und
  `tests/test_engine.py` (alle Fälle außer GUI) + Vergleich mit der Python-
  Engine an 34 festen Stellungen (Scores und Knotenzahlen je Tiefe, MTD(f),
  scoreToMovesLeft), 300 Buchstellungen, 80 Partien (Gewinnreihen) und 80
  `.4gp`-Präfixfällen.
- Emulator: Auf diesem Rechner gibt es kein KVM (keine VT-x/AMD-V-Flags), daher
  laufen x86_64-Images (z. B. API 35) nicht. Getestet wurde mit ARM-Images
  (armeabi-v7a) in reiner Software-Emulation mit Emulator 28.0.23 (die aktuelle
  Version 37 unterstützt ARM-Gäste auf x86 nicht mehr): **API 21** (Android 5.0,
  `-engine classic`) und **API 24** (Android 7.0, AOSP-Image). Das Google-APIs-
  Image API 25 war unter Software-Emulation nicht benutzbar (System-ANR bei der
  Installation). Diese Emulation ist etwa 100× langsamer als ein echtes Gerät;
  Rechenzeiten in Statuszeile/Screenshots sind entsprechend hoch.
- Geprüft per `adb shell input tap/keyevent`, `screencap`, `uiautomator dump`
  und logcat: vollständige Partien gegen „14 Perfekt“ und „7 Mittel“ (API 21),
  Computer-Computer-Match (2 Partien, Turbo), Menüs und Dialoge, Stufenwahl,
  Sprachwechsel (Partie bleibt), Drehen hoch/quer, Tastenkürzel (1–7, F1, F3/F4,
  F6, F7), Schnellspeichern/-laden, Hilfe (Links, Suche, Zoom), Laden/Speichern
  per Dateiauswahl (API 24; der API-21-Emulator hat keine SD-Karte für den
  Downloads-Anbieter), Wiederherstellung nach Prozess-Ende im Hintergrund
  (API 24). Eine Analyse auf dem Gerät wurde mit der Python-Engine
  gegengeprüft: alle 7 Spaltenwerte und die Knotenzahl identisch.
- Gefundene und behobene Fehler: Absturz jedes Engine-Zugs unter Android 7
  (`String.format(Locale.ROOT, "%,d")` → „divide by zero“, Plattform-Fehler;
  jetzt eigene Tausendergruppierung, Unit-Test ergänzt), Hilfe-Suche scrollte
  falsch / Enter-Taste verschob den Fokus, zu kleine Bedienelemente im
  Querformat auf kleinen Telefonen, Tastatur sprang im Match-Bildschirm auf,
  Hilfe baute alle ~300 Absätze auf einmal (auf sehr langsamen Geräten ANR,
  jetzt schrittweise).
