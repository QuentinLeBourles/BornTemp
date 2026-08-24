# BornTemp — Cupra Born 77kWh Battery Temperature Monitor

Application Android native (Kotlin + Jetpack Compose) pour lire la température de batterie haute tension de la **Cupra Born 77kWh (2023)** via un **OBDLink CX** en Bluetooth.

---

## Prérequis

- Android Studio Hedgehog (2023.1.1) ou supérieur
- Android SDK 34
- Téléphone Android 8.0+ avec Bluetooth
- OBDLink CX apparié en Bluetooth avec le téléphone
- Contact mis sur la Cupra Born (position II minimum)

---

## Installation

1. **Clone / ouvre** ce dossier dans Android Studio
2. **Sync Gradle** (le bouton "Sync Now" en haut)
3. **Build → Run** sur ton téléphone Android

---

## Utilisation

1. **Apparie l'OBDLink CX** dans Paramètres Android → Bluetooth (si pas encore fait)
2. **Branche l'OBDLink CX** sur le port OBD2 de la Born (sous le volant)
3. **Mets le contact** (position II ou démarre)
4. **Lance BornTemp** et appuie sur **CONNECTER**
5. Sélectionne l'OBDLink CX dans la liste
6. L'app initialise l'ELM327 et commence à lire toutes les **5 secondes**

---

## Protocole OBD/UDS

**Transport** : l'OBDLink CX est un adaptateur **BLE uniquement** (pas de SPP/RFCOMM
Bluetooth Classic). Il expose un service GATT type UART :

| GATT | UUID | Rôle |
|---|---|---|
| Service | `FFF0` | service UART custom |
| Notify (RX) | `FFF1` | l'adaptateur pousse la sortie ELM327 en notifications |
| Write (TX) | `FFF2` | écriture des commandes (write-with-response) |

**Protocole CAN** : ISO 15765-4 CAN, **adressage étendu 29 bits**, 500 kbps
(ELM `ATSP7` + `ATCP17`). Les réponses multi-trames sont réassemblées côté app.

**Cibles ECU (en-têtes 29 bits)** :

| Module | Requête | Réponse |
|---|---|---|
| BMS (batterie HV) | `17FC007B` | `17FE007B` |
| EM (gestion énergie / 12V) | `17FC0010` | `17FE0010` |

L'init configure le flow-control 29 bits (`ATFCSH` / `STCFCPA`) sur le BMS, et
`sendCommand(cmd, ecu)` rebascule en-tête + filtre + flow-control au changement d'ECU.

**PIDs lus (service UDS 0x22, défini dans `obd/ObdPids.kt`)** :

| Paramètre | DID | ECU | Formule |
|---|---|---|---|
| Temp. pack max | `221E0E` | BMS | `(B0×256+B1) / 64` = °C |
| Temp. pack min | `221E0F` | BMS | `(B0×256+B1) / 64` = °C |
| SOC BMS (réel) | `22028C` | BMS | `raw / 2.5` = % |
| Tension pack HV | `221E3B` | BMS | `(B0×256+B1) / 4` = V |
| Courant pack HV | `221E3C` | BMS | ⚠️ **décodage non résolu — l'app ne publie plus de valeur** (voir ci-dessous) |
| Cellule min / max | `221E34` / `221E33` | BMS | `[VV VV][II II]` → tension `BE / 4096` = V, index cellule `BE` (1..96) |
| Mode véhicule | `227448` | BMS | `0`=veille, `1`=roulage, `4`=charge AC, `6`=charge DC |
| Pompe refroid. HV | `22743B` | BMS | 1 octet = % |
| Temp. liquide refroid. | `22189D` | BMS | `WW XX YY ZZ` → sortie `(WW×256+XX)/64`, entrée `(YY×256+ZZ)/64` °C |
| Énergie cumulée (vie) | `221E32` | BMS | charge `uint(B8..B11)/8583.07`, décharge `int(B12..B15)/8583.07` kWh |
| MEC (capacité max) | `222AB2` | EM | 4 octets BE Wh `/ 1000` = kWh — **muet sur la Cupra Born (NO DATA)** |
| EC (énergie courante) | `222AB8` | EM | idem MEC — **muet sur la Cupra Born (NO DATA)** |

