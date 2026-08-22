# ADR-0008 — Topología de despliegue y operación

**Estado:** Aceptada

**Fecha:** 2026-08-22

## Decisión

Un único VPS con Docker Compose. **Caddy** termina TLS en 80/443 y es el único servicio con
puertos publicados; backend y frontend quedan internos. El nombre lo aporta un sidecar
**DuckDNS** que mantiene el registro A de `<subdominio>.duckdns.org`, y Caddy obtiene
certificados por ACME/Let's Encrypt contra ese dominio real. Los secretos viven en un `.env`
con permisos `0600`. Un sidecar de backup nocturno vuelca PostgreSQL y los STL originales al
propio host. La observabilidad es **logs JSON estructurados + healthchecks reales + Actuator**,
sin ninguna pieza de infraestructura nueva.

## Topología

```text
Internet :80/:443
      |
   caddy  ── /api/*  ──> backend:8080   (sin puerto publicado)
      |   └─ resto    ──> frontend:80   (sin puerto publicado)
      |
  duckdns (sin puertos)  → mantiene el registro A
  postgres ← backend, geometry-worker, backup   (red interna)
  ./data   ← backend, geometry-worker, backup
  ./backups ← backup
```

`/actuator/*` no se enruta desde Caddy en ninguna regla: solo `/api/*` llega al backend, así que
los endpoints de gestión no son alcanzables desde Internet ni siquiera por error de
configuración de Spring Security.

## TLS y dominio

DuckDNS + ACME normal, no CA interna. Motivo: es gratuito, da un nombre público real y por
tanto Let's Encrypt puede completar el desafío HTTP-01; una CA interna obligaría a instalar el
certificado raíz en cada navegador y rompería el flujo de sesión con cookie `Secure`. Se reutiliza
literalmente la forma del proyecto hermano `000Libre` (servicio `duckdns` con
`lscr.io/linuxserver/duckdns`, directiva `{$DOMAIN}` en el Caddyfile) en lugar de inventar otra.

**Prerrequisitos operativos**, sin los cuales el despliegue falla y deben documentarse en el
runbook: 80 y 443 abiertos en el firewall del host y del proveedor, el registro DuckDNS
apuntando a la IP pública, y `DOMAIN` coincidiendo exactamente con el subdominio.

`SESSION_COOKIE_SECURE` pasa a `true` por defecto. Hoy está fijado a `"false"` en
`compose.yml`, lo que emitiría cookies de sesión inseguras sobre el nuevo TLS.

## Secretos

`.env` con `chmod 600` más un `.env.example` versionado. `POSTGRES_PASSWORD` pierde su default
débil `scenery-local` y pasa a ser obligatorio con la misma forma `${VAR:?mensaje}` que ya usa
`WORKER_DB_PASSWORD`.

**Docker secrets rechazado**: aporta indirección por montaje de ficheros y ceremonia orientada a
Swarm sin ninguna ganancia en un despliegue Compose de un solo host; el fichero seguiría estando
en el mismo disco y con los mismos permisos.

## Backups

- **Qué**: `pg_dump -Fc` de la base completa y un tar de los **STL originales** de `/data`.
- **Qué no**: previews y exports derivados. Son reproducibles desde originales inmutables
  (ADR-0002/ADR-0004) e incluirlos multiplicaría el tamaño sin añadir capacidad de recuperación.
- **Cuándo**: diario, en una ventana nocturna configurable, desde un sidecar que reutiliza la
  imagen `postgres:18.4` ya presente. Reutilizarla garantiza que la versión de `pg_dump` coincide
  con la del servidor y no añade ninguna imagen que mantener.
- **Retención**: 7 diarios + 4 semanales, podados por el mismo job.
- **Integridad**: cada backup lleva un manifiesto con `sha256`; se publica de forma atómica
  (staging → `rename`) para que un backup interrumpido nunca aparezca como válido.
- **Restauración**: runbook escrito más un smoke test que demuestra que un volcado recarga de
  verdad. Un backup no verificado no cuenta como backup.

**Cron del host rechazado**: quedaría fuera de `docker compose`, invisible en `docker compose ps`
y exigiría privilegios de host, contra el invariante 11 (la aplicación debe seguir arrancando
mediante Docker Compose). El sidecar duerme hasta la siguiente ventana; no se añade `cron` ni
`supercronic` a ninguna imagen.

## Observabilidad

- **Logs**: JSON estructurado en ambos lados, con `jobId` como clave de correlación entre Spring
  y el worker. En Spring se usa el soporte nativo de logging estructurado de Spring Boot
  (configuración, cero dependencias nuevas); en el worker, el `logging` de la stdlib con un
  formateador JSON propio (cero dependencias nuevas). El worker deja de escribir
  `traceback.print_exc()` a stdout.
- **Nunca se registran**: contraseñas, hashes, email, identificador de sesión, token CSRF,
  token DuckDNS, rutas absolutas fuera de `/data` ni contenido de STL.
