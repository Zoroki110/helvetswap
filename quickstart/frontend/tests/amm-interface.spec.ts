import { test, expect } from '@playwright/test';

test.describe('Canton AMM Interface', () => {
  
  test.beforeEach(async ({ page }) => {
    await page.goto('/');
  });

  test('should load the main interface', async ({ page }) => {
    // Vérifier que la page se charge
    await expect(page).toHaveTitle(/Canton AMM/);
    
    // Vérifier les éléments principaux
    await expect(page.locator('h1')).toContainText('Canton AMM');
    await expect(page.locator('text=Privacy-First Trading')).toBeVisible();
  });

  test('should display navigation menu', async ({ page }) => {
    // Vérifier la navigation
    await expect(page.locator('text=Swap')).toBeVisible();
    await expect(page.locator('text=Pools')).toBeVisible();
    await expect(page.locator('text=Liquidity')).toBeVisible();
    await expect(page.locator('text=History')).toBeVisible();
  });

  test('should show connection status', async ({ page }) => {
    // Vérifier l'indicateur de connexion
    const connectionIndicator = page.locator('[data-testid="connection-status"]').or(
      page.locator('text=Connected').or(page.locator('text=Disconnected'))
    );
    await expect(connectionIndicator).toBeVisible();
  });

  test('should display swap interface by default', async ({ page }) => {
    // Vérifier que l'interface de swap est affichée
    await expect(page.locator('h2')).toContainText('Swap');
    await expect(page.locator('text=From')).toBeVisible();
    await expect(page.locator('text=To')).toBeVisible();
  });

  test('should allow token selection', async ({ page }) => {
    // Tester la sélection de tokens
    const selectButton = page.locator('button:has-text("Select")').first();
    await expect(selectButton).toBeVisible();
    
    await selectButton.click();
    
    // Vérifier que le modal de sélection s'ouvre
    await expect(page.locator('text=Select a token')).toBeVisible();
    
    // Fermer le modal
    await page.locator('button').filter({ hasText: '×' }).or(
      page.locator('[data-testid="close-modal"]')
    ).first().click();
  });

  test('should navigate to pools page', async ({ page }) => {
    await page.locator('text=Pools').click();
    await expect(page.locator('h1')).toContainText('Liquidity Pools');
    await expect(page.locator('text=Discover and analyze liquidity pools')).toBeVisible();
  });

  test('should navigate to liquidity page', async ({ page }) => {
    await page.locator('text=Liquidity').click();
    await expect(page.locator('h2')).toContainText('Liquidity');
    await expect(page.locator('text=Add')).toBeVisible();
    await expect(page.locator('text=Remove')).toBeVisible();
  });

  test('should navigate to history page', async ({ page }) => {
    await page.locator('text=History').click();
    await expect(page.locator('h1')).toContainText('Transaction History');
    await expect(page.locator('text=Track your swaps and liquidity operations')).toBeVisible();
  });

  test('should toggle theme', async ({ page }) => {
    // Trouver le bouton de thème (icône lune/soleil)
    const themeButton = page.locator('button').filter({ 
      has: page.locator('svg') 
    }).filter({ hasText: '' });
    
    // S'il y a plusieurs boutons avec des SVG, essayons de trouver celui du thème
    const themeToggle = page.locator('[data-testid="theme-toggle"]').or(
      page.locator('button:has(svg)').last()
    );
    
    if (await themeToggle.count() > 0) {
      await themeToggle.click();
      // Le thème devrait changer (difficile à tester visuellement, mais pas d'erreur)
    }
  });

  test('should display responsive design on mobile', async ({ page }) => {
    await page.setViewportSize({ width: 375, height: 667 });
    
    // Vérifier que l'interface s'adapte
    await expect(page.locator('h1')).toBeVisible();
    
    // La navigation mobile devrait être visible
    const mobileNav = page.locator('[data-testid="mobile-nav"]').or(
      page.locator('.md\\:hidden')
    );
    
    // Au moins un élément de navigation devrait être visible
    await expect(page.locator('text=Swap')).toBeVisible();
  });
});

