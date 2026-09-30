import { test, expect } from '@playwright/test';

test.describe('Pools and Liquidity Management', () => {
  
  test.beforeEach(async ({ page }) => {
    await page.goto('/');
    await page.waitForLoadState('networkidle');
  });

  test.describe('Pools Interface', () => {
    test.beforeEach(async ({ page }) => {
      await page.locator('text=Pools').click();
      await expect(page.locator('h1')).toContainText('Liquidity Pools');
    });

    test('should display pools page correctly', async ({ page }) => {
      await expect(page.locator('h1')).toContainText('Liquidity Pools');
      await expect(page.locator('text=Discover and analyze liquidity pools')).toBeVisible();
      
      // Vérifier les contrôles de recherche et filtre
      const searchInput = page.locator('input[placeholder*="Search"]');
      await expect(searchInput).toBeVisible();
      
      const sortSelect = page.locator('select').or(
        page.locator('text=Sort by')
      );
      if (await sortSelect.count() > 0) {
        await expect(sortSelect).toBeVisible();
      }
    });

    test('should handle pool search', async ({ page }) => {
      const searchInput = page.locator('input[placeholder*="Search"]');
      
      if (await searchInput.count() > 0) {
        await searchInput.fill('USDC');
        await page.waitForTimeout(500);
        
        // Vérifier que la recherche fonctionne (même s'il n'y a pas de résultats)
        // L'interface ne devrait pas crasher
      }
    });

    test('should display empty state when no pools', async ({ page }) => {
      // Si aucun pool n'est disponible, vérifier l'état vide
      const emptyState = page.locator('text=No pools found').or(
        page.locator('text=No liquidity pools')
      );
      
      // Soit il y a des pools, soit il y a un état vide
      const poolCards = page.locator('[data-testid="pool-card"]').or(
        page.locator('.card').filter({ hasText: '/' })
      );
      
      const hasPoolsOrEmptyState = await poolCards.count() > 0 || await emptyState.count() > 0;
      expect(hasPoolsOrEmptyState).toBeTruthy();
    });
  });

  test.describe('Liquidity Interface', () => {
    test.beforeEach(async ({ page }) => {
      await page.locator('text=Liquidity').click();
      await expect(page.locator('h2')).toContainText('Liquidity');
    });

    test('should display liquidity management interface', async ({ page }) => {
      await expect(page.locator('h2')).toContainText('Liquidity');
      
      // Vérifier les onglets Add/Remove
      await expect(page.locator('text=Add')).toBeVisible();
      await expect(page.locator('text=Remove')).toBeVisible();
    });

    test('should switch between Add and Remove modes', async ({ page }) => {
      // Tester le mode Add (par défaut)
      const addTab = page.locator('button:has-text("Add")');
      const removeTab = page.locator('button:has-text("Remove")');
      
      if (await addTab.count() > 0 && await removeTab.count() > 0) {
        // Vérifier que Add est actif par défaut
        await expect(addTab).toHaveClass(/primary|active|selected/);
        
        // Passer en mode Remove
        await removeTab.click();
        await expect(removeTab).toHaveClass(/primary|active|selected/);
        
        // Revenir en mode Add
        await addTab.click();
        await expect(addTab).toHaveClass(/primary|active|selected/);
      }
    });

    test('should display add liquidity form', async ({ page }) => {
      // S'assurer qu'on est en mode Add
      const addTab = page.locator('button:has-text("Add")');
      if (await addTab.count() > 0) {
        await addTab.click();
      }
      
      // Vérifier les éléments du formulaire
      await expect(page.locator('text=Token A')).toBeVisible();
      await expect(page.locator('text=Token B')).toBeVisible();
      
      // Vérifier les champs d'entrée
      const inputFields = page.locator('input[type="number"]');
      await expect(inputFields).toHaveCount(2);
      
      // Vérifier les boutons de sélection
      const selectButtons = page.locator('button:has-text("Select")');
      await expect(selectButtons).toHaveCount(2);
      
      // Vérifier le bouton d'ajout de liquidité
      const addLiquidityButton = page.locator('button:has-text("Add Liquidity")');
      await expect(addLiquidityButton).toBeVisible();
    });

    test('should display remove liquidity form', async ({ page }) => {
      const removeTab = page.locator('button:has-text("Remove")');
      if (await removeTab.count() > 0) {
        await removeTab.click();
        
        // Vérifier les éléments du formulaire de retrait
        const poolSelector = page.locator('select').or(
          page.locator('text=Select Pool')
        );
        
        if (await poolSelector.count() > 0) {
          await expect(poolSelector).toBeVisible();
        }
        
        // Vérifier le bouton de retrait
        const removeLiquidityButton = page.locator('button:has-text("Remove Liquidity")');
        await expect(removeLiquidityButton).toBeVisible();
      }
    });

    test('should handle liquidity form inputs', async ({ page }) => {
      // Tester la saisie dans les champs de liquidité
      const amountInputs = page.locator('input[type="number"]');
      
      if (await amountInputs.count() >= 2) {
        await amountInputs.first().fill('1000');
        await expect(amountInputs.first()).toHaveValue('1000');
        
        await amountInputs.nth(1).fill('1');
        await expect(amountInputs.nth(1)).toHaveValue('1');
      }
    });

    test('should show liquidity button states', async ({ page }) => {
      const liquidityButton = page.locator('button').filter({
        hasText: /Add Liquidity|Remove Liquidity|Select/
      }).last();
      
      await expect(liquidityButton).toBeVisible();
      
      // Le bouton devrait être désactivé sans sélection de tokens
      const isDisabled = await liquidityButton.getAttribute('disabled');
      // Test de l'état du bouton (désactivé par défaut)
    });
  });

  test.describe('Responsive Design', () => {
    test('should work on mobile - Pools', async ({ page }) => {
      await page.setViewportSize({ width: 375, height: 667 });
      await page.locator('text=Pools').click();
      
      await expect(page.locator('h1')).toContainText('Liquidity Pools');
      
      // L'interface devrait rester utilisable
      const searchInput = page.locator('input[placeholder*="Search"]');
      if (await searchInput.count() > 0) {
        await expect(searchInput).toBeVisible();
      }
    });

    test('should work on mobile - Liquidity', async ({ page }) => {
      await page.setViewportSize({ width: 375, height: 667 });
      await page.locator('text=Liquidity').click();
      
      await expect(page.locator('h2')).toContainText('Liquidity');
      await expect(page.locator('text=Add')).toBeVisible();
      await expect(page.locator('text=Remove')).toBeVisible();
    });
  });
});

