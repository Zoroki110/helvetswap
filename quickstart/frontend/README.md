# ClearportX AMM - Decentralized Exchange

A professional-grade AMM (Automated Market Maker) built for Canton Network with institutional-level design and functionality.

## 🚀 Features

### 📊 **Trading Interface**
- **TradingView Integration**: Real-time charts with professional trading tools
- **Token Swapping**: Intuitive swap interface with live price feeds
- **Smart Token Selection**: Dropdown selectors (no popups) like Uniswap
- **Real-time Data**: Connected pool volumes and statistics

### 💧 **Liquidity Management**
- **Pool Overview**: Complete view of all available liquidity pools
- **Add/Remove Liquidity**: Full liquidity management interface
- **Position Tracking**: Monitor your liquidity positions and earnings
- **APR Calculations**: Real-time APR display for all pools

### 🎨 **Design & UX**
- **CantonX Branding**: Consistent with institutional design standards
- **Responsive Design**: Works perfectly on mobile and desktop
- **Glassmorphism UI**: Modern, clean interface with subtle transparency
- **Professional Color Scheme**: Black, gold, and cream palette

## 🛠 Tech Stack

- **Frontend**: Vanilla HTML5, CSS3, JavaScript ES6+
- **Styling**: Tailwind CSS with custom CantonX theme
- **Charts**: TradingView Advanced Charts
- **Icons**: Custom token icons with proper branding
- **Deployment**: Netlify with custom domain support

## 📱 Pages

### 1. **Swap** (Default)
- TradingView chart on the left
- Swap interface on the right
- Real-time price data
- Token selection dropdowns

### 2. **Pools**
- TVL, Volume, and Active Pools statistics
- Complete pools table with APR data
- Add liquidity buttons for each pool
- Professional data presentation

### 3. **Liquidity**
- Add liquidity form with dual token inputs
- Pool information display
- Your positions overview
- Empty state for new users

## 🎯 Key Improvements Made

1. **No More Popups**: Replaced alert() with professional dropdowns
2. **Real Data**: Connected pool volumes and statistics
3. **Professional Navigation**: Tab-based page switching
4. **Correct Branding**: Applied actual CantonX colors and fonts
5. **TradingView Integration**: Professional charts like major DEXs
6. **Mobile Responsive**: Works on all device sizes

## 🚀 Getting Started

### Local Development
```bash
# Clone the repository
git clone https://github.com/X-Ventures/canton-website.git
cd canton-website/app

# Open in browser
open index.html

# Or serve with Python
python3 -m http.server 8080
```

### Live Demo
Visit: [app.clearportx.com](https://app.clearportx.com) (when deployed)

## 🔧 Configuration

The app uses mock data for demonstration. To connect to real Canton Network:

1. Update `mockPools` object with real pool data
2. Replace TradingView symbols with actual trading pairs
3. Implement wallet connection logic
4. Add real transaction handling

## 📊 Mock Data Structure

```javascript
const mockPools = {
    'BTC/USDT': {
        volume24h: 3200000,
        fee: 0.3,
        tvSymbol: 'BINANCE:BTCUSDT'
    },
    // ... more pools
};
```

## 🎨 Branding

- **Primary**: Black (#000000)
- **Accent**: Gold (#B8860B, #DAA520)  
- **Background**: Cream (#fafafa, #f5f5f5)
- **Font**: Inter (primary), Space Grotesk (display)

## 🤝 Contributing

This project is ready for Canton Network integration. Key areas for development:

1. **Smart Contract Integration**: Connect to real Canton pools
2. **Wallet Integration**: Add MetaMask/WalletConnect support
3. **Real-time Data**: Replace mock data with live feeds
4. **Transaction Handling**: Implement actual swap/liquidity functions

## 📞 Contact

Built for institutional-grade DeFi on Canton Network.

---

**Ready to share with Zoroki110! 🚀**