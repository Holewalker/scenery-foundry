# Seeding local del editor (fixtures STL)

Para ejercitar el editor 3D de extremo a extremo en una máquina de desarrollo hace falta un
usuario autenticado, un proyecto que le pertenezca, y una fila en `prepared_assets` que apunte
a un archivo `.stl` real. `data/seed/` y `scripts/seed-local-editor.ps1` proveen ese cableado
solo para desarrollo local. **Son fixtures locales desechables, no una funcionalidad de subida,
no un manifiesto, y no almacenamiento de objetos.** Nada bajo `data/` se sirve, respalda ni
distribuye; el directorio está completamente ignorado por Git (`/data/` y `data/` en
`.gitignore`) y cualquier ruta debajo puede borrarse y recrearse en cualquier momento sin
consecuencias.

## Camino rápido

1. Levantar el stack (`docker compose up -d --wait`). Postgres debe estar arriba y saludable
   antes de cualquier `docker compose exec`, incluyendo la creación del usuario y el script de
   seed de los pasos siguientes.

2. Crear directamente en PostgreSQL un usuario propietario y su proyecto (todavía no existe un
   endpoint de registro propio):

   ```powershell
   docker compose exec -T postgres psql -U scenery -d scenery_foundry -c @"
   insert into users(id, email, password_hash)
     values (gen_random_uuid(), 'dev@example.com', crypt('dev-password', gen_salt('bf', 12)))
     returning id;
   "@
   ```

   Usar el `id` devuelto como `<user-id>` a continuación, luego insertar un proyecto propio con
   `insert into projects(id, owner_id) values (gen_random_uuid(), '<user-id>') returning id;`.

3. Dejar caer un archivo `.stl` real bajo `data/seed/`, por ejemplo `data/seed/fixture.stl`.
   Nunca commitear este archivo — `data/` está ignorado precisamente para que nadie tenga que
   recordarlo.

4. Ejecutar el script de seed para registrar ese archivo como una fila de `prepared_assets`
   asociada al proyecto:

   ```powershell
   ./scripts/seed-local-editor.ps1 `
     -RelativePath "seed/fixture.stl" `
     -UserId "<user-id>" `
     -ProjectId "<project-id>" `
     -AssetId (New-Guid)
   ```

5. Iniciar sesión con las credenciales sembradas y abrir el editor en
   `http://localhost:8081/?project=<project-id>`. El asset sembrado aparece en el catálogo y
   puede insertarse en la escena.

## Detalles

| Tema | Decisión |
|-------|----------|
| Qué es `data/seed/*.stl` | Un archivo fuente local que el script de seed lee para calcular un checksum SHA-256 y registrar una `storage_key`. Cumple el mismo papel que cumpliría un pipeline de subida real una vez exista. |
| Qué NO es | No es un endpoint de subida, no es un manifiesto persistido, no es ninguna forma de almacenamiento de objetos. No existe ningún camino de código que escriba en `data/` desde tráfico de la aplicación. |
| Seguridad de rutas | `Resolve-SeedAssetPath` en `scripts/seed-local-editor.ps1` rechaza rutas absolutas, extensiones distintas de `.stl`, archivos inexistentes, symlinks, y cualquier ruta que escape de `data/` mediante traversal. |
| Propiedad | El script verifica que el usuario dado existe y es propietario del proyecto dado antes de escribir la fila en `prepared_assets`; nunca crea usuarios ni proyectos por sí mismo. |
| Higiene de Git | `data/` y `/data/` están en `.gitignore`. No se espera ningún binario `.stl` ni dato de seed en un commit o diff de PR. |
| Acceso desde contenedores | `compose.yml` monta `./data:/data` en `backend` y `geometry-worker`, así que un archivo dejado bajo `data/seed/` en el host es visible para ambos servicios sin necesidad de reconstruir la imagen. |

## Checklist

- [ ] El fixture `.stl` vive bajo `data/seed/` y no está en el índice de Git.
- [ ] El usuario de seed existe y es propietario del proyecto referenciado por `-ProjectId`.
- [ ] `scripts/seed-local-editor.ps1` imprimió `Seeded asset <id> -> seed/<file> (<sha256>)`.
- [ ] El asset es visible en el catálogo del editor tras iniciar sesión con el usuario sembrado.

## Siguiente paso

Ver el [`README.md`](../README.md) de nivel superior para el arranque completo del stack, y
`scripts/seed-local-editor-test.ps1` para las pruebas unitarias que cubren la seguridad de
rutas y las verificaciones de propiedad del script.
