# ClearPortX App - Quick Start 🚀

## Configuration Terminée ✅

Le branding noir/or de clearportx.com a été appliqué avec succès!

---

## 🏃 Démarrage Rapide

### 1. Installation
```bash
cd app
npm install
```

### 2. Lancer en développement
```bash
npm start
```

L'app s'ouvrira sur `http://localhost:3000`

### 3. Build pour production
```bash
npm run build
```

Le build sera dans `app/build/`

---

## 🎨 Aperçu des Changements

### Couleurs Appliquées
- **Noir**: `#000000` (Primary)
- **Or**: `#B8860B` (Accent)
- **Or Clair**: `#DAA520` (Accent Light)

### Éléments Modifiés
✅ Logo ClearPortX (noir/or)
✅ Navigation (état actif en or)
✅ Boutons primaires (noir)
✅ Text gradients (or)
✅ Backgrounds mesh (or subtil)
✅ Focus states (anneau or)
✅ Loading spinners (or)

---

## 📦 Déploiement Netlify

### Configuration Netlify
```
Base directory:      /
Package directory:   Not set
Build command:       cd app && npm ci && npm run build
Publish directory:   app/build
```

### Étapes
1. Connecter GitHub à Netlify
2. Sélectionner `canton-website` repo
3. Entrer la config ci-dessus
4. Déployer!

**Important**: Chaque push sur `main` déploie automatiquement.

---

## 🔧 Scripts Disponibles

```bash
# Développement
npm start

# Build production
npm run build

# Tests
npm test

# Tests E2E (Playwright)
npm run test:e2e

# Linter
npm run lint

# Format code
npm run format
```

---

## 📁 Structure du Projet

```
app/
├── public/
│   ├── clearportx-logo.svg    ✅ Logo officiel
│   ├── index.html
│   └── manifest.json
├── src/
│   ├── components/
│   │   ├── Header.tsx          ✅ Modifié
│   │   ├── SwapInterface.tsx
│   │   ├── PoolsInterface.tsx
│   │   └── ...
│   ├── services/
│   ├── stores/
│   ├── types/
│   ├── App.tsx                 ✅ Modifié
│   └── index.css               ✅ Modifié
├── tailwind.config.js          ✅ Modifié
└── package.json
```

---

## 🎨 Utilisation des Couleurs

### Dans vos composants

```tsx
// Texte avec gradient or
<h1 className="text-gradient-clearportx">ClearPortX</h1>

// Bouton noir
<button className="btn-primary">Action</button>

// Texte or
<span className="text-accent-600">Important</span>

// Background or subtil
<div className="bg-accent-50">Content</div>

// Bordure or
<div className="border-accent-600">Box</div>
```

### Classes Tailwind Disponibles

**Primary (Noir/Gris)**
- `text-primary-950` - Noir
- `bg-primary-950` - Fond noir
- `border-primary-950` - Bordure noire

**Accent (Or)**
- `text-accent-600` - Or foncé
- `text-accent-500` - Or clair
- `bg-accent-600` - Fond or foncé
- `bg-accent-50` - Fond or très clair
- `border-accent-600` - Bordure or

**Gradients**
- `text-gradient-clearportx` - Gradient or pour texte
- `bg-clearportx-gradient` - Gradient or pour fond

---

## 🐛 Troubleshooting

### Le site ne démarre pas
```bash
# Supprimer node_modules et réinstaller
rm -rf node_modules package-lock.json
npm install
npm start
```

### Les couleurs ne s'appliquent pas
```bash
# Rebuild Tailwind CSS
npm run build
```

### Erreur au build
```bash
# Vérifier les dépendances
npm audit fix
npm run build
```

---

## 📝 Notes Importantes

### Canton Network Connection
L'app se connecte à Canton LocalNet sur `http://localhost:8080`

Pour tester avec un vrai réseau:
1. Modifier `CANTON_CONFIG` dans `src/App.tsx`
2. Pointer vers votre nœud Canton

### Environnements
- **LocalNet**: Développement local (par défaut)
- **DevNet**: Réseau de développement Canton
- **TestNet**: Réseau de test Canton
- **MainNet**: Production Canton

---

## 🔗 Ressources

### Documentation
- [ClearPortX Site](https://clearportx.com/)
- [Canton Network](https://www.canton.network/)
- [Canton Docs](https://docs.digitalasset-staging.com/)
- [React Docs](https://react.dev/)
- [Tailwind CSS](https://tailwindcss.com/)

### Fichiers de Référence
- `BRANDING_GUIDE.md` - Guide complet des couleurs
- `BRANDING_CHANGES.md` - Détails des changements
- `README.md` - Documentation générale

---

## ✨ Prochaines Étapes

1. **Tester localement**
   ```bash
   npm start
   ```

2. **Vérifier le design**
   - Header avec logo or
   - Navigation active en or
   - Boutons noirs
   - Gradients or

3. **Push sur GitHub**
   ```bash
   git add .
   git commit -m "Apply ClearPortX black/gold branding"
   git push origin main
   ```

4. **Vérifier le déploiement Netlify**
   - Auto-déploie depuis GitHub
   - Check les logs de build
   - Tester le site en production

---

## 🎯 Support

Des questions? Besoin d'ajustements?

**Modifications possibles:**
- Ajuster les nuances de couleurs
- Modifier les effets hover
- Ajouter des animations
- Personnaliser les composants
- Optimiser les performances

Faites-moi savoir! 🚀

