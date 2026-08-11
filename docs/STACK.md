# Stack técnico — Editor web 3D de escenarios modulares

**Estado:** Draft v1  
**Principio:** pocas piezas, responsabilidades claras.

## Stack provisional

```text
Frontend
  React
  TypeScript
  Vite
  Three.js
  React Three Fiber
  Drei
  Zustand

Backend
  Java
  Spring Boot
  Spring Security
  Spring Data JPA
  Flyway
  PostgreSQL driver

Geometry
  Python
  Trimesh
  Manifold3D
  NumPy
  Blender headless (solo si se justifica)

Database
  PostgreSQL

Infra
  Docker
  Docker Compose
  Caddy
  Linux VPS

Storage
  local filesystem (/data)
  backup externo
```

## Política de versiones

Antes de generar proyectos ejecutables o añadir una API sensible a versión:

1. consultar documentación actual;
2. seleccionar releases estables compatibles;
3. fijar versiones y lockfiles;
4. documentar la decisión.

No usar versiones RC/beta salvo necesidad explícita y documentada.

## Backend

Spring MVC, no WebFlux. Dependencias iniciales esperadas:

```text
spring-boot-starter-web
spring-boot-starter-security
spring-boot-starter-validation
spring-boot-starter-data-jpa
spring-boot-starter-actuator
postgresql JDBC driver
flyway-core
```

Testing:

```text
spring-boot-starter-test
spring-security-test
Testcontainers PostgreSQL
```

No añadir Kafka, RabbitMQ, Redis, Spring Cloud o Spring Batch sin necesidad demostrada.

## Auth

Spring Security con sesión server-side y cookie HttpOnly. JWT no es necesario para el MVP.

## Persistencia

Spring Data JPA para CRUD y queries de dominio. Flyway para todo cambio de esquema. PostgreSQL real en tests donde importen JSONB, locking o `SKIP LOCKED`.

## Frontend

React + TypeScript + Vite. Three.js es el motor 3D del navegador y React Three Fiber integra el scene graph en React. Drei se utiliza cuando evita utilidades propias; Zustand mantiene estado transitorio del editor.

La fuente persistente autoritativa sigue siendo Spring + PostgreSQL.

## Geometry Worker

Proceso Python independiente, sin FastAPI ni puerto HTTP. Trimesh gestiona carga, inspección, transformaciones y export; Manifold3D es el motor booleano primario; NumPy maneja álgebra lineal.

Blender queda fuera de la dependencia mínima hasta que un spike justifique su uso.

## Formatos

- STL: fuente imprimible original y export final; convención de unidades en milímetros.
- GLB: preview web derivado y potencialmente simplificado.

El preview nunca reemplaza al STL original.

## Storage

Implementación MVP mediante filesystem local:

```text
/data/assets
/data/exports
/data/tmp
```

Spring y worker comparten el volumen; Caddy no expone el directorio.

Debe existir una abstracción pequeña `StorageService` para no contaminar el dominio con detalles de filesystem.

## Infraestructura

Caddy termina TLS, sirve la SPA y hace reverse proxy de `/api/*` a Spring. Docker Compose debe bastar para desarrollo integrado y producción inicial.

Servicios previstos:

```text
caddy
backend
geometry-worker
postgres
```

## Dependencias rechazadas por defecto en MVP

```text
Redis
Celery
RabbitMQ
Kafka
Kubernetes
MinIO
Elasticsearch
Next.js server runtime
FastAPI
Django
Spring WebFlux
Spring Cloud
Temporal
Airflow
```

## Regla para añadir tecnología

Toda nueva pieza debe justificar qué problema actual resuelve, por qué el stack existente no lo resuelve, cuál es su coste operativo y si sigue siendo razonable en un único VPS.
