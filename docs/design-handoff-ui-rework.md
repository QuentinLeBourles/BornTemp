# BornTemp — Handoff design : rework de l'écran principal

> État au 2026-06-21. L'app est fonctionnelle (toutes les features SOH + Handoff-2 sont câblées), mais l'écran principal est devenu **un seul long scroll de 16 sections empilées**. On veut le tightener sans perdre les infos.

---

## 1. Inventaire actuel (ordre de scroll)

```
┌─────────────────────────────────────────┐
│ AppHeader                               │  ← "BornTemp" + sous-titre + StatusChip BLE
├─────────────────────────────────────────┤
│                                         │
│        ╭───────────╮                    │
│        │  33.4 °C  │  TemperatureGauge  │  ← Hero, anneau coloré
│        │  OPTIMALE │                    │
│        ╰───────────╯                    │
├─────────────────────────────────────────┤
│ ⏵ CHARGE  +18.4 kW  +52.1 A     [chip]  │  ← LivePowerChip (s'auto-cache si null)
├─────────────────────────────────────────┤
│ [ Charge en cours ]    (visible si DC/AC)
│ ┌─ TEMPS RESTANT ─ 50 kW (60 s) ────┐  │
│ │  → 80 % :  37 min                 │  │  ← ChargeProjectionCard (Handoff-2 A)
│ │  → 100 % : 1h 24m                 │  │
│ │  Pente SOC : +0.65 %/min          │  │
│ │  Capacité intégrée : 76.8 kWh     │  │
│ └────────────────────────────────────┘  │
├─────────────────────────────────────────┤
│ [ Trajectoire thermique ]               │
│ ┌─ PENTE TEMP ─────────────── OPTIMAL ┐ │  ← ThermalTrajectoryCard (Handoff-2 B)
│ │  ↑ +0.45 °C/min   depuis 33.4 °C   │ │
│ │  Atteint 40 °C dans ~14 min        │ │
│ │  Fenêtre thermique idéale...       │ │
│ └─────────────────────────────────────┘ │
├─────────────────────────────────────────┤
│ [ Santé batterie ]                      │
│ ┌─ SOH ─────────────────── 96.4 % ───┐ │  ← HealthCard (la plus dense)
│ │  ▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓░             │ │
│ │  74.2 / 77.0 kWh · LG 2021-22      │ │
│ │  Buffer : [bas|utilisable|haut]    │ │
│ │  SOC affiché 77 → SOC BMS 75.6     │ │
│ │  Tampon caché +1.4 %               │ │
│ │  🟢 FIABLE · T optimale + SOC haut │ │
│ │  [ VOIR LA TENDANCE SOH → ]        │ │
│ └─────────────────────────────────────┘ │
│ [Auto] [LG 77] [SK 79.9]                │  ← PackTypeSelector
├─────────────────────────────────────────┤
│ [ Compteurs vie ]                       │
│  Chargé · Consommé · Efficience          │  ← LifetimeEnergyCard (DID 1E32)
├─────────────────────────────────────────┤
│ [ Sondes thermiques ]                   │
│  ┌─┐┌─┐ ┌─┐┌─┐ ┌─┐┌─┐                  │  ← SensorsGrid 6 cards
│  └─┘└─┘ └─┘└─┘ └─┘└─┘                  │     ⚠️ actuellement TOUTES à `--`
├─────────────────────────────────────────┤
│ [ Cellules ]                            │
│  Vmin · Vmax · Δ · Tmin · Tmax (5 rows) │  ← CellsCard
├─────────────────────────────────────────┤
│ [ Système & Batterie ]                  │
│  10 rows: SOC, mode, V, I, P, pompe,    │  ← SystemInfoCard
│  fluide in/out, dernier relevé, proto   │     (très bavard)
├─────────────────────────────────────────┤
│ [ 12V système ]                         │
│  12.6 V · OK                            │  ← Battery12vCard
├─────────────────────────────────────────┤
│ [DÉCONNECTER]      [RAFRAÎCHIR]         │  ← ActionButtons
├─────────────────────────────────────────┤
│ [ Vitesse de relevé ]                   │
│  [2s] [5s] [10s] [30s]                  │  ← PollingIntervalSelector
├─────────────────────────────────────────┤
│ [ ABRP télémétrie ]                     │
│  Toggle + API key + token + status      │  ← AbrpCard
├─────────────────────────────────────────┤
│ [ Journal ]                             │
│  CAPTURE BRUTE          [EXPORTER]      │
│  HISTORIQUE SOH (CSV)   [EXPORTER]      │
│  ┌── 50 dernières lignes log ──┐        │  ← LogPanel
│  └──────────────────────────────┘       │
└─────────────────────────────────────────┘
```

