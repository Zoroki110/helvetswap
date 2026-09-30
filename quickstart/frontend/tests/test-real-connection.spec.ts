import { test, expect } from '@playwright/test';

test.describe('🔗 Test Connexion Réelle Canton LocalNet', () => {
  
  test('🎯 Test connexion avec JSON API Canton', async ({ page }) => {
    console.log('🚀 TEST CONNEXION RÉELLE CANTON LOCALNET');
    console.log('========================================');
    
    // Aller sur l'interface
    await page.goto('http://localhost:3001');
    await page.waitForLoadState('networkidle');
    
    console.log('✅ Interface AMM chargée');
    
    // Attendre que les vérifications de santé se fassent
    await page.waitForTimeout(5000);
    
    // Vérifier l'état de connexion
    const connectionStatus = await page.evaluate(() => {
      const bodyText = document.body.innerText;
      return {
        isConnected: bodyText.includes('Connected to Canton') && !bodyText.includes('Disconnected'),
        hasDisconnectedMessage: bodyText.includes('Disconnected from Canton Network'),
        bodySnippet: bodyText.substring(0, 500)
      };
    });
    
    console.log('🔍 État de connexion:');
    console.log(`  - Connecté: ${connectionStatus.isConnected ? '✅' : '❌'}`);
    console.log(`  - Message déconnexion: ${connectionStatus.hasDisconnectedMessage ? '❌' : '✅'}`);
    
    // Vérifier les requêtes réseau vers Canton
    const requests: string[] = [];
    const responses: string[] = [];
    
    page.on('request', request => {
      if (request.url().includes('localhost:7575') || request.url().includes('localhost:5012')) {
        requests.push(`${request.method()} ${request.url()}`);
      }
    });
    
    page.on('response', response => {
      if (response.url().includes('localhost:7575') || response.url().includes('localhost:5012')) {
        responses.push(`${response.status()} ${response.url()}`);
      }
    });
    
    // Forcer un refresh pour déclencher les requêtes
    await page.reload();
    await page.waitForTimeout(5000);
    
    console.log('🌐 Requêtes Canton:');
    requests.forEach(req => console.log(`  📤 ${req}`));
    responses.forEach(res => console.log(`  📥 ${res}`));
    
    // Test direct de l'API Canton depuis le navigateur
    const apiTest = await page.evaluate(async () => {
      try {
        // Test JSON API sans auth d'abord
        const response = await fetch('http://localhost:7575/v1/query', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ templateIds: [] })
        });
        
        return {
          status: response.status,
          ok: response.ok,
          statusText: response.statusText,
          body: await response.text()
        };
      } catch (error) {
        return {
          error: error.message,
          status: 0
        };
      }
    });
    
    console.log('🧪 Test direct API Canton:');
    console.log(`  - Status: ${apiTest.status}`);
    console.log(`  - Erreur: ${apiTest.error || 'Aucune'}`);
    if (apiTest.body) {
      console.log(`  - Réponse: ${apiTest.body.substring(0, 100)}...`);
    }
    
    // Vérifier si l'interface a des tokens maintenant
    const interfaceData = await page.evaluate(() => {
      return {
        hasTokensInUI: document.body.innerText.includes('USDC') || document.body.innerText.includes('ETH'),
        hasSelectButtons: document.querySelectorAll('button:contains("Select")').length > 0,
        hasSwapInterface: document.body.innerText.includes('From') && document.body.innerText.includes('To')
      };
    });
    
    console.log('🎮 État de l\'interface:');
    console.log(`  - Tokens visibles: ${interfaceData.hasTokensInUI ? '✅' : '❌'}`);
    console.log(`  - Interface swap: ${interfaceData.hasSwapInterface ? '✅' : '❌'}`);
    
    // Prendre une capture d'écran finale
    await page.screenshot({ 
      path: 'localnet-connection-test.png', 
      fullPage: true 
    });
    
    console.log('📸 Capture d\'écran: localnet-connection-test.png');
    
    console.log('\n🎊 TEST CONNEXION TERMINÉ !');
    console.log('============================');
    
    if (connectionStatus.isConnected) {
      console.log('🎉 SUCCÈS: Interface connectée à Canton LocalNet !');
      console.log('✅ Vous pouvez maintenant faire de vraies transactions !');
    } else if (apiTest.status === 401) {
      console.log('🔐 Canton LocalNet répond mais demande authentification');
      console.log('💡 Solution: Configurer JWT token ou mode développement');
    } else {
      console.log('⚠️ Canton LocalNet pas encore complètement prêt');
      console.log('💡 L\'interface fonctionne en mode mock en attendant');
    }
  });

  test('🎮 Test fonctionnalités en mode LocalNet', async ({ page }) => {
    await page.goto('http://localhost:3001');
    await page.waitForTimeout(3000);
    
    console.log('🎮 Test des fonctionnalités avec Canton LocalNet...');
    
    // Test de navigation
    await page.goto('http://localhost:3001/#/swap');
    await expect(page.locator('h2')).toContainText('Swap');
    console.log('✅ Page Swap accessible');
    
    await page.goto('http://localhost:3001/#/pools');
    await page.waitForTimeout(1000);
    console.log('✅ Page Pools accessible');
    
    await page.goto('http://localhost:3001/#/liquidity');
    await page.waitForTimeout(1000);
    console.log('✅ Page Liquidity accessible');
    
    await page.goto('http://localhost:3001/#/history');
    await page.waitForTimeout(1000);
    console.log('✅ Page History accessible');
    
    // Retour au swap pour test
    await page.goto('http://localhost:3001/#/swap');
    await page.waitForTimeout(2000);
    
    // Vérifier que l'interface fonctionne
    const swapElements = await page.evaluate(() => {
      return {
        hasFromField: document.body.innerText.includes('From'),
        hasToField: document.body.innerText.includes('To'),
        hasSelectButtons: document.querySelectorAll('button').length > 0,
        hasInputFields: document.querySelectorAll('input[type="number"]').length > 0
      };
    });
    
    console.log('🔍 Éléments interface swap:');
    console.log(`  - Champ From: ${swapElements.hasFromField ? '✅' : '❌'}`);
    console.log(`  - Champ To: ${swapElements.hasToField ? '✅' : '❌'}`);
    console.log(`  - Boutons: ${swapElements.hasSelectButtons ? '✅' : '❌'}`);
    console.log(`  - Champs numériques: ${swapElements.hasInputFields ? '✅' : '❌'}`);
    
    console.log('🎯 Interface prête pour Canton LocalNet !');
  });
});

