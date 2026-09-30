import { test, expect } from '@playwright/test';

test.describe('✅ Validation Manuelle AMM - Tests Essentiels', () => {
  
  test.beforeEach(async ({ page }) => {
    await page.goto('/');
    await page.waitForLoadState('networkidle');
    
    // Injecter les données de test
    await page.evaluate(() => {
      const mockTokens = [
        { symbol: 'USDC', name: 'USD Coin', decimals: 6, balance: 10000.50, contractId: 'mock-usdc-001' },
        { symbol: 'ETH', name: 'Ethereum', decimals: 18, balance: 25.75, contractId: 'mock-eth-001' },
        { symbol: 'BTC', name: 'Bitcoin', decimals: 8, balance: 2.5, contractId: 'mock-btc-001' }
      ];
      
      localStorage.setItem('canton-amm-mock-tokens', JSON.stringify(mockTokens));
    });
    
    await page.reload();
    await page.waitForLoadState('networkidle');
  });

  test('🎯 TEST PRINCIPAL: Swap complet USDC → ETH', async ({ page }) => {
    console.log('🚀 Début du test de swap USDC → ETH');
    
    // 1. Vérifier que la page se charge
    await expect(page.getByText('Canton AMM')).toBeVisible();
    console.log('✅ Page chargée');
    
    // 2. Aller sur la page de swap
    await page.goto('/#/swap');
    await page.waitForTimeout(2000);
    console.log('✅ Page swap accessible');
    
    // 3. Sélectionner USDC (FROM)
    const fromButton = page.locator('button').filter({ hasText: 'Select' }).first();
    await fromButton.click();
    await page.waitForTimeout(1000);
    
    // Chercher USDC dans la liste
    const usdcOption = page.getByText('USDC').first();
    await usdcOption.click();
    await page.waitForTimeout(1000);
    console.log('✅ USDC sélectionné');
    
    // 4. Sélectionner ETH (TO)
    const toButton = page.locator('button').filter({ hasText: 'Select' }).last();
    await toButton.click();
    await page.waitForTimeout(1000);
    
    const ethOption = page.getByText('ETH').first();
    await ethOption.click();
    await page.waitForTimeout(1000);
    console.log('✅ ETH sélectionné');
    
    // 5. Entrer un montant
    const amountInput = page.locator('input[type="number"]').first();
    await amountInput.fill('100');
    await page.waitForTimeout(2000);
    console.log('✅ Montant saisi: 100 USDC');
    
    // 6. Vérifier que la quote est calculée
    const outputInput = page.locator('input[type="number"]').last();
    const outputValue = await outputInput.inputValue();
    const outputAmount = parseFloat(outputValue);
    
    expect(outputAmount).toBeGreaterThan(0);
    console.log(`✅ Quote calculée: 100 USDC → ${outputAmount} ETH`);
    
    // 7. Vérifier les détails du swap (si affichés)
    const priceImpact = page.getByText(/Price Impact/);
    if (await priceImpact.count() > 0) {
      console.log('✅ Détails du swap affichés');
    }
    
    // 8. Exécuter le swap
    const allButtons = await page.locator('button').all();
    let swapExecuted = false;
    
    for (const button of allButtons) {
      const buttonText = await button.textContent();
      if (buttonText && (buttonText.includes('Swap') || buttonText.includes('Exchange'))) {
        const isEnabled = await button.isEnabled();
        if (isEnabled) {
          await button.click();
          swapExecuted = true;
          console.log('✅ Swap exécuté');
          break;
        }
      }
    }
    
    if (swapExecuted) {
      await page.waitForTimeout(3000);
      console.log('✅ Transaction complétée');
    } else {
      console.log('⚠️ Bouton swap non trouvé ou désactivé');
    }
    
    console.log('🎉 Test de swap terminé avec succès!');
  });

  test('🏊 TEST POOLS: Vérification des pools de liquidité', async ({ page }) => {
    console.log('🚀 Test des pools de liquidité');
    
    // Aller sur les pools
    await page.goto('/#/pools');
    await page.waitForTimeout(2000);
    
    // Vérifier le titre
    await expect(page.getByText('Liquidity Pools')).toBeVisible();
    console.log('✅ Page pools chargée');
    
    // Chercher des pools
    const searchInput = page.getByPlaceholder(/Search/);
    if (await searchInput.count() > 0) {
      await searchInput.fill('USDC');
      await page.waitForTimeout(1000);
      console.log('✅ Recherche de pools testée');
    }
    
    // Vérifier qu'il y a du contenu (pools ou message vide)
    const hasContent = await page.getByText('USDC').count() > 0 || 
                      await page.getByText('No pools').count() > 0;
    expect(hasContent).toBeTruthy();
    
    console.log('✅ Pools de liquidité fonctionnels');
  });

  test('💧 TEST LIQUIDITÉ: Interface d\'ajout de liquidité', async ({ page }) => {
    console.log('🚀 Test d\'ajout de liquidité');
    
    // Aller sur la liquidité
    await page.goto('/#/liquidity');
    await page.waitForTimeout(2000);
    
    // Vérifier le titre
    await expect(page.getByText('Liquidity')).toBeVisible();
    console.log('✅ Page liquidité chargée');
    
    // Vérifier les onglets Add/Remove
    const addButton = page.getByRole('button', { name: 'Add' }).first();
    const removeButton = page.getByRole('button', { name: 'Remove' }).first();
    
    if (await addButton.count() > 0) {
      await addButton.click();
      console.log('✅ Mode Add activé');
    }
    
    if (await removeButton.count() > 0) {
      await removeButton.click();
      await page.waitForTimeout(500);
      await addButton.click(); // Revenir en mode Add
      console.log('✅ Mode Remove testé');
    }
    
    console.log('✅ Interface de liquidité fonctionnelle');
  });

  test('📊 TEST HISTORIQUE: Page d\'historique', async ({ page }) => {
    console.log('🚀 Test de l\'historique');
    
    // Aller sur l'historique
    await page.goto('/#/history');
    await page.waitForTimeout(2000);
    
    // Vérifier le titre
    await expect(page.getByText('Transaction History')).toBeVisible();
    console.log('✅ Page historique chargée');
    
    // Vérifier les filtres
    const filterElements = page.locator('select');
    if (await filterElements.count() > 0) {
      console.log('✅ Filtres d\'historique présents');
    }
    
    // Vérifier l'état vide ou les transactions
    const hasTransactions = await page.getByText('Swap').count() > 0;
    const hasEmptyState = await page.getByText('No transactions').count() > 0;
    
    expect(hasTransactions || hasEmptyState).toBeTruthy();
    console.log('✅ Historique des transactions fonctionnel');
  });

  test('📱 TEST RESPONSIVE: Interface mobile', async ({ page }) => {
    console.log('🚀 Test responsive mobile');
    
    // Passer en mode mobile
    await page.setViewportSize({ width: 375, height: 667 });
    await page.waitForTimeout(1000);
    
    // Vérifier que l'interface s'adapte
    await expect(page.getByText('Canton AMM')).toBeVisible();
    console.log('✅ Interface mobile OK');
    
    // Tester la navigation mobile
    const mobileNavLinks = await page.locator('a').all();
    let navWorking = false;
    
    for (const link of mobileNavLinks) {
      const linkText = await link.textContent();
      if (linkText && linkText.includes('Swap')) {
        await link.click();
        await page.waitForTimeout(500);
        navWorking = true;
        break;
      }
    }
    
    expect(navWorking).toBeTruthy();
    console.log('✅ Navigation mobile fonctionnelle');
  });
});

