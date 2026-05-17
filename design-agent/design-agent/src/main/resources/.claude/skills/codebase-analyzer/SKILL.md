---
name: codebase-analyzer
description: >
  Deep analysis of Java/Spring Boot codebase structure, patterns, and tech debt.
  Use during the ANALYZE phase to map architecture before producing a design.
---

# Codebase analyzer skill

## Exploration order
1. Start with `GlobTool` — find all Java files: `**/*.java`
2. Find entry points: `GrepTool` for `@RestController`, `@SpringBootApplication`
3. Map the layers: controller → service → repository → domain
4. Find config: `@Configuration`, `application.yml`, `application.properties`
5. Find integration points: `@FeignClient`, `WebClient`, `RestTemplate`, `@KafkaListener`
6. Spot patterns: `@EventListener`, `@Async`, `@Scheduled`, `@Transactional`

## Pattern detection checklist
- **Layered architecture**: controllers → services → repositories
- **Hexagonal**: ports (interfaces) + adapters (implementations)
- **Event-driven**: `ApplicationEvent`, Kafka, RabbitMQ
- **CQRS**: separate read/write models
- **Saga**: distributed transaction coordination
- **Anti-patterns to flag**: God classes, anemic domain models,
  business logic in controllers, direct DB calls from controllers

## Tech debt indicators
- Classes > 300 lines
- Methods > 30 lines
- `@SuppressWarnings` usage
- TODO/FIXME comments
- Direct SQL strings in Java code
- Missing `@Transactional` on write operations
- Hardcoded configuration values

## Output format
```
## Project structure
src/main/java/com/example/
├── controller/   [REST entry points]
├── service/      [Business logic]
├── repository/   [Data access]
├── domain/       [Entities and value objects]
└── config/       [Spring configuration]

## Patterns detected
- Layered architecture ✓
- Event-driven (Kafka) ✓
- Missing: proper domain model (anemic domain detected)

## Tech debt
- UserService.java:245 — God class, 847 lines
- OrderController.java:12 — Business logic in controller

## Key components
| Component | Responsibility | Dependencies |
|-----------|---------------|--------------|
| UserService | User CRUD + auth | UserRepository, EmailService |
```