> **Courant pack (`221E3C`) — non résolu.** La formule du handoff (2 premiers
> octets BE signés `/5`) ne résiste pas aux relevés : ces deux octets restent
> collés à `0x0016`–`0x0017` quoi que fasse la voiture, soit un ±4,4 A constant.
> Le relevé du 2026-08-16 affiche −4,40 A / −1,58 kW pendant une charge DC qui
> fait passer le SOC de 20 % à 55 % en douze minutes (≈ 135 kW), et le même
> −4,40 A en roulage. Les 32 bits complets, eux, bougent avec la charge, mais
> trois sessions n'ont pas suffi à en fixer l'encodage : la valeur baisse parfois
> en pleine charge (donc pas un compteur coulométrique) et les plages charge et
> roulage se recouvrent.
>
> En attendant, `parsePackCurrent` retourne `null` : un courant faux se propageait
> dans la puissance affichée, l'ETA, la télémétrie ABRP, et — silencieusement —
> dans `ChargeEnergyIntegrator`, dont le garde-fou `energyKwh <= 0` rejetait
> chaque passe puisque la puissance était négative. C'est la raison pour laquelle
> le SOH restait `UNAVAILABLE` malgré une charge qualifiante. La trame brute est
> capturée à chaque tick (`PID BMS:221E3C`) ; une session sur un chargeur de
> puissance connue suffira à fixer l'échelle.
>
> **Pack à 96 cellules** (12 modules × 8), et non 108 comme supposé initialement :
> tous les index rapportés par `1E33`/`1E34` sur trois sessions tombent dans
> 1..96, et la tension pack ÷ 96 se situe bien entre les cellules min et max.
>
> Le SOC affiché (HMI) est dérivé du SOC BMS : `SOC_HMI = SOC_BMS × 51/46 − 6.4`.
> MEC/EC sont sondés sur l'EM (`0x10`) puis en repli BMS/BREG/DCDC ; aucun module
> n'a répondu sur cette voiture (relevé 2026-06-21).
>
> Ce MEC absent servait de dénominateur unique à tout le pipeline capacité : SOH,
> tampons, confiance, ETA de charge et historique CSV tombaient ensemble. Deux
> replis sont désormais en place (`referenceCapacityKwh` / `classifyCapacityProvenance`
> dans `BatteryModels.kt`) :
>
> - **capacité de référence** — override utilisateur, sinon la déduction MEC, sinon
>   les 77 kWh que l'estimateur de charge suppose déjà. Sert d'échelle (SOH %, ETA),
>   jamais de mesure.
> - **capacité mesurée** — `apparentCapacityKwh` de `ChargeEnergyIntegrator`, intégré
>   sur une passe de charge 30→70 % de SOC. C'est la seule vraie mesure disponible
>   sur cette voiture ; le SOH la reporte en confiance **INDICATIF**, et reste à `--`
>   tant qu'aucune charge qualifiante n'a eu lieu.

---

## Plages de température

| Couleur | Plage | État |
|---|---|---|
| 🔵 Bleu | < 10°C | Froide — capacité et charge réduites |
| 🩵 Bleu clair | 10–15°C | Fraîche |
| 🟢 Vert | 15–35°C | **Optimale** |
| 🟡 Jaune | 35–40°C | Chaude — charge rapide ralentie |
| 🔴 Rouge | > 40°C | Trop chaude — protection active |

---

## Structure du projet

```
app/src/main/java/com/borntemp/app/
├── MainActivity.kt          — Activité principale + permissions
├── MainScreen.kt            — Routeur Compose (home / estimator / trend / errorDetail)
├── obd/
│   ├── ObdPids.kt           — Cibles ECU, séquence d'init, constantes PID + parseurs
│   ├── BluetoothObdManager.kt — Connexion BLE/GATT + ELM327
│   ├── ElmResponseAssembler.kt — Accumulation des trames jusqu'au prompt '>'
│   └── SessionCapture.kt    — Journalisation CSV des réponses brutes
├── screens/
│   ├── CockpitScreen.kt     — Tableau de bord (gauge, sondes, intervalle de polling)
│   ├── ChargeEstimatorScreen.kt — Planificateur de charge rapide DC
│   ├── SohTrendScreen.kt    — Courbe historique du SOH (CSV)
│   └── ErrorDetailScreen.kt — Détail erreur + log brut
├── components/
│   ├── CockpitHero.kt       — En-tête principal du cockpit
│   ├── ChargeEstimatorChart.kt — Graphe de l'estimateur de charge
│   └── CollapsibleCard.kt   — Carte repliable réutilisable
├── viewmodel/
│   ├── MainViewModel.kt     — Logique + polling
│   ├── BatteryModels.kt     — Modèles de données + état UI (dont pollingIntervalMs)
│   ├── BatterySettings.kt   — Préférences persistées (override type de pack…)
│   ├── ChargeAnalytics.kt   — Fenêtres glissantes (puissance, pente SoC, dT/dt)
│   ├── ChargeEstimator.kt   — Estimation du temps de charge restant
│   └── SohHistory.kt        — Historique SOH persisté
├── abrp/
│   ├── AbrpTelemetryClient.kt — Envoi de télémétrie à A Better Routeplanner
│   ├── AbrpSettings.kt      — Clés / configuration ABRP
│   └── LocationProvider.kt  — Position GPS pour la télémétrie
└── ui/theme/
    └── Theme.kt             — Thème sombre Cupra
```

---

## Notes

- Les PIDs UDS de la plateforme MEB sont documentés par la communauté VCDS/OBDEleven. Ils peuvent varier légèrement selon le firmware BMS.
- L'OBDLink CX est un adaptateur **BLE uniquement** (service GATT `FFF0`), il n'expose pas de SPP/Bluetooth Classic.
- Le polling est de **5 secondes par défaut**, ajustable (2–60 s) depuis l'écran cockpit ; la valeur vit dans `uiState.pollingIntervalMs` (`BatteryModels.kt`).
- Sources communautaires : forums VWIDtalk, ID.3/Born subreddit, VCDS Ross-Tech wiki.

---

## Licence

Distribué sous **[PolyForm Noncommercial 1.0.0](https://polyformproject.org/licenses/noncommercial/1.0.0/)** — voir le fichier [`LICENSE`](./LICENSE).

Usage personnel, étude, modification et redistribution non-commerciale autorisés. Tout usage commercial nécessite un accord écrit du licenseur (Quentin Le Bourles).
