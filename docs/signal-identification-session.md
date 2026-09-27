# Session d'identification des signaux (phase 5)

But : une seule session voiture doit suffire à trancher le courant HV, la limite BMS, MEC/EC et le 12 V.

## Avant de partir
1. Installer le build de `feat/acquisition-reliability`.
2. Réglages → **IDENTIFICATION SIGNAUX** : tout activer (les balayages sont coupés par défaut).
3. Réglages → **RELEVÉ EN CHARGE** : 5 s.

## Déroulé (≈ 45 min)
| Étape | Durée | Pourquoi |
|---|---|---|
| Contact mis, à l'arrêt | 2 min | Référence au repos : courant ≈ DC-DC seul |
| Roulage, dont une accélération franche et un freinage régénératif | 10 min | Courant des deux signes |
| Charge DC depuis < 30 % | 20 min min. | Courant stable et connu (puissance affichée par la borne) |
| Noter 3 fois l'heure et les kW affichés par la borne | — | Vérité terrain pour l'échelle du courant |
| Débrancher, rester 2 min contact mis | 2 min | Nouveau changement d'état → nouveau balayage |

## Ce qu'il faut rapporter
Brancher le téléphone ; je récupère `Android/data/com.borntemp.app/files/Download/` :
- `borntemp_uds_<ts>.csv` — chaque requête : ECU, trame, réponse, latence, NRC.
- `borntemp_soh_<ts>.csv` — mesures + statuts.
- `borntemp_<ts>.log` — lignes `CANDIDAT …` (bilan par candidat).
- Les kW notés à la borne, avec l'heure.

## Lecture des résultats
- **NRC_31** (requestOutOfRange) : l'ECU existe, le DID non.
- **TIMEOUT / NO_DATA** : rien n'a répondu à cette adresse.
- **OK** sur un balayage : DID existant — on compare sa valeur entre les balayages roulage / charge / arrêt.
