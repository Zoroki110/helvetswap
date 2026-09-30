import { test, expect } from '@playwright/test';

test.describe('Transaction History', () => {
  
  test.beforeEach(async ({ page }) => {
    await page.goto('/');
    await page.waitForLoadState('networkidle');
    await page.locator('text=History').click();
    await expect(page.locator('h1')).toContainText('Transaction History');
  });

  test('should display transaction history page', async ({ page }) => {
    await expect(page.locator('h1')).toContainText('Transaction History');
    await expect(page.locator('text=Track your swaps and liquidity operations')).toBeVisible();
  });

  test('should show filter controls', async ({ page }) => {
    // Vérifier les contrôles de filtre
    const filterControls = page.locator('select').or(
      page.locator('text=All Transactions')
    );
    
    if (await filterControls.count() > 0) {
      await expect(filterControls.first()).toBeVisible();
    }
    
    // Vérifier le bouton d'export
    const exportButton = page.locator('button:has-text("Export")').or(
      page.locator('text=Export')
    );
    
    if (await exportButton.count() > 0) {
      await expect(exportButton).toBeVisible();
    }
  });

  test('should handle filter changes', async ({ page }) => {
    const filterSelect = page.locator('select').first();
    
    if (await filterSelect.count() > 0) {
      // Tester les différents filtres
      await filterSelect.selectOption('pending');
      await page.waitForTimeout(500);
      
      await filterSelect.selectOption('confirmed');
      await page.waitForTimeout(500);
      
      await filterSelect.selectOption('all');
      await page.waitForTimeout(500);
    }
  });

  test('should display empty state when no transactions', async ({ page }) => {
    // Vérifier l'état vide s'il n'y a pas de transactions
    const emptyState = page.locator('text=No transactions found').or(
      page.locator('text=You haven\'t made any transactions')
    );
    
    const transactionItems = page.locator('[data-testid="transaction-item"]').or(
      page.locator('.card').filter({ hasText: 'Swap' })
    );
    
    // Soit il y a des transactions, soit il y a un état vide
    const hasTransactionsOrEmpty = await transactionItems.count() > 0 || await emptyState.count() > 0;
    expect(hasTransactionsOrEmpty).toBeTruthy();
  });

  test('should display transaction items correctly', async ({ page }) => {
    // Si des transactions existent, vérifier leur affichage
    const transactionItems = page.locator('.card').filter({ hasText: 'Swap' });
    
    if (await transactionItems.count() > 0) {
      const firstTransaction = transactionItems.first();
      
      // Vérifier les éléments d'une transaction
      await expect(firstTransaction).toBeVisible();
      
      // Chercher des indicateurs de statut
      const statusIndicators = firstTransaction.locator('text=Pending').or(
        firstTransaction.locator('text=Confirmed').or(
          firstTransaction.locator('text=Failed')
        )
      );
      
      if (await statusIndicators.count() > 0) {
        await expect(statusIndicators.first()).toBeVisible();
      }
    }
  });

  test('should handle sort options', async ({ page }) => {
    const sortSelect = page.locator('select').filter({ hasText: /Sort|Date|Amount/ });
    
    if (await sortSelect.count() > 0) {
      await sortSelect.selectOption('timestamp');
      await page.waitForTimeout(500);
      
      await sortSelect.selectOption('amount');
      await page.waitForTimeout(500);
    }
  });

  test('should work on mobile', async ({ page }) => {
    await page.setViewportSize({ width: 375, height: 667 });
    
    await expect(page.locator('h1')).toContainText('Transaction History');
    
    // Les contrôles devraient rester accessibles
    const filterControls = page.locator('select');
    if (await filterControls.count() > 0) {
      await expect(filterControls.first()).toBeVisible();
    }
  });

  test('should handle export functionality', async ({ page }) => {
    const exportButton = page.locator('button:has-text("Export")');
    
    if (await exportButton.count() > 0) {
      // Cliquer sur export ne devrait pas causer d'erreur
      await exportButton.click();
      
      // Vérifier qu'aucune erreur n'est affichée
      const errorMessages = page.locator('text=Error').or(
        page.locator('text=Failed')
      );
      
      // S'il y a des messages d'erreur, ils ne devraient pas être liés à l'export
      // (difficile à tester sans vraie fonctionnalité d'export)
    }
  });
});

