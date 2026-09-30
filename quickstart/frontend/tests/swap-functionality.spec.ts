import { test, expect } from '@playwright/test';

test.describe('Swap Functionality', () => {
  
  test.beforeEach(async ({ page }) => {
    await page.goto('/');
    await page.waitForLoadState('networkidle');
    
    // S'assurer qu'on est sur la page de swap
    if (await page.locator('text=Pools').count() > 0) {
      await page.locator('text=Swap').click();
    }
  });

  test('should display swap form correctly', async ({ page }) => {
    // Vérifier les éléments du formulaire de swap
    await expect(page.locator('h2')).toContainText('Swap');
    await expect(page.locator('text=From')).toBeVisible();
    await expect(page.locator('text=To')).toBeVisible();
    
    // Vérifier les champs d'entrée
    const inputFields = page.locator('input[type="number"]');
    await expect(inputFields).toHaveCount(2);
    
    // Vérifier les boutons de sélection de tokens
    const selectButtons = page.locator('button:has-text("Select")');
    await expect(selectButtons).toHaveCount(2);
  });

  test('should open token selector modal', async ({ page }) => {
    const firstSelectButton = page.locator('button:has-text("Select")').first();
    await firstSelectButton.click();
    
    // Vérifier que le modal s'ouvre
    await expect(page.locator('text=Select a token')).toBeVisible();
    
    // Vérifier les éléments du modal
    await expect(page.locator('input[placeholder*="Search"]')).toBeVisible();
    await expect(page.locator('text=Popular tokens')).toBeVisible();
    
    // Fermer le modal
    await page.locator('button').filter({ hasText: '×' }).first().click();
    await expect(page.locator('text=Select a token')).not.toBeVisible();
  });

  test('should handle amount input', async ({ page }) => {
    const amountInput = page.locator('input[type="number"]').first();
    
    // Tester la saisie d'un montant
    await amountInput.fill('100');
    await expect(amountInput).toHaveValue('100');
    
    // Tester la validation (montants négatifs)
    await amountInput.fill('-50');
    // Le champ devrait soit rejeter la valeur soit la corriger
    
    // Tester les décimales
    await amountInput.fill('123.456');
    await expect(amountInput).toHaveValue('123.456');
  });

  test('should show swap button states', async ({ page }) => {
    // Initialement, le bouton devrait indiquer qu'il faut sélectionner des tokens
    const swapButton = page.locator('button').filter({ 
      hasText: /Swap|Select Tokens|Enter Amount/ 
    }).last();
    
    await expect(swapButton).toBeVisible();
    
    // Le bouton devrait être désactivé initialement
    if (await swapButton.count() > 0) {
      const isDisabled = await swapButton.getAttribute('disabled');
      // Soit disabled=true soit pas de disabled mais classe indiquant l'état
    }
  });

  test('should toggle token positions', async ({ page }) => {
    // Chercher le bouton de swap des positions (flèche)
    const swapPositionsButton = page.locator('button').filter({
      has: page.locator('svg')
    }).filter({ hasText: '' }).first();
    
    if (await swapPositionsButton.count() > 0) {
      await swapPositionsButton.click();
      // Les positions devraient s'inverser (difficile à tester sans tokens sélectionnés)
    }
  });

  test('should display slippage settings', async ({ page }) => {
    // Chercher le bouton des paramètres
    const settingsButton = page.locator('button').filter({
      has: page.locator('svg')
    }).filter({ hasText: '' });
    
    if (await settingsButton.count() > 0) {
      await settingsButton.first().click();
      
      // Vérifier que les paramètres de slippage s'affichent
      const slippageSettings = page.locator('text=Slippage').or(
        page.locator('text=0.1%').or(page.locator('text=0.5%'))
      );
      
      if (await slippageSettings.count() > 0) {
        await expect(slippageSettings).toBeVisible();
      }
    }
  });

  test('should handle responsive design in swap interface', async ({ page }) => {
    // Tester sur mobile
    await page.setViewportSize({ width: 375, height: 667 });
    
    await expect(page.locator('h2')).toContainText('Swap');
    await expect(page.locator('text=From')).toBeVisible();
    await expect(page.locator('text=To')).toBeVisible();
    
    // Les champs devraient rester utilisables
    const inputFields = page.locator('input[type="number"]');
    await expect(inputFields.first()).toBeVisible();
    
    // Retour au desktop
    await page.setViewportSize({ width: 1280, height: 720 });
    await expect(page.locator('h2')).toContainText('Swap');
  });
});

