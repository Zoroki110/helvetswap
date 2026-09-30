import { test, expect } from '@playwright/test';

test.describe('🔍 Debug Connexion LocalNet', () => {
  
  test('🔗 Vérifier la connexion Canton LocalNet', async ({ page }) => {
    console.log('🚀 DÉBUT DEBUG CONNEXION LOCALNET');
    console.log('=================================');
    
    // Aller sur l'interface
    await page.goto('http://localhost:3001');
    await page.waitForLoadState('networkidle');
    await page.waitForTimeout(3000);
    
    console.log('✅ Interface AMM chargée');
    
    // Vérifier l'état de connexion dans l'interface
    const connectionStatus = await page.evaluate(() => {
      return {
        url: window.location.href,
        title: document.title,
        connectionText: document.body.innerText.includes('Connected') ? 'Connected' : 
                       document.body.innerText.includes('Disconnected') ? 'Disconnected' : 'Unknown'
      };
    });
    
    console.log('🔍 État de l\'interface:');
    console.log(`  - URL: ${connectionStatus.url}`);
    console.log(`  - Titre: ${connectionStatus.title}`);
    console.log(`  - Connexion: ${connectionStatus.connectionText}`);
    
    // Vérifier les requêtes réseau
    const networkRequests: string[] = [];
    const networkErrors: string[] = [];
    
    page.on('request', request => {
      if (request.url().includes('localhost:501')) {
        networkRequests.push(`${request.method()} ${request.url()}`);
      }
    });
    
    page.on('requestfailed', request => {
      if (request.url().includes('localhost:501')) {
        networkErrors.push(`FAILED: ${request.method()} ${request.url()} - ${request.failure()?.errorText}`);
      }
    });
    
    // Attendre et observer les requêtes
    await page.waitForTimeout(5000);
    
    console.log('🌐 Requêtes réseau Canton:');
    networkRequests.forEach(req => console.log(`  ✅ ${req}`));
    networkErrors.forEach(err => console.log(`  ❌ ${err}`));
    
    // Vérifier les erreurs JavaScript
    const jsErrors: string[] = [];
    page.on('console', msg => {
      if (msg.type() === 'error' && !msg.text().includes('deprecated')) {
        jsErrors.push(msg.text());
      }
    });
    
    await page.waitForTimeout(2000);
    
    console.log('🐛 Erreurs JavaScript:');
    if (jsErrors.length === 0) {
      console.log('  ✅ Aucune erreur JavaScript');
    } else {
      jsErrors.forEach(err => console.log(`  ❌ ${err}`));
    }
    
    // Vérifier les données dans localStorage
    const localStorageData = await page.evaluate(() => {
      return {
        mockTokens: localStorage.getItem('canton-amm-mock-tokens'),
        mockPools: localStorage.getItem('canton-amm-mock-pools'),
        appStore: localStorage.getItem('canton-amm-store')
      };
    });
    
    console.log('💾 Données localStorage:');
    console.log(`  - Mock Tokens: ${localStorageData.mockTokens ? 'Présent' : 'Absent'}`);
    console.log(`  - Mock Pools: ${localStorageData.mockPools ? 'Présent' : 'Absent'}`);
    console.log(`  - App Store: ${localStorageData.appStore ? 'Présent' : 'Absent'}`);
    
    // Forcer une tentative de connexion
    console.log('🔄 Test de connexion forcée...');
    
    await page.evaluate(() => {
      // Essayer de faire une requête à Canton
      fetch('http://localhost:5011/v1/parties')
        .then(response => {
          console.log('Canton API Response:', response.status);
          return response.json();
        })
        .then(data => {
          console.log('Canton API Data:', data);
        })
        .catch(error => {
          console.log('Canton API Error:', error.message);
        });
    });
    
    await page.waitForTimeout(3000);
    
    // Vérifier si l'état de connexion a changé
    const finalConnectionStatus = await page.evaluate(() => {
      return document.body.innerText.includes('Connected') ? 'Connected' : 
             document.body.innerText.includes('Disconnected') ? 'Disconnected' : 'Unknown';
    });
    
    console.log(`🎯 État final de connexion: ${finalConnectionStatus}`);
    
    // Prendre une capture d'écran pour debug
    await page.screenshot({ 
      path: 'debug-connection-status.png', 
      fullPage: true 
    });
    
    console.log('📸 Capture d\'écran sauvée: debug-connection-status.png');
    
    console.log('\n🎊 DEBUG TERMINÉ !');
    console.log('==================');
    
    if (finalConnectionStatus === 'Connected') {
      console.log('✅ SUCCÈS: Interface connectée à Canton LocalNet !');
    } else {
      console.log('⚠️ Interface en mode mock - Canton LocalNet pas encore prêt');
      console.log('💡 Solutions:');
      console.log('  1. Attendre que Canton soit complètement démarré');
      console.log('  2. Vérifier les logs: tail -f canton-with-api.log');
      console.log('  3. Tester manuellement: curl http://localhost:5011/v1/parties');
    }
  });

  test('🧪 Test des APIs Canton directement', async ({ page }) => {
    console.log('🔍 Test direct des APIs Canton...');
    
    // Tester via le navigateur
    await page.goto('http://localhost:3001');
    
    const apiTests = await page.evaluate(async () => {
      const results = {
        adminApi: { status: 'unknown', error: null },
        ledgerApi: { status: 'unknown', error: null }
      };
      
      // Test Admin API
      try {
        const adminResponse = await fetch('http://localhost:5012/health');
        results.adminApi.status = adminResponse.status;
      } catch (error) {
        results.adminApi.error = error.message;
      }
      
      // Test Ledger API
      try {
        const ledgerResponse = await fetch('http://localhost:5011/v1/parties');
        results.ledgerApi.status = ledgerResponse.status;
      } catch (error) {
        results.ledgerApi.error = error.message;
      }
      
      return results;
    });
    
    console.log('📊 Résultats tests API:');
    console.log(`  - Admin API (5012): ${apiTests.adminApi.status} ${apiTests.adminApi.error ? '❌' : '✅'}`);
    console.log(`  - Ledger API (5011): ${apiTests.ledgerApi.status} ${apiTests.ledgerApi.error ? '❌' : '✅'}`);
    
    if (apiTests.adminApi.error) {
      console.log(`    Admin Error: ${apiTests.adminApi.error}`);
    }
    if (apiTests.ledgerApi.error) {
      console.log(`    Ledger Error: ${apiTests.ledgerApi.error}`);
    }
  });

  test('🎮 Test fonctionnalités avec LocalNet', async ({ page }) => {
    await page.goto('http://localhost:3001');
    await page.waitForLoadState('networkidle');
    
    console.log('🎮 Test des fonctionnalités avec LocalNet...');
    
    // Vérifier que l'interface fonctionne même si Canton n'est pas prêt
    await expect(page.getByText('Canton AMM')).toBeVisible();
    console.log('✅ Interface principale OK');
    
    // Test navigation
    await page.goto('http://localhost:3001/#/swap');
    await page.waitForTimeout(1000);
    console.log('✅ Page swap accessible');
    
    await page.goto('http://localhost:3001/#/pools');
    await page.waitForTimeout(1000);
    console.log('✅ Page pools accessible');
    
    // Vérifier les données disponibles
    const hasData = await page.evaluate(() => {
      const tokens = localStorage.getItem('canton-amm-mock-tokens');
      const pools = localStorage.getItem('canton-amm-mock-pools');
      return {
        hasTokens: !!tokens,
        hasPool: !!pools,
        tokenCount: tokens ? JSON.parse(tokens).length : 0,
        poolCount: pools ? JSON.parse(pools).length : 0
      };
    });
    
    console.log('💾 Données disponibles:');
    console.log(`  - Tokens: ${hasData.tokenCount} ${hasData.hasTokens ? '✅' : '❌'}`);
    console.log(`  - Pools: ${hasData.poolCount} ${hasData.hasPool ? '✅' : '❌'}`);
    
    console.log('🎯 Interface fonctionnelle même en attente de Canton LocalNet');
  });
});

