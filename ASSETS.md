# Assets und ihre Lizenzen

| Asset | Herkunft | Rechteinhaber | Lizenz |
|---|---|---|---|
| `app/src/main/assets/sets/set1 … set20/{back,red,yellow}.webp` | `data/images/set*/…_hires.png` der Desktop-Version, als WebP neu kodiert (`scripts/gen_resources.py`) | Patrick Götz (Autor von ConnectFour Studio) | GNU AGPL v3 (wie der Code) |
| `app/src/main/assets/sets/sets.json` | aus denselben Bildern berechnete Kantenfarben und Stein-Radien | Patrick Götz | GNU AGPL v3 |
| Programm-Icon (`res/drawable/ic_launcher_*.xml`, `res/mipmap-*/ic_launcher.png`, `fastlane/…/icon.png`) | nachgezeichnet nach `data/connectfour-studio.png` (`scripts/gen_icons.py`) | Patrick Götz | GNU AGPL v3 |
| `app/src/main/assets/book/book_12ply_dist.cfb` | `book_12ply_distances.dat` aus **bitbully-databases 0.0.2** von Markus Thill, verlustfrei umgepackt (`scripts/encode_book.py`) | Markus Thill | MIT (https://github.com/MarkusThill/bitbully-databases) |
| `app/src/main/res/raw*/help.json`, `values*/strings.xml` | Texte aus `cfs_core.help_content` und `cfs_core.lang` der Desktop-Version (generiert) | Patrick Götz | GNU AGPL v3 |
| Navigations-Symbole `res/drawable/ic_{first_page,chevron_left,chevron_right,last_page}.xml` | Pfade der Material Icons (first_page, chevron_left, chevron_right, last_page) | Google | Apache License 2.0 (https://github.com/google/material-design-icons) |
| Screenshots in `fastlane/metadata/android/en-US/images/phoneScreenshots/` (gelten für alle Sprachen) | auf einem moto g(30) aufgenommen, englische Oberfläche | Patrick Götz | GNU AGPL v3 |

Die Engine selbst (`core/src/main/kotlin/.../engine/`) ist eine Portierung von
**BitBully** (C++/Python, © Markus Thill, GNU AGPL v3,
https://github.com/MarkusThill/BitBully) und steht wie das ganze Programm unter
der GNU AGPL v3 (siehe `LICENSE`).

**Entscheidung zu den Bildern:** Die Stein-Sets und das Icon gehören dem Autor.
Sie werden unter derselben Lizenz wie der Code (AGPL v3) veröffentlicht, damit
das Repository einheitlich lizenziert ist. Alternativ könnten sie als CC0
freigegeben werden; dafür genügt es, die Lizenzspalte oben zu ändern
(Entscheidung des Rechteinhabers, siehe `DECISIONS.md`).
