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

| Paramètre | PID (UDS) | Formule |
|---|---|---|
| Temp. moy. batterie | `22F1A3` | byte − 40 = °C |
| 6 sondes thermiques | `2202F9` | 6 bytes, chacun − 40 = °C |
| Liquide de refroid. entrée | `22027E` | byte − 40 = °C |
| Liquide de refroid. sortie | `22027F` | byte − 40 = °C |
| SOC | `22028C` | byte × 100 / 255 = % |
| Tension HV | `22025F` | int16 × 0.1 = V |
| État charge | `22023A` | 0=off, 1=AC, 2=DC |

**Module BMS** : header CAN `7E4` (requête) / `7EC` (réponse)  
**Protocole** : ISO 15765-4 CAN 11-bit 500kbps (ELM `ATSP6`)

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
├── MainScreen.kt            — UI Compose (gauge, sondes, log)
├── obd/
│   ├── ObdPids.kt           — Constantes PID + parseurs
│   └── BluetoothObdManager.kt — Connexion SPP + ELM327
├── viewmodel/
│   ├── BatteryModels.kt     — Modèles de données
│   └── MainViewModel.kt     — Logique + polling
└── ui/theme/
    └── Theme.kt             — Thème sombre Cupra
```

---

## Notes

- Les PIDs UDS de la plateforme MEB sont documentés par la communauté VCDS/OBDEleven. Ils peuvent varier légèrement selon le firmware BMS.
- L'OBDLink CX utilise le profil **SPP (Bluetooth Classic)**, pas BLE.
- Le polling est de 5 secondes. Tu peux le changer dans `MainViewModel.POLLING_INTERVAL_MS`.
- Sources communautaires : forums VWIDtalk, ID.3/Born subreddit, VCDS Ross-Tech wiki.

---

## Licence

Distribué sous **[PolyForm Noncommercial 1.0.0](https://polyformproject.org/licenses/noncommercial/1.0.0/)** — voir le fichier [`LICENSE`](./LICENSE).

Usage personnel, étude, modification et redistribution non-commerciale autorisés. Tout usage commercial nécessite un accord écrit du licenseur (Quentin Le Bourles).
