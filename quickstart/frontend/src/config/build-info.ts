// Build information to force Netlify redeploy
export const BUILD_INFO = {
  version: '1.0.4',
  buildTime: new Date().toISOString(),
  features: {
    useMockData: false,
    useRealCantonData: true,
    backendUrl: process.env.REACT_APP_BACKEND_API_URL || (process.env.NODE_ENV === 'production' ? 'https://api.helvetswap.app' : 'http://localhost:8080'),
    cantonApiRemoved: true,
    backendApiOnly: true,
    apiErrorHandlingFixed: true,
    typeScriptErrorFixed: true,
    ammPackageId: process.env.REACT_APP_AMM_POOL_PACKAGE_ID || ''
  }
};
