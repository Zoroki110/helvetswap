#!/bin/bash

# Script de test automatisé pour l'AMM Canton
# Ce script lance les tests Playwright pour vérifier le bon fonctionnement de l'interface

echo "🧪 Tests Automatisés - Canton AMM"
echo "================================="

# Couleurs pour les logs
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Configuration
CANTON_ADMIN_API="http://localhost:5012"
AMM_UI_URL="http://localhost:3001"

# Fonction pour vérifier un service
check_service() {
    local service_name=$1
    local url=$2
    
    echo -n "🔍 Vérification de $service_name..."
    
    if curl -s -f "$url" > /dev/null 2>&1; then
        echo -e " ${GREEN}✅ OK${NC}"
        return 0
    else
        echo -e " ${RED}❌ ÉCHEC${NC}"
        return 1
    fi
}

echo "📋 Vérification des prérequis..."

# Vérifier Canton LocalNet
if ! check_service "Canton LocalNet" "$CANTON_ADMIN_API/v1/status" && 
   ! check_service "Canton Health" "$CANTON_ADMIN_API/health"; then
    echo -e "${YELLOW}⚠️  Canton LocalNet ne semble pas accessible${NC}"
    echo "L'AMM UI peut quand même être testée en mode déconnecté"
fi

# Vérifier l'interface AMM
if ! check_service "AMM UI" "$AMM_UI_URL"; then
    echo -e "${RED}❌ L'interface AMM n'est pas accessible sur $AMM_UI_URL${NC}"
    echo ""
    echo "Pour démarrer l'interface AMM :"
    echo "1. npm start (dans le répertoire amm-ui)"
    echo "2. Ou utilisez ./start-amm-ui.sh"
    echo ""
    echo "Voulez-vous continuer les tests sans l'interface ? (y/N)"
    read -r response
    
    if [[ ! "$response" =~ ^[Yy]$ ]]; then
        echo "Tests annulés"
        exit 1
    fi
fi

echo ""
echo "🚀 Lancement des tests Playwright..."

# Types de tests disponibles
echo ""
echo "Choisissez le type de test :"
echo "1) Tests complets (tous les navigateurs)"
echo "2) Tests rapides (Chrome uniquement)"
echo "3) Tests avec interface UI"
echo "4) Tests en mode debug"
echo "5) Tests spécifiques (choisir)"
echo ""
echo -n "Votre choix (1-5): "
read -r test_choice

case $test_choice in
    1)
        echo -e "${BLUE}🧪 Exécution des tests complets...${NC}"
        npm run test:e2e
        ;;
    2)
        echo -e "${BLUE}🧪 Exécution des tests rapides...${NC}"
        npm run test:e2e -- --project=chromium
        ;;
    3)
        echo -e "${BLUE}🧪 Ouverture de l'interface de test...${NC}"
        npm run test:e2e:ui
        ;;
    4)
        echo -e "${BLUE}🧪 Tests en mode debug...${NC}"
        npm run test:e2e:debug
        ;;
    5)
        echo ""
        echo "Tests disponibles :"
        echo "1) Interface principale (amm-interface)"
        echo "2) Intégration Canton (canton-integration)" 
        echo "3) Fonctionnalités swap (swap-functionality)"
        echo "4) Pools et liquidité (pools-liquidity)"
        echo "5) Historique transactions (transaction-history)"
        echo ""
        echo -n "Choisissez un test (1-5): "
        read -r specific_test
        
        case $specific_test in
            1) npm run test:e2e -- tests/amm-interface.spec.ts ;;
            2) npm run test:e2e -- tests/canton-integration.spec.ts ;;
            3) npm run test:e2e -- tests/swap-functionality.spec.ts ;;
            4) npm run test:e2e -- tests/pools-liquidity.spec.ts ;;
            5) npm run test:e2e -- tests/transaction-history.spec.ts ;;
            *) echo "Choix invalide" && exit 1 ;;
        esac
        ;;
    *)
        echo "Choix invalide"
        exit 1
        ;;
esac

echo ""
echo "📊 Tests terminés!"
echo ""
echo "📋 Résultats :"
echo "- Rapport HTML : playwright-report/index.html"
echo "- Screenshots : test-results/"
echo "- Vidéos : test-results/ (en cas d'échec)"
echo ""
echo "💡 Conseils :"
echo "- Utilisez 'npm run test:e2e:ui' pour une interface graphique"
echo "- Utilisez 'npm run test:e2e:debug' pour déboguer les tests"
echo "- Les tests s'adaptent automatiquement si Canton n'est pas disponible"
echo ""
echo "🎉 Tests de l'AMM Canton terminés!"

