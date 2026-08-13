# Frontend

SPA del editor 3D.

Responsabilidades previstas:

- React + TypeScript + Vite;
- React Three Fiber / Three.js / Drei;
- catálogo privado de assets;
- scene graph en memoria;
- selección, transformaciones, grid snap y undo/redo;
- persistencia mediante la API de Spring;
- consulta de estado de jobs/exports.

El frontend no es una frontera de seguridad y no accede directamente a `/data`.

## Quick path

```powershell
npm ci
npm test
npm run build
```

El scaffold fija Node 24.19/npm 11.17 como engine y versiona `package-lock.json`. Vitest
comprueba el shell y consume el fixture compartido de matrices mediante Three.js.

Canvas, catálogo y estado del editor quedan para el siguiente spike vertical.
