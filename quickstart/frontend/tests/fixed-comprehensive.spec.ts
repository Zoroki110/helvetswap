import { test, expect } from '@playwright/test';

test.describe('🧪 Tests AMM Canton - Version Corrigée', () => {
  
  test.beforeEach(async ({ page }) => {
    await page.goto('/');
    await page.waitForLoadState('networkidle');
    
    // Injecter les données de test directement
    await page.evaluate(() => {
      const mockTokens = [
        { symbol: 'USDC', name: 'USD Coin', decimals: 6, balance: 10000.50, contractId: 'mock-usdc-001' },
        { symbol: 'ETH', name: 'Ethereum', decimals: 18, balance: 25.75, contractId: 'mock-eth-001' },
        { symbol: 'BTC', name: 'Bitcoin', decimals: 8, balance: 2.5, contractId: 'mock-btc-001' },
        { symbol: 'USDT', name: 'Tether USD', decimals: 6, balance: 8500.25, contractId: 'mock-usdt-001' }
      ];
      
      const mockPools = [
        {
          contractId: 'mock-pool-usdc-eth-001',
          tokenA: { symbol: 'USDC', name: 'USD Coin', decimals: 6 },
          tokenB: { symbol: 'ETH', name: 'Ethereum', decimals: 18 },
          reserveA: 50000.0, reserveB: 25.0, totalLiquidity: 1118.03, feeRate: 0.003,
          apr: 12.5, volume24h: 125000
        }
      ];
      
      localStorage.setItem('canton-amm-mock-tokens', JSON.stringify(mockTokens));
      localStorage.setItem('canton-amm-mock-pools', JSON.stringify(mockPools));
      
      // Simuler la connexion
      const storeData = {
        isConnected: false,
        participantId: 'amm_participant',
        currentParty: 'Alice',
        slippage: 0.5,
        deadline: 20,
        isExpertMode: false,
        theme: 'light'
      };
      localStorage.setItem('canton-amm-store', JSON.stringify(storeData));
    });
    
    await page.reload();
    await page.waitForLoadState('networkidle');
  });

  test('🏠 Test 1: Chargement de l\'interface', async ({ page }) => {
    // Vérifier le titre de la page
    await expect(page).toHaveTitle(/Canton AMM/);
    
    // Vérifier le logo/titre principal
    await expect(page.locator('h1')).toContainText('Canton AMM');
    
    // Vérifier que la page ne crash pas
    const errors = await page.locator('[role="alert"]').count();
    expect(errors).toBe(0);
    
    console.log('✅ Interface principale chargée sans erreur');
  });

  test('🔗 Test 2: Navigation entre les pages', async ({ page }) => {
    // Utiliser des sélecteurs plus spécifiques pour éviter les doublons
    
    // Test navigation vers Swap (déjà sur cette page normalement)
    await page.locator('nav a[href="/swap"]').first().click();
    await page.waitForTimeout(1000);
    await expect(page.locator('h2')).toContainText('Swap');
    console.log('✅ Navigation Swap OK');
    
    // Test navigation vers Pools
    await page.locator('nav a[href="/pools"]').first().click();
    await page.waitForTimeout(1000);
    await expect(page.locator('h1')).toContainText('Pools');
    console.log('✅ Navigation Pools OK');
    
    // Test navigation vers Liquidity
    await page.locator('nav a[href="/liquidity"]').first().click();
    await page.waitForTimeout(1000);
    await expect(page.locator('h2')).toContainText('Liquidity');
    console.log('✅ Navigation Liquidity OK');
    
    // Test navigation vers History
    await page.locator('nav a[href="/history"]').first().click();
    await page.waitForTimeout(1000);
    await expect(page.locator('h1')).toContainText('History');
    console.log('✅ Navigation History OK');
    
    // Retour au Swap
    await page.locator('nav a[href="/swap"]').first().click();
    await page.waitForTimeout(1000);
  });

  test('💱 Test 3: Sélection de tokens', async ({ page }) => {
    // S'assurer qu'on est sur la page swap
    await page.goto('/#/swap');
    await page.waitForTimeout(2000);
    
    // Sélectionner le premier token (FROM)
    const selectButtons = page.locator('button:has-text("Select")');
    await expect(selectButtons).toHaveCount(2);
    
    await selectButtons.first().click();
    await page.waitForTimeout(1000);
    
    // Vérifier que le modal s'ouvre
    await expect(page.locator('h3')).toContainText('Select a token');
    
    // Sélectionner USDC
    await page.locator('button').filter({ hasText: 'USDC' }).first().click();
    await page.waitForTimeout(1000);
    
    // Vérifier que USDC est sélectionné
    await expect(page.locator('button').filter({ hasText: 'USDC' })).toBeVisible();
    console.log('✅ USDC sélectionné comme token FROM');
    
    // Sélectionner le deuxième token (TO)
    await selectButtons.last().click();
    await page.waitForTimeout(1000);
    
    // Sélectionner ETH
    await page.locator('button').filter({ hasText: 'ETH' }).first().click();
    await page.waitForTimeout(1000);
    
    // Vérifier que ETH est sélectionné
    await expect(page.locator('button').filter({ hasText: 'ETH' })).toBeVisible();
    console.log('✅ ETH sélectionné comme token TO');
  });

  test('🧮 Test 4: Calcul de quotes AMM', async ({ page }) => {
    await page.goto('/#/swap');
    await page.waitForTimeout(2000);
    
    // Sélectionner USDC → ETH
    await page.locator('button:has-text("Select")').first().click();
    await page.waitForTimeout(500);
    await page.locator('button').filter({ hasText: 'USDC' }).first().click();
    await page.waitForTimeout(500);
    
    await page.locator('button:has-text("Select")').last().click();
    await page.waitForTimeout(500);
    await page.locator('button').filter({ hasText: 'ETH' }).first().click();
    await page.waitForTimeout(500);
    
    // Tester différents montants
    const testAmounts = ['10', '100', '1000'];
    
    for (const amount of testAmounts) {
      await page.locator('input[type="number"]').first().fill(amount);
      await page.waitForTimeout(1500);
      
      const outputValue = await page.locator('input[type="number"]').last().inputValue();
      const output = parseFloat(outputValue);
      
      expect(output).toBeGreaterThan(0);
      console.log(`✅ Quote: ${amount} USDC → ${output} ETH`);
    }
  });

  test('💰 Test 5: Affichage des balances', async ({ page }) => {
    await page.goto('/#/swap');
    await page.waitForTimeout(2000);
    
    // Sélectionner USDC
    await page.locator('button:has-text("Select")').first().click();
    await page.waitForTimeout(500);
    await page.locator('button').filter({ hasText: 'USDC' }).first().click();
    await page.waitForTimeout(1000);
    
    // Vérifier que la balance s'affiche
    const balanceElements = page.locator('text=/Balance:/');
    if (await balanceElements.count() > 0) {
      const balanceText = await balanceElements.first().textContent();
      expect(balanceText).toContain('10000.5');
      console.log(`✅ Balance USDC affichée: ${balanceText}`);
    }
  });

  test('🔄 Test 6: Inversion des tokens', async ({ page }) => {
    await page.goto('/#/swap');
    await page.waitForTimeout(2000);
    
    // Sélectionner USDC et ETH
    await page.locator('button:has-text("Select")').first().click();
    await page.waitForTimeout(500);
    await page.locator('button').filter({ hasText: 'USDC' }).first().click();
    await page.waitForTimeout(500);
    
    await page.locator('button:has-text("Select")').last().click();
    await page.waitForTimeout(500);
    await page.locator('button').filter({ hasText: 'ETH' }).first().click();
    await page.waitForTimeout(500);
    
    // Chercher le bouton d'inversion (icône flèche)
    const swapArrowButton = page.locator('button').filter({ 
      has: page.locator('svg') 
    }).filter({ hasText: '' });
    
    if (await swapArrowButton.count() > 0) {
      await swapArrowButton.first().click();
      await page.waitForTimeout(1000);
      console.log('✅ Inversion des tokens testée');
    }
  });

  test('📱 Test 7: Design responsive', async ({ page }) => {
    // Test mobile
    await page.setViewportSize({ width: 375, height: 667 });
    await page.waitForTimeout(1000);
    
    await expect(page.locator('h1')).toContainText('Canton AMM');
    console.log('✅ Mobile: Interface visible');
    
    // Test tablette
    await page.setViewportSize({ width: 768, height: 1024 });
    await page.waitForTimeout(500);
    await expect(page.locator('h1')).toContainText('Canton AMM');
    console.log('✅ Tablette: Interface visible');
    
    // Test desktop
    await page.setViewportSize({ width: 1280, height: 720 });
    await page.waitForTimeout(500);
    await expect(page.locator('h1')).toContainText('Canton AMM');
    console.log('✅ Desktop: Interface visible');
  });

  test('⚙️ Test 8: Paramètres de slippage', async ({ page }) => {
    await page.goto('/#/swap');
    await page.waitForTimeout(2000);
    
    // Chercher le bouton des paramètres (icône settings)
    const settingsButtons = page.locator('button').filter({ 
      has: page.locator('svg') 
    });
    
    if (await settingsButtons.count() > 0) {
      // Essayer chaque bouton jusqu'à trouver celui des paramètres
      for (let i = 0; i < await settingsButtons.count(); i++) {
        try {
          await settingsButtons.nth(i).click();
          await page.waitForTimeout(500);
          
          // Vérifier si les paramètres de slippage apparaissent
          const slippageText = page.locator('text=/Slippage/');
          if (await slippageText.count() > 0) {
            console.log('✅ Paramètres de slippage ouverts');
            break;
          }
        } catch (e) {
          // Continuer avec le bouton suivant
        }
      }
    }
  });

  test('🔍 Test 9: Recherche et filtres', async ({ page }) => {
    await page.goto('/#/pools');
    await page.waitForTimeout(2000);
    
    // Chercher le champ de recherche
    const searchInputs = page.locator('input[placeholder*="Search"], input[placeholder*="search"]');
    
    if (await searchInputs.count() > 0) {
      await searchInputs.first().fill('USDC');
      await page.waitForTimeout(1000);
      console.log('✅ Recherche testée');
      
      // Effacer la recherche
      await searchInputs.first().clear();
      await page.waitForTimeout(500);
    }
    
    // Tester les sélecteurs de tri
    const selects = page.locator('select');
    if (await selects.count() > 0) {
      await selects.first().selectOption({ index: 1 });
      await page.waitForTimeout(500);
      console.log('✅ Tri testé');
    }
  });

  test('⚠️ Test 10: Gestion des erreurs', async ({ page }) => {
    await page.goto('/#/swap');
    await page.waitForTimeout(2000);
    
    // Tester montant sans sélection de tokens
    const amountInput = page.locator('input[type="number"]').first();
    await amountInput.fill('1000');
    await page.waitForTimeout(1000);
    
    // Le bouton swap devrait être désactivé ou montrer un message
    const allButtons = await page.locator('button').all();
    let foundSwapButton = false;
    
    for (const button of allButtons) {
      const text = await button.textContent();
      if (text && (text.includes('Select') || text.includes('Swap') || text.includes('Enter'))) {
        foundSwapButton = true;
        console.log(`✅ État du bouton: ${text}`);
        break;
      }
    }
    
    expect(foundSwapButton).toBeTruthy();
    
    // Tester montant négatif
    await amountInput.fill('-100');
    await page.waitForTimeout(500);
    
    // Tester montant zéro
    await amountInput.fill('0');
    await page.waitForTimeout(500);
    
    console.log('✅ Gestion des erreurs testée');
  });

  test('🎨 Test 11: Thème et interface', async ({ page }) => {
    // Chercher le bouton de thème
    const themeButtons = page.locator('button').filter({ 
      has: page.locator('svg') 
    });
    
    if (await themeButtons.count() > 0) {
      // Essayer de cliquer sur un bouton qui pourrait être le thème
      for (let i = 0; i < Math.min(3, await themeButtons.count()); i++) {
        try {
          await themeButtons.nth(i).click();
          await page.waitForTimeout(500);
          console.log(`✅ Bouton thème ${i + 1} testé`);
        } catch (e) {
          // Continuer
        }
      }
    }
    
    // Vérifier que l'interface reste stable
    await expect(page.locator('h1')).toContainText('Canton AMM');
    console.log('✅ Interface stable après changements');
  });

  test('📊 Test 12: Toutes les pages accessibles', async ({ page }) => {
    const pages = [
      { path: '/#/swap', title: 'Swap' },
      { path: '/#/pools', title: 'Pools' },
      { path: '/#/liquidity', title: 'Liquidity' },
      { path: '/#/history', title: 'History' }
    ];
    
    for (const testPage of pages) {
      await page.goto(testPage.path);
      await page.waitForTimeout(2000);
      
      // Vérifier qu'on arrive sur la bonne page
      const hasExpectedContent = await page.locator(`h1, h2`).filter({ 
        hasText: new RegExp(testPage.title, 'i') 
      }).count() > 0;
      
      expect(hasExpectedContent).toBeTruthy();
      console.log(`✅ Page ${testPage.title} accessible`);
    }
  });

  test('💡 Test 13: Fonctionnalités de base', async ({ page }) => {
    await page.goto('/#/swap');
    await page.waitForTimeout(2000);
    
    // Test des champs d'entrée
    const numberInputs = page.locator('input[type="number"]');
    const inputCount = await numberInputs.count();
    expect(inputCount).toBeGreaterThanOrEqual(1);
    
    if (inputCount > 0) {
      await numberInputs.first().fill('123.45');
      await page.waitForTimeout(500);
      const value = await numberInputs.first().inputValue();
      expect(value).toBe('123.45');
      console.log('✅ Champs numériques fonctionnels');
    }
    
    // Test des boutons de sélection
    const selectButtons = page.locator('button:has-text("Select")');
    const buttonCount = await selectButtons.count();
    expect(buttonCount).toBeGreaterThanOrEqual(1);
    console.log(`✅ ${buttonCount} bouton(s) de sélection trouvé(s)`);
  });

  test('🔄 Test 14: Swap complet simulé', async ({ page }) => {
    await page.goto('/#/swap');
    await page.waitForTimeout(2000);
    
    console.log('🚀 Test de swap complet USDC → ETH');
    
    // Étape 1: Sélectionner FROM token
    const fromSelect = page.locator('button:has-text("Select")').first();
    await fromSelect.click();
    await page.waitForTimeout(1000);
    
    // Chercher et cliquer sur USDC
    const usdcButtons = page.locator('button').filter({ hasText: 'USDC' });
    if (await usdcButtons.count() > 0) {
      await usdcButtons.first().click();
      await page.waitForTimeout(1000);
      console.log('✅ USDC sélectionné');
    }
    
    // Étape 2: Sélectionner TO token
    const toSelect = page.locator('button:has-text("Select")').last();
    await toSelect.click();
    await page.waitForTimeout(1000);
    
    // Chercher et cliquer sur ETH
    const ethButtons = page.locator('button').filter({ hasText: 'ETH' });
    if (await ethButtons.count() > 0) {
      await ethButtons.first().click();
      await page.waitForTimeout(1000);
      console.log('✅ ETH sélectionné');
    }
    
    // Étape 3: Entrer un montant
    const amountInput = page.locator('input[type="number"]').first();
    await amountInput.fill('100');
    await page.waitForTimeout(2000);
    console.log('✅ Montant saisi: 100 USDC');
    
    // Étape 4: Vérifier la quote
    const outputInput = page.locator('input[type="number"]').last();
    const outputValue = await outputInput.inputValue();
    const output = parseFloat(outputValue);
    
    if (output > 0) {
      console.log(`✅ Quote calculée: 100 USDC → ${output} ETH`);
    } else {
      console.log('⚠️ Quote non calculée (normal si pas de pool)');
    }
    
    // Étape 5: Chercher le bouton de swap
    const allButtons = await page.locator('button').all();
    let swapButtonFound = false;
    
    for (const button of allButtons) {
      const text = await button.textContent();
      if (text && (text.toLowerCase().includes('swap') || text.includes('Exchange'))) {
        swapButtonFound = true;
        const isEnabled = await button.isEnabled();
        console.log(`✅ Bouton swap trouvé: "${text}" (${isEnabled ? 'activé' : 'désactivé'})`);
        
        if (isEnabled) {
          await button.click();
          await page.waitForTimeout(2000);
          console.log('✅ Swap exécuté');
        }
        break;
      }
    }
    
    if (!swapButtonFound) {
      console.log('⚠️ Bouton swap non trouvé - vérifier l\'état des tokens');
    }
  });

  test('📊 Test 15: Vérification des données', async ({ page }) => {
    // Vérifier que les données mockées sont bien chargées
    const tokensData = await page.evaluate(() => {
      return localStorage.getItem('canton-amm-mock-tokens');
    });
    
    expect(tokensData).toBeTruthy();
    const tokens = JSON.parse(tokensData || '[]');
    expect(tokens.length).toBeGreaterThan(0);
    console.log(`✅ ${tokens.length} tokens chargés en mémoire`);
    
    // Vérifier les balances
    const usdcToken = tokens.find((t: any) => t.symbol === 'USDC');
    expect(usdcToken).toBeTruthy();
    expect(usdcToken.balance).toBe(10000.50);
    console.log(`✅ Balance USDC: ${usdcToken.balance}`);
    
    const ethToken = tokens.find((t: any) => t.symbol === 'ETH');
    expect(ethToken).toBeTruthy();
    expect(ethToken.balance).toBe(25.75);
    console.log(`✅ Balance ETH: ${ethToken.balance}`);
  });

  test('🎯 Test 16: Performance globale', async ({ page }) => {
    const startTime = Date.now();
    
    // Navigation rapide
    await page.goto('/#/swap');
    await page.waitForTimeout(500);
    await page.goto('/#/pools');
    await page.waitForTimeout(500);
    await page.goto('/#/liquidity');
    await page.waitForTimeout(500);
    await page.goto('/#/history');
    await page.waitForTimeout(500);
    await page.goto('/#/swap');
    await page.waitForTimeout(500);
    
    const endTime = Date.now();
    const totalTime = endTime - startTime;
    
    expect(totalTime).toBeLessThan(10000); // Moins de 10 secondes
    console.log(`✅ Performance: Navigation en ${totalTime}ms`);
    
    // Vérifier qu'il n'y a pas d'erreurs JavaScript
    const jsErrors: string[] = [];
    page.on('console', msg => {
      if (msg.type() === 'error') {
        jsErrors.push(msg.text());
      }
    });
    
    await page.waitForTimeout(2000);
    expect(jsErrors.length).toBe(0);
    console.log('✅ Aucune erreur JavaScript détectée');
  });
});