**Total : 16 sections, ~2200 lignes de Kotlin Compose dans un seul `MainScreen.kt`.**

---

## 2. Pain points (le « pâté »)

| Symptôme | Cause | Constat |
|---|---|---|
| Scroll très long | Tout est empilé verticalement, aucune densité 2D | Le user perd l'overview |
| Trop de "section labels" | Une étiquette par card, même quand la card fait 2 lignes | Bruit visuel |
| Cards qui se ressemblent | Même surface, même border, même padding pour info live ET réglages | Hiérarchie absente |
| Settings noyés dans le flux | ABRP, polling, pack override scrollent avec les données live | Devraient être ailleurs |
| `Sondes thermiques` à 6× `--` | DID inconnu sur ce véhicule | Section qui occupe ~140 dp pour rien |
| `Système & Batterie` à 10 rows | Doublons avec ce qui est déjà dans HealthCard / LivePowerChip | Redondance |
| Journal en bas du scroll | Très utile en debug mais s'enterre | Toujours à ouvrir |
| Pas d'identité Cupra/Born | Couleur d'accent verte (BornGreen) générique | L'icône passe en Cobre, l'app non |

---

## 3. Données disponibles, par cadence

C'est ça la matière première du redesign. Ce qui est lu vite peut bouger sous le doigt → mérite un graphique ou un slot animé. Ce qui est lu lent → tile statique ou révélation à la demande.

| Cadence | Champ | Source | Notes |
|---|---|---|---|
| **Fast** (2-30 s, défini par le user) | T avg, T min, T max | BMS `1E0E/1E0F` | Hero, drift lent |
| | SOC HMI + BMS | BMS `028C` + dérivé | Bouge en charge / roulage |
| | V HV, I HV, P | BMS `1E3B/1E3C` | Très live, surtout en charge |
| | Vehicle mode | BMS `7448` | Décide quelles cards montrer |
| | Pump % | BMS `743B` | 0-100, climbé en charge |
| | Coolant in/out | BMS `189D` | Lent mais utile pour le derating |
| **Slow** (×12 fast ticks ≈ 60 s) | MEC, EC | EM / BMS / BREG (cherche encore) | Si trouvé, base du SOH |
| | 12V | EM `2AF7` ou OBD-II `0142` | Pour la santé batterie 12V |
| | Cell V min/max + idx | BMS `1E33/1E34/1E40+` | Δ cellules |
| | Lifetime charge/discharge | BMS `1E32` | Compteurs vie, change peu |
| **Une fois** | Pack type (LG/SK) | Heuristique ou override user | Détermine la réf MEC |
| | Adaptateur / protocole | Init ELM327 | Statique |
| **Dérivés en mémoire** | Pente SOC (%/min) | `ChargeAnalytics` régression linéaire | Pour ETA + UX charge |
| | Pente T (°C/min) | idem | Pour trajectoire thermique |
| | ETA → 80 / 100 % | `etaMinutesTo` heuristique | Visible en charge |
| | Capacité intégrée mid-range | `ChargeEnergyIntegrator` | Cross-check MEC |
| | Confiance SOH | `classifySohConfidence` | 🟢 / 🟠 / ❔ + raison |
| | Conseil thermique | `classifyThermalTrajectory` | OPTIMAL / PRÉ-ROULE / REPORTE / BRIDÉE |

→ **Beaucoup de signaux dérivés sont déjà calculés mais sous-exploités**. Une vue "dashboard" pourrait en surfacer plus de 5 sans rien recalculer.

