---
name: pattern-detector
description: >
  Detect architectural patterns, anti-patterns, and design smells in a Java/Spring Boot codebase.
  Use during ANALYZE or CROSS_REF phases to identify what needs to change.
---

# Pattern detector skill

## Patterns to detect

### Good patterns (note as strengths)
- Repository pattern via Spring Data JPA
- DTO separation from domain entities
- Service interfaces with implementations
- Domain events via ApplicationEvent
- Configuration via @ConfigurationProperties
- Externalized config via application.yml

### Anti-patterns to flag (note as weaknesses)
- **Anemic domain model**: entities with only getters/setters, all logic in services
- **God class**: single class > 300 lines doing too many things
- **Controller logic**: business logic directly in @RestController methods
- **Direct repository calls from controller**: skipping the service layer
- **Hardcoded values**: magic strings/numbers not in config
- **Missing transactions**: write operations without @Transactional
- **Circular dependencies**: service A depends on service B and vice versa
- **Catch and ignore**: empty catch blocks or catch(Exception e) {}
- **N+1 queries**: loops that call repository methods

## How to detect each
```
# God classes
GrepTool: search for class declarations, then check file size

# Anemic domain
GrepTool: find @Entity classes with no methods beyond getters/setters

# Controller logic
GrepTool: find @RestController files, look for business logic keywords
(if/else chains, calculations, multiple service calls in one method)

# Missing transactions
GrepTool: find @Service methods with save/update/delete that lack @Transactional

# Circular deps
GrepTool: map @Autowired/@RequiredArgsConstructor fields in each service
```

## Output format
```
## Patterns detected
### Strengths
- Repository pattern: ✓ consistent across all data access
- DTO separation: ✓ found in com.example.dto

### Anti-patterns found
| Location | Anti-pattern | Severity | Notes |
|----------|-------------|----------|-------|
| UserService.java | God class (847 lines) | HIGH | Split into UserAuthService + UserProfileService |
| OrderController.java:45 | Business logic in controller | MEDIUM | Move to OrderService |
```
