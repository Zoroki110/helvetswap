// Simplified Canton Auth for build
export const cantonAuth = {
  getAuthToken: () => 'mock-token',
  isAuthenticated: () => true,
  login: () => Promise.resolve('success'),
  logout: () => Promise.resolve(),
};

export default cantonAuth;