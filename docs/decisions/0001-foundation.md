# ADR 0001: Single-Cafe Modular Monolith and Operational Surfaces

**Status:** Accepted for planning baseline  
**Date:** 2026-10-29  
**Project:** Smart Cafe System

## Context

A cafe needs customer self-ordering by QR/NFC, staff kitchen/serving, cashier order supervision, payment providers, game loans, and reliable transaction history. The project is also intended to demonstrate enterprise backend/DevOps skills without creating unjustified distributed-system overhead.

## Decision

- Product name is **Smart Cafe System** and target MVP is one cafe.
- Customer, waiter, KDS, cashier **Order Control Center**, admin interfaces share Next.js TypeScript application with separate access controls.
- Spring Boot Java backend uses a modular monolith; PostgreSQL stores all transactional truth.
- Customer uses table-visit scoped ephemeral identity and no mandatory account.
- Static QR/NFC locates table; rotating visit-specific join proof authorizes entry.
- Staff OCC is mandatory to oversee approval, reconciliation and order exceptions.
- Cash staff approval grants preparation eligibility, not payment confirmation.
- QRIS/VA handled by provider adapter in sandbox first, no frontend-declared success.
- Physical games are per-copy inventories and staff-delivered loans.
- Server-sent events are optional transport hints; backend read snapshots are authoritative.
- Docker and CI are introduced alongside executable project; extra infra deferred until useful.

## Consequences

Advantages: a deployable, testable, modest-operational-cost architecture; strong demonstration of Java domain modeling, transactions, RBAC, web UI, gateway integration, tests and DevOps.

Trade-offs: one backend deployed as unit; horizontal scaling and multi-tenant extraction may need future design. Avoid cross-module accidental coupling through package boundaries and integration tests.

## Rejected alternatives for initial stage

- Five separate frontend projects and microservices.
- Fully serverless per-request backend for money/state workflows.
- Client-only state management for paid/order/game availability.
- Permanent guest authentication via table QR.
- Kubernetes and Redis as mandatory MVP dependencies.

## Revisit when

Evidence justifies multiple cafe tenants/branches, sustained bottlenecks, required offline operations or other constraints. Record follow-up ADR before making architecture-breaking changes.
