# 🚀 Quick Start - AMM Canton

## ⚡ **IMMEDIATE FIX - Works in 30 seconds**

### **Problem**: "Swap failed: Unable to connect to Canton Network"

### **Solution**: Force mock mode

1. **Open**: `http://localhost:3001`
2. **Press F12** (open browser console)
3. **Paste this code**:

```javascript
localStorage.clear();
localStorage.setItem("canton-amm-store", JSON.stringify({
  isConnected: false,
  currentParty: "Alice",
  slippage: 0.5,
  deadline: 20
}));
window.location.reload();
```

4. **Test swap**: USDC → ETH should work!

## 🎯 **What You'll Get**

- ✅ **Working swaps**: USDC ↔ ETH, BTC, USDT
- ✅ **Real AMM calculations**: Constant product formula
- ✅ **Pool visualization**: Liquidity, volume, APR
- ✅ **Transaction history**: Complete tracking
- ✅ **Responsive design**: Mobile, tablet, desktop

## 🔗 **For Real Canton LocalNet Later**

When you want real on-chain transactions:

1. **Configure Canton** with proper APIs
2. **Deploy AMM contracts**
3. **Switch to connected mode**
4. **Test real transactions**

---

**Your AMM is ready! Apply the localStorage fix and start trading!** 🎉

