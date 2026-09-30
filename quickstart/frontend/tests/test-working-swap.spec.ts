import { test, expect } from '@playwright/test';

test.describe('🎯 Test Working Swap', () => {
  
  test('💱 Complete USDC → ETH swap test', async ({ page }) => {
    console.log('🚀 TESTING WORKING SWAP: USDC → ETH');
    console.log('===================================');
    
    // Go to AMM interface
    await page.goto('http://localhost:3001');
    await page.waitForLoadState('networkidle');
    await page.waitForTimeout(2000);
    
    console.log('✅ AMM interface loaded');
    
    // Go to swap page
    await page.goto('http://localhost:3001/#/swap');
    await page.waitForTimeout(3000);
    
    console.log('✅ Swap page loaded');
    
    // Debug: Check what's on the page
    const pageContent = await page.evaluate(() => {
      return {
        hasSwapTitle: document.body.innerText.includes('Swap'),
        hasFromField: document.body.innerText.includes('From'),
        hasToField: document.body.innerText.includes('To'),
        selectButtonCount: document.querySelectorAll('button:contains("Select"), button[text*="Select"]').length,
        allButtonsCount: document.querySelectorAll('button').length,
        inputCount: document.querySelectorAll('input[type="number"]').length,
        bodySnippet: document.body.innerText.substring(0, 500)
      };
    });
    
    console.log('🔍 Page analysis:');
    console.log(`  - Has Swap title: ${pageContent.hasSwapTitle ? '✅' : '❌'}`);
    console.log(`  - Has From field: ${pageContent.hasFromField ? '✅' : '❌'}`);
    console.log(`  - Has To field: ${pageContent.hasToField ? '✅' : '❌'}`);
    console.log(`  - Total buttons: ${pageContent.allButtonsCount}`);
    console.log(`  - Input fields: ${pageContent.inputCount}`);
    
    // Try to find and click select buttons more reliably
    console.log('🔄 Attempting token selection...');
    
    // Method 1: Look for buttons with "Select" text
    const allButtons = await page.locator('button').all();
    let selectButtons = [];
    
    for (let i = 0; i < allButtons.length; i++) {
      const buttonText = await allButtons[i].textContent();
      if (buttonText && buttonText.includes('Select')) {
        selectButtons.push(allButtons[i]);
        console.log(`Found select button ${selectButtons.length}: "${buttonText}"`);
      }
    }
    
    if (selectButtons.length >= 2) {
      try {
        // Select USDC (first token)
        console.log('🪙 Selecting USDC...');
        await selectButtons[0].click();
        await page.waitForTimeout(2000);
        
        // Look for USDC in modal
        const modalContent = await page.evaluate(() => {
          return {
            hasModal: document.body.innerText.includes('Select a token'),
            hasUSDC: document.body.innerText.includes('USDC'),
            modalSnippet: document.body.innerText.includes('Select a token') ? 
              document.body.innerText.substring(document.body.innerText.indexOf('Select a token'), document.body.innerText.indexOf('Select a token') + 200) : 'No modal'
          };
        });
        
        console.log(`  Modal open: ${modalContent.hasModal ? '✅' : '❌'}`);
        console.log(`  USDC available: ${modalContent.hasUSDC ? '✅' : '❌'}`);
        
        if (modalContent.hasUSDC) {
          // Click on USDC
          const usdcButton = page.locator('button').filter({ hasText: 'USDC' }).first();
          await usdcButton.click();
          await page.waitForTimeout(1500);
          console.log('✅ USDC selected');
          
          // Select ETH (second token)
          console.log('🪙 Selecting ETH...');
          
          // Find select buttons again after first selection
          const buttonsAfterUSDC = await page.locator('button').all();
          let secondSelectButton = null;
          
          for (let i = 0; i < buttonsAfterUSDC.length; i++) {
            const buttonText = await buttonsAfterUSDC[i].textContent();
            if (buttonText && buttonText.includes('Select')) {
              secondSelectButton = buttonsAfterUSDC[i];
              break;
            }
          }
          
          if (secondSelectButton) {
            await secondSelectButton.click();
            await page.waitForTimeout(2000);
            
            const ethButton = page.locator('button').filter({ hasText: 'ETH' }).first();
            if (await ethButton.count() > 0) {
              await ethButton.click();
              await page.waitForTimeout(1500);
              console.log('✅ ETH selected');
              
              // Enter amount
              console.log('💰 Entering amount...');
              const numberInputs = await page.locator('input[type="number"]').all();
              
              if (numberInputs.length >= 1) {
                await numberInputs[0].fill('10');
                await page.waitForTimeout(3000);
                console.log('✅ Amount entered: 10 USDC');
                
                // Check output
                if (numberInputs.length >= 2) {
                  const outputValue = await numberInputs[1].inputValue();
                  console.log(`📊 Output value: ${outputValue}`);
                  
                  if (parseFloat(outputValue) > 0) {
                    console.log('🎉 SUCCESS: Swap quote calculated!');
                    console.log(`✅ 10 USDC → ${outputValue} ETH`);
                  } else {
                    console.log('❌ No quote calculated');
                    
                    // Debug why no quote
                    const debugInfo = await page.evaluate(() => {
                      return {
                        consoleMessages: console.log.toString(),
                        hasErrors: document.body.innerText.includes('Error'),
                        hasNoPool: document.body.innerText.includes('No liquidity pool')
                      };
                    });
                    
                    console.log('🔍 Debug info:');
                    console.log(`  - Has errors: ${debugInfo.hasErrors}`);
                    console.log(`  - No pool message: ${debugInfo.hasNoPool}`);
                  }
                }
                
                // Check swap button state
                const swapButtonState = await page.evaluate(() => {
                  const buttons = Array.from(document.querySelectorAll('button'));
                  const swapButton = buttons.find(btn => 
                    btn.textContent && 
                    (btn.textContent.includes('Swap') || btn.textContent.includes('Exchange'))
                  );
                  
                  return {
                    found: !!swapButton,
                    text: swapButton?.textContent || 'Not found',
                    enabled: swapButton ? !swapButton.disabled : false,
                    className: swapButton?.className || 'N/A'
                  };
                });
                
                console.log('🔘 Swap button state:');
                console.log(`  - Found: ${swapButtonState.found ? '✅' : '❌'}`);
                console.log(`  - Text: "${swapButtonState.text}"`);
                console.log(`  - Enabled: ${swapButtonState.enabled ? '✅' : '❌'}`);
              }
            }
          }
        }
      } catch (error) {
        console.log(`❌ Error during token selection: ${error.message}`);
      }
    }
    
    console.log('\n🎊 SWAP TEST COMPLETE');
    console.log('=====================');
    
    // Final recommendations
    console.log('💡 Next steps to fix:');
    console.log('1. Check browser console for React errors');
    console.log('2. Verify pools are loaded in React state');
    console.log('3. Check useSwapQuote hook logic');
    console.log('4. Ensure pool matching works correctly');
  });
});