- **Healthchecks reales**: el del worker deja de comprobar que un módulo importa. Comprueba que
  el bucle de sondeo sigue vivo y que la base es alcanzable.
- **Rotación**: driver `json-file` con `max-size`/`max-file` en todos los servicios. Hoy los logs
  son ilimitados y acabarían llenando el disco del VPS.
- **Actuator**: se exponen `health`, `info` y `metrics`. Doble control de acceso: Spring Security
  ya exige autenticación para todo salvo `/actuator/health`, y Caddy no enruta `/actuator/*`. No
  se activa `management.info.env`, para que ninguna variable de entorno se publique.

**Prometheus, Grafana, Loki, `micrometer-registry-prometheus`, tracing distribuido, Sentry/APM y
telemetría de frontend rechazados**: el invariante 12 de ARCHITECTURE.md exige una métrica que
justifique infraestructura nueva. Una TSDB y un stack de dashboards en un único VPS sin ningún
consumidor de alertas es exactamente la infraestructura que ese invariante prohíbe, y consumiría
la memoria que necesita el worker. Se reconsidera cuando exista una necesidad operativa medida.

**Object storage externo (S3 y equivalentes) rechazado** como destino de backup: el PRD lo excluye
del runtime, y adoptarlo solo para backup introduciría credenciales, coste y una dependencia de red
en el camino de recuperación.

## Valores iniciales configurables

| Recurso | Default inicial | Motivo |
| --- | --- | --- |
| `geometry-worker` | 3 GiB / 2 CPU | Mayor riesgo de memoria: el trabajo de malla es ilimitado respecto al input (ADR-0006 admite 5 M de triángulos de salida). |
| `backend` | 1 GiB / 1 CPU | JVM más buffers de multipart de 205 MiB. |
| `postgres` | 1 GiB / 1 CPU | Dataset pequeño; el volumen está en `/data`, no en la base. |
| `caddy` | 128 MiB / 0.25 CPU | Proxy sin estado. |
| `backup` | 256 MiB / 0.5 CPU | `pg_dump` + `tar` en streaming. |
| `frontend` | 64 MiB / 0.25 CPU | Nginx sirviendo estáticos. |
| `duckdns` | 32 MiB / 0.1 CPU | Un `curl` periódico. |
| rotación de logs | `max-size: 10m`, `max-file: 5` | ~50 MiB por servicio, ~350 MiB en total. |
| ventana de backup | 03:00 UTC | Fuera de horas de uso previsibles. |

Total ≈ 5,8 GiB: cabe en un VPS de 8 GiB. Subir el límite del worker exige medir memoria real
sobre un corpus, igual que exige ADR-0006 para sus umbrales.

## Consecuencias

- Un `docker compose up -d` en el VPS sirve HTTPS sobre un dominio real, con cookies seguras y sin
  contraseña por defecto. El despliegue local mantiene los puertos en loopback mediante un fichero
  de override, porque Compose **fusiona** listas de `ports` entre ficheros y no permite quitar un
  puerto publicado desde un overlay: el fichero base debe ser el desplegable.
- Los límites por servicio convierten una fuga de memoria del worker en el reinicio de un
  contenedor en lugar de un OOM de todo el host.
- **Los backups son locales al host y no sobreviven a su pérdida.** Es una carencia conocida y
  aceptada para esta fase, no un descuido: se documenta explícitamente en el runbook con la tabla
  de escenarios que cubre y los que no.
- DuckDNS es un servicio gratuito de terceros. Si desaparece, el dominio se pierde y hay que
  reapuntar `DOMAIN`; Caddy y el resto de la topología no cambian.

## Cuestiones aplazadas

Copia de backup fuera del host (rsync cifrado a un segundo destino es el mínimo razonable),
PITR/WAL archiving, snapshots de volumen del proveedor, CI/CD, despliegues sin corte, multi-host,
Swarm/Kubernetes, Terraform/Ansible, CDN, WAF, alertas y rate limiting en el borde.

## Fuentes

- [Caddy: HTTPS automático y ACME](https://caddyserver.com/docs/automatic-https)
- [Compose: driver de logging `json-file` y rotación](https://docs.docker.com/engine/logging/drivers/json-file/)
- [Compose: fusión de listas al combinar ficheros](https://docs.docker.com/reference/compose-file/merge/)
- [PostgreSQL 18: `pg_dump` y formato custom](https://www.postgresql.org/docs/18/app-pgdump.html)
- [Spring Boot: logging estructurado](https://docs.spring.io/spring-boot/reference/features/logging.html#features.logging.structured)
- [DuckDNS](https://www.duckdns.org/)
- [ARCHITECTURE.md — invariantes 11 y 12](../ARCHITECTURE.md)
- [ADR-0002 — layout de `/data` y artefactos derivados](0002-frontera-spring-worker.md)