---

## 4. Palette & typo (à respecter)

Définies dans `ui/theme/Theme.kt` (sauf indiqué).

| Token | Valeur | Usage |
|---|---|---|
| `BornBg`     | `#0A0E14` | Fond global (très sombre, bleu nuit) |
| `BornSurface`| `#111720` | Cards |
| `BornText`   | `#E8EDF5` | Texte principal |
| `BornMuted`  | `#6B7A94` | Texte secondaire, labels small caps |
| `BornBorder` | `#14FFFFFF` (alpha) | Bordures cards 0.5dp |
| `BornGreen`  | `#00D4A8` | Accent principal actuel (teal). OK ou à *substituer* par Cupra Cobre |
| `BornBlue`   | `#60A5FA` | Charge / froid |
| `BornAmber`  | `#F59E0B` | Warning, regen idle |
| `BornRed`    | `#FF4D4D` | Erreur, surchauffe |
| **Cupra Cobre** *(à ajouter)* | `#B26F47` (+ sheen `#D9956C`) | Déjà utilisé dans l'icône launcher — pas encore dans l'app |
| **Cupra Petrol** *(à considérer)* | `#1A3540` ou voisin | Alternative cards si on veut un fond non-monochrome |

**Typo** : `FontFamily.Monospace` partout, `letterSpacing` agressif sur les labels (1.5–2 sp). Style "instrument de bord" / "console" — c'est l'identité, à garder.

**Tailles couramment utilisées** : 9-11sp pour les labels small caps, 13-16sp pour les valeurs, 56sp pour le hero du gauge.

---

## 5. Constraints techniques (ne pas casser)

- **Compose Material3**, minSdk 26, dark theme uniquement.
- L'écran principal vit dans `MainScreen.kt` (gros fichier — un découpage en plusieurs `*.kt` serait bienvenu avant le rework).
- L'`UiState` est imposé par `MainViewModel` : tout passe par `uiState.batteryData.*` + `uiState.chargeProjection` + `uiState.thermalTrajectory`. Le redesign ne doit pas exiger de nouveaux champs (sauf à compléter ces data classes).
- Connexion live BLE → `FLAG_KEEP_SCREEN_ON` actif tant que connecté → on peut se permettre des animations un peu coûteuses (pulse, transitions), mais pas un canvas qui repaint à 60fps.
- Sélection texte autorisée sur l'écran d'erreur seulement (déjà fait).
- Les exports CSV/log sont déclenchés via `FileProvider` — bouton "EXPORTER" déjà câblé, à conserver fonctionnellement.

---

## 6. Pistes de rework (à choisir, pas à empiler)

### Piste A — « Cockpit » (radical)

Inspiration : tableaux de bord EV (Polestar Now, OVMS).

```
┌──────────────────────────────────────────┐
│  Header compact + chip BLE               │
├───────────────┬──────────────────────────┤
│               │  SOC 77 %                │
│  TempGauge    │  ┌────────────────────┐  │
│  (hero)       │  │ ETA 80% : 37 min   │  │  ← grille 2 colonnes pour le hero
│               │  │ P : +52 kW         │  │
│               │  │ V : 375 V          │  │
│               │  └────────────────────┘  │
├───────────────┴──────────────────────────┤
│ Stack contextuelle (n'apparaît que       │
│ si applicable) :                         │
│  · Charge en cours (si chargingMode)     │
│  · Thermique (si advice ≠ NONE)          │
│  · Cellules (collapsed par défaut)       │
├──────────────────────────────────────────┤
│ Tabs en bas : [ Live | SOH | Système |   │
│                 Journal | Réglages ]     │
└──────────────────────────────────────────┘
```

- **Pour** : maximum de densité utile au-dessus du fold, navigation claire par tabs.
- **Contre** : refonte structurelle, navigation à inventer.

### Piste B — « Cards qui se contractent » (modéré)

