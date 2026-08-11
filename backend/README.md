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

> El scaffold ejecutable, JDK y versión de Spring Boot se fijarán tras verificar la línea estable compatible.
