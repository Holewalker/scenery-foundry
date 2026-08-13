# Backend

Backend de producto basado en Spring Boot.

Responsabilidades previstas:

- autenticación y autorización;
- usuarios, assets, proyectos, escenas y niveles;
- print groups y exports;
- persistencia PostgreSQL + Flyway;
- lifecycle de geometry jobs;
- autorización y streaming de descargas privadas;
- API REST y health checks.

Arquitectura interna: modular monolith organizado por feature.

La geometría pesada no se ejecuta en Spring ni dentro del request HTTP.

## Quick path

```powershell
./mvnw.cmd test
# Start PostgreSQL from the repository root before running locally.
./mvnw.cmd spring-boot:run
```

El scaffold usa JDK 25, Spring Boot 4.1 y Maven Wrapper 3.9.16. Incluye Actuator health,
PostgreSQL/Flyway y el contrato mínimo de CSRF con `HttpSessionCsrfTokenRepository`.
Testcontainers valida la migración cuando Docker está disponible; de lo contrario esa
prueba se marca como omitida de forma explícita.

Para el segundo comando, ejecuta antes `docker compose up postgres --wait` desde la raíz o
proporciona `DATABASE_URL`, `DATABASE_USER` y `DATABASE_PASSWORD` equivalentes.

Registro/login, tablas de usuario, ownership y jobs no forman parte de este bootstrap.
