# HelvetSwap (ClearportX) — DEX AMM sur Canton Network

**Projet arrêté, non maintenu.** Ce dépôt est publié à titre de référence. Il ne reçoit plus de correctifs et ne tourne plus nulle part.

Ce dépôt est **un extrait d'un dépôt privé** : l'historique git d'origine n'est pas repris (un seul commit), et des fichiers internes ou d'exploitation ont été retirés.

## Ce que fait le projet

HelvetSwap est un échange décentralisé (DEX) à teneur de marché automatique (AMM, produit constant `x·y = k`), écrit pour le **Canton Network**. Il a été déployé sur le MainNet Canton en 2026, avec un pool CC (Amulet) / CBTC et des frais de 0,30 %. En production, il n'a traité que des swaps de test de l'opérateur.

| Dossier | Rôle |
|---|---|
| `quickstart/clearportx/daml/` | Contrats Daml du paquet `helvet-amm` 1.0.0 : pools adossés au Token Standard (`HoldingPool`), swaps, liquidité, jetons LP, frais de protocole |
| `quickstart/clearportx/daml-prod/` | Variante `clearportx-amm-production-gv-compat` (Daml SDK 3.4.7) |
| `quickstart/backend/` | Backend Java 17 / Spring Boot 3.4 : API REST, soumission des commandes au Ledger API (gRPC), paiements par TransferInstruction, limitation de débit |
| `quickstart/frontend/` | Interface React 18 (Create React App), connexion de portefeuille, swap et liquidité |
| `quickstart/daml/`, `quickstart/docker/` | Socle hérité de `digital-asset/cn-quickstart` : DAR Splice, LocalNet Docker Compose |

## Technologies

- Daml (SDK 3.3 / 3.4), Canton 3.x, Splice 0.6.x (Amulet, Token Standard CIP-0056)
- Java 17, Gradle 8, Spring Boot 3.4
- React 18, TypeScript
- Docker Compose (LocalNet), Nix (environnement de développement optionnel)

## Construire et lancer

Prérequis : JDK 17, Node.js 18+, Daml SDK (versions indiquées dans les `daml.yaml`), Docker.

```bash
# Contrats Daml helvet-amm
cd quickstart/clearportx && daml build

# Backend (depuis quickstart/)
cd quickstart && ./gradlew build

# Frontend
cd quickstart/frontend && npm ci && npm run build
```

Pour un environnement local complet (Canton LocalNet + backend + frontend), le socle `cn-quickstart` fournit `quickstart/Makefile` et `quickstart/compose.yaml`. La configuration passe par des variables d'environnement : voir `quickstart/.env.example` et `quickstart/backend/src/main/resources/application-*.yml`. **Aucun secret n'est fourni.** Il faut définir soi-même, entre autres, `CLEARPORTX_AUTH_JWT_SECRET`, `AUTH_APP_PROVIDER_BACKEND_SECRET`, `admin.password` et l'accès au Ledger API.

## État réel

- Projet arrêté ; le code reflète le dernier état déployé (juin 2026).
- En production, le backend tournait en mode développement (`gradle bootRun`), et non comme un service empaqueté.
- Aucune garantie de fonctionnement contre une version actuelle de Canton ou de Splice.

## Licence

Le socle provient de `digital-asset/cn-quickstart` : voir `LICENSE` et `quickstart/NOTICES`.

## Limite connue

Le connecteur du portefeuille Zoro a été retiré de cette publication : il sgissait de code dérivé d'un tiers, sans licence visible. Le frontend ne compile pas sans lui.
