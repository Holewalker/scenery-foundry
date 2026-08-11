# ADR-0003 — Ownership y orden de implementación de seguridad

**Estado:** Aceptada

**Fecha:** 2026-08-11

## Decisión

Autenticación, sesión, CSRF y enforcement de ownership se implementan **antes** de persistir
assets, proyectos, escenas o exports privados. Un UUID no concede acceso y el frontend no es
una frontera de seguridad.

## Contrato firme

- La autenticación servlet usa `HttpSessionSecurityContextRepository`: carga y guarda el
  `SecurityContext` en `HttpSession`. Si un endpoint realiza autenticación manual, debe llamar
  explícitamente a `SecurityContextRepository.saveContext(...)`; no basta con asignar el
  contexto al `SecurityContextHolder`.
- El usuario efectivo de las reglas de aplicación procede exclusivamente del
  `SecurityContext`; se ignora cualquier
  `userId` de autorización enviado por el cliente.
- Cada aggregate root privado tiene `owner_id NOT NULL` y clave foránea a `users`.
- Los dependientes derivan ownership por join con su aggregate root; no mantienen copias
  independientes que puedan divergir.
- Lecturas, mutaciones, descargas y creación de jobs incluyen ownership en la query, no como
  comprobación tardía después de cargar la entidad.
- Un recurso ajeno o inexistente responde `404` en endpoints privados. `403` se reserva para
  una acción conocida no permitida dentro de un recurso ya autorizado.
- Descargas pasan por Spring tras la autorización; `/data` nunca es público.
- Spring Security conserva CSRF habilitado con `HttpSessionCsrfTokenRepository`. La SPA
  solicita `GET /api/csrf` con credenciales de mismo origen y recibe
  `{ "token": "...", "headerName": "X-CSRF-TOKEN" }`; envía ese header y la cookie de
  sesión en `POST`, `PUT`, `PATCH` y `DELETE`, incluidos login y logout. Tras autenticar,
  cerrar sesión, recibir `401`/CSRF inválido o invalidarse la sesión, descarta el token y
  obtiene uno nuevo antes de reintentar una mutación. El endpoint fuerza la materialización
  del token diferido y nunca se cachea. `csrf.spa()` con `CookieCsrfTokenRepository` es una
  alternativa distinta y no se mezcla con este contrato basado en sesión.
- La cookie de sesión usa `HttpOnly`, `Secure` en producción y `SameSite=Lax`. Estos atributos
  y el ciclo de vida de la cookie se configuran en Spring Boot/contenedor servlet: Spring
  Security no crea la cookie de sesión ni controla directamente `SameSite`.
- Los jobs contienen solo un snapshot ya autorizado. El worker no recibe credenciales de
  usuario y no toma decisiones de ownership.

## Orden de entrega

1. tablas `users` y sesiones/configuración Spring Security;
2. registro, login, logout y CSRF;
3. helpers de queries scoped y pruebas con dos usuarios;
4. primer aggregate privado y su storage autorizado;
5. resto de assets, proyectos, escenas, exports y jobs.

Ninguna fase puede introducir un recurso privado persistente sin pruebas positivas del
propietario y negativas de un segundo usuario para lectura, mutación, descarga y acción
indirecta relevante.

El filtrado por `owner_id`, la respuesta `404` para recursos ajenos y este orden de entrega
son políticas de Scenery Foundry. Spring Security aporta el contexto autenticado y las
protecciones configuradas, pero no garantiza por sí mismo esas reglas de dominio.

## Consecuencias

- El editor puede evolucionar primero con fixtures/local state, pero su persistencia privada
  espera a la baseline de seguridad.
- El coste inicial aumenta; a cambio se evita adaptar ownership a tablas, paths y jobs ya
  desplegados.

## Valores configurables y cuestiones aplazadas

- **Configurable:** duración de sesión, política de contraseña, rate limits y atributos
  finales de cookie según el dominio de despliegue.
- **Aplazado:** OAuth/OIDC, MFA, organizaciones, colaboración y roles distintos del owner.

## Fuentes

- [Spring Security 7.1: protección CSRF](https://docs.spring.io/spring-security/reference/7.1/servlet/exploits/csrf.html)
- [Spring Security 7.1: sesiones](https://docs.spring.io/spring-security/reference/7.1/servlet/authentication/session-management.html)
- [Spring Security 7.1: `HttpSessionSecurityContextRepository`](https://docs.spring.io/spring-security/reference/7.1/api/java/org/springframework/security/web/context/HttpSessionSecurityContextRepository.html)
- [Spring Security 7.1: alcance de `SameSite`](https://docs.spring.io/spring-security/reference/7.1/features/exploits/csrf.html)
