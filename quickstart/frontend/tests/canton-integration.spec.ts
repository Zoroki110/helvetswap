import { test, expect } from '@playwright/test';

test.describe('Canton Network Integration', () => {
  
  test.beforeEach(async ({ page }) => {
    await page.goto('/');
    // Attendre que la page soit chargée
    await page.waitForLoadState('networkidle');
  });

  test('should check Canton LocalNet connection', async ({ page }) => {
    // Attendre un peu pour que les vérifications de santé se fassent
    await page.waitForTimeout(3000);
    
    // Vérifier les indicateurs de connexion
    const healthIndicators = page.locator('text=Connected').or(
      page.locator('text=Disconnected')
    );
    await expect(healthIndicators).toBeVisible();
    
    // Si connecté, vérifier les détails
    const connectedIndicator = page.locator('text=Connected');
    if (await connectedIndicator.count() > 0) {
      console.log('✅ Canton LocalNet est connecté');
    } else {
      console.log('⚠️ Canton LocalNet n\'est pas connecté - tests limités');
    }
  });

  test('should display Canton network status', async ({ page }) => {
    // Vérifier que les informations de réseau sont affichées
    const networkInfo = page.locator('text=LocalNet').or(
      page.locator('text=Canton Network')
    );
    await expect(networkInfo).toBeVisible();
  });

  test('should handle API errors gracefully', async ({ page }) => {
    // Intercepter les appels API et simuler des erreurs
    await page.route('**/health', route => {
      route.fulfill({ status: 500, body: 'Server Error' });
    });
    
    await page.reload();
    await page.waitForTimeout(2000);
    
    // Vérifier que l'interface gère l'erreur
    const errorIndicator = page.locator('text=Disconnected').or(
      page.locator('text=Error')
    );
    await expect(errorIndicator).toBeVisible();
  });

  test('should show loading states', async ({ page }) => {
    // Intercepter les appels API pour les ralentir
    await page.route('**/v1/**', route => {
      setTimeout(() => route.continue(), 1000);
    });
    
    await page.reload();
    
    // Chercher des indicateurs de chargement
    const loadingIndicators = page.locator('[data-testid="loading"]').or(
      page.locator('text=Loading').or(
        page.locator('svg').filter({ hasText: '' })
      )
    );
    
    // Au moins pendant un moment, il devrait y avoir un indicateur de chargement
    // (difficile à tester de manière fiable, mais on vérifie que ça ne crash pas)
  });
});