Garder le scroll vertical, mais :
- Cards collapsibles avec un état par défaut (HealthCard collapsed sur SOH+capacité, expand → buffer + SOC + confiance).
- `SystemInfoCard` masquée par défaut (lien "voir tous les paramètres" en bas).
- `Sondes thermiques`, `12V système` : masqués tant qu'on n'a pas de data live (déjà fait pour Compteurs vie — pattern à étendre).
- Settings (ABRP / polling / pack override) déplacés dans un *bottom sheet* "Réglages".

- **Pour** : conserve la familiarité, gain de densité ~40 %.
- **Contre** : ne corrige pas la sensation de "longue liste de boîtes".

### Piste C — « Tabs simples » (léger, le moins risqué)

Toolbar à 3 onglets : **Live** / **Santé** / **Journal & réglages**.

- **Live** = TempGauge + LivePower + ChargeProjection + ThermalTrajectory + Cellules basique.
- **Santé** = HealthCard + PackType + Compteurs vie + Tendance SOH (bouton qui existe déjà).
- **Journal & réglages** = SystemInfo + 12V + Action buttons + Polling + ABRP + LogPanel + Capture exports.

- **Pour** : 1h de boulot, casse rien, le scroll par tab redevient digérable.
- **Contre** : pas de saut qualitatif visuel ; reste « 3 listes empilées au lieu d'1 ».

---

## 7. Ce qu'on peut décemment jeter / fusionner

- **`Sondes thermiques` (6 cases)** : tant que le DID n'est pas trouvé, c'est mort. Le retirer du flux principal, le réintroduire SI un jour `2202F9` ou équivalent répond.
- **`Système & Batterie` 10 rows** : SOC déjà dans HealthCard, État charge déjà dans LivePower, mode déjà visible via LivePower label, V/I/P déjà dans LivePower. Il reste vraiment : pompe %, fluide in/out, dernier relevé, protocole. → 4 rows max, à fusionner avec une card plus large "détails techniques" cachée par défaut.
- **`12V système`** : 1 valeur, pas besoin d'une card entière. Coller dans la même ligne que la pompe + fluide.
- **`PollingIntervalSelector`** : appartient aux réglages, pas au flux live.

---

## 8. Bonus — l'identité visuelle

Le launcher est passé en **Cobre `#B26F47`**. L'app, elle, est encore en BornGreen teal. Trois options selon l'ambition :

1. **Garder vert teal en accent live** (charge, OK), introduire le Cobre uniquement sur les éléments « identitaires » (header, statusChip connecté, bouton CONNECTER).
2. **Bascule complète** : Cobre devient l'accent principal, le teal devient indicateur secondaire (régen, vert OK).
3. **Dual-tone Cupra** : Cobre pour les états *actifs/intelligents* (SOH, ETA, conseil), Petrol Blue (`#1A3540` ou voisin) pour les *cards d'info passive*. Plus radical mais signature.

---

## 9. Hors-scope, mais à garder en tête

- L'app est offline-first sauf ABRP. Pas de compte, pas de cloud.
- Les exports CSV alimentent la **tendance SOH** + (futur) **modèle ML thermique** Phase 2. Ne pas casser la structure des fichiers (cf. `SessionCapture.kt`).
- Le format des `Section labels` (small caps mono lettre-spacée) sert aussi dans la table OBD du log → bien penser à harmoniser si on change.
- L'écran tourne en `screenOrientation="fullSensor"` (manifest). Si paysage devient une cible, prévoir un layout 2-colonnes natif.

---

## Annexe — fichiers concernés par un rework

| Zone | Fichier | Action probable |
|---|---|---|
| Layout principal | `MainScreen.kt` (~2200 l.) | À découper en `screens/`, `components/` |
| State | `viewmodel/BatteryModels.kt` | OK, juste compléter si besoin |
| Thème / couleurs | `ui/theme/Theme.kt` | Ajouter Cobre + tokens dérivés |
| Manifest | `AndroidManifest.xml` | Rien à changer pour A/B/C |
| Tests | `test/viewmodel/ChargeAnalyticsTest.kt` | Indépendants de l'UI, intacts |

→ Le rework est **purement UI**. Aucune feature OBD ne doit être impactée.
