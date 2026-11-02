# ADR-0002: Use Spring Boot 4.1 instead of 3.x

- Status: Accepted
- Date: 2026-11-01

## Context
The brief asks for a "Spring Boot 3.x compatible release" and also for "current, compatible, maintained dependency versions" and "avoid deprecated libraries". Spring Boot 3.5 is the last 3.x line. Its open-source support ended on 2026-06-30, and 3.5.16 (2026-06-25) was announced as its final OSS release. Spring Cloud 2025.0, the train for Boot 3.5, also ended OSS support on 2026-06-30.

## Decision
Use Spring Boot 4.1.x (4.1.2 at the time of writing) with Spring Cloud 2025.1.x "Oakwood" (2025.1.4; Boot 4.1 is supported from 2025.1.2). Java 21.

## Consequences
- Security fixes keep arriving for the life of the project.
- Spring Framework 7, Spring Security 7, Jackson 3 (`tools.jackson.*` packages) and modular starters (for example `spring-boot-starter-webmvc`, `spring-boot-starter-flyway`). Many online tutorials still show Boot 3 code; adapt them carefully.
- Testcontainers 2.x module names (`testcontainers-postgresql`, `testcontainers-kafka`).
- If 3.x is a hard requirement, switch the parent POM to 3.5.16 and Spring Cloud 2025.0.3. The architecture does not change, but no further security patches will be available.

## Alternatives
- Spring Boot 3.5.16: rejected, no longer maintained.
- Spring Boot 4.2: still at RC1 on 2026-11-01; adopt once GA if convenient.
