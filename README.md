# Baton

Baton is a multi-tenant, event-driven durable message exchange platform. This
repository contains the backend design and the first Spring Boot implementation
slice.

## Documents

- [Backend design](SPRING-BOOT-BACKEND-DESIGN.md)
- [Backend implementation](ss-backend/README.md)

## Current slice

The backend currently implements durable publish acceptance and the
transactional outbox path:

- `POST /v1/flows/{flowId}/messages`
- CloudEvents and active-revision JSON Schema validation
- message, expected subscriber snapshot, and outbox row committed in one
  transaction before `202 Accepted`
- idempotent replay and message-ID conflict detection
- outbox dispatcher with `SKIP LOCKED`, leases, fences, and retries
- Flyway schema and Testcontainers integration tests

## Quick start

See [ss-backend/README.md](ss-backend/README.md).
