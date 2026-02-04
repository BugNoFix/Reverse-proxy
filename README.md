# Reverse Proxy

[![Java](https://img.shields.io/badge/Java-17-orange.svg)](https://openjdk.java.net/projects/jdk/17/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-4.0.2-green.svg)](https://spring.io/projects/spring-boot)
[![WebFlux](https://img.shields.io/badge/WebFlux-Reactive-blue.svg)](https://docs.spring.io/spring-framework/reference/web/webflux.html)

## Table of Contents
1. [Overview](#overview)
2. [Architectural Design](#architectural-design)
3. [Request Flow](#request-flow)
4. [Sequence Diagrams](#sequence-diagrams)
5. [Configuration](#configuration)
6. [Load Balancing](#load-balancing)
7. [Caching](#caching)
8. [Testing](#testing)

---

## Overview

This service acts as a **high-performance reverse proxy** that receives inbound HTTP requests and intelligently routes them to downstream services based on the request's `Host` header. It leverages Spring WebFlux's reactive architecture to handle thousands of concurrent connections with minimal resource overhead.

**Tech Stack**
- **Java 17** - Modern LTS version with performance improvements
- **Spring Boot 4.0.2** - Latest stable release
- **Spring WebFlux** - Reactive stack with non-blocking I/O
- **Netty Runtime** - High-performance async event-driven network framework
- **Maven** - Build automation (wrapper included)

---

## Architectural Design

- **⚡ Non-Blocking Event Loop**: Built on **Netty** and **Spring WebFlux**, the proxy uses a fixed number of event loop threads. This architecture minimizes context switching and memory overhead, enabling the handling of thousands of concurrent connections.
- **⚖️ Pluggable Load Balancing Strategy**: Routing logic implements the *Strategy Pattern* via the `LoadBalancer` interface. This decoupling allows distinct algorithms (e.g., Round-Robin, Random) to be applied per-service in `application.yml` without code changes.
- **💾 HTTP-Compliant Caching Layer**: The caching system implements validation logic using `ETag` / `Last-Modified`, supports `Vary`
- **🔧 Protocol Compliance Engine**: The system manages `X-Forwarded-*` headers for client traceability and strips both standard (e.g., `Connection`, `Keep-Alive`) and dynamic Hop-by-Hop headers (specified in `Connection` header).



## Request Flow

1.  **Incoming Request**: Netty accepts the connection.
2.  **Routing**: `ServiceRegistry` identifies the target backend service based on the `Host` header.
3.  **Cache Lookup**: `CacheService` checks for a valid response (Non-blocking access).
4.  **Load Balancing**: If not cached, `LoadBalancer` selects a specific backend host.
5.  **Proxying**: `WebClient` forwards the request asynchronously.
6.  **Response Handling**: The response is streamed back to the client and asynchronously stored in the cache.

---

## Sequence Diagrams

These diagrams illustrate the detailed interactions between components for common scenarios.

### 1. Cache MISS (First Request)
The proxy forwards the request and stores the response.
![Cache MISS sequence diagram](img/sequence1.svg)


### 2. Cache HIT (Fresh)
The cached resource is within `max-age`. No backend contact needed.
![Cache HIT (fresh) sequence diagram](img/sequence2.svg)


### 3. Cache Revalidation - Not Modified (304)
Resource is stale (`Age > max-age`) but has validators (`ETag`). Backend confirms it's unchanged.
![Cache revalidation (304 Not Modified) sequence diagram](img/sequence3.svg)

### 4. Cache Revalidation - Modified (200)
Resource is stale and HAS changed on the backend.
![Cache revalidation (200 Modified) sequence diagram](img/sequence4.svg)


### 5. Stale Cache without Validators
Resource is stale but has NO `ETag` or `Last-Modified`. Must re-download fully.
![Stale cache without validators sequence diagram](img/sequence5.svg)

## Configuration

Configuration is split into a **baseline** file plus **profile-specific** overrides.

### Files

- `src/main/resources/application.yml`: shared defaults (server bind, logging, common proxy settings).
- `src/main/resources/application-local.yml`: local environment values 
- `src/main/resources/application-prod.yml`: production environment values
---

## Load Balancing

### Available Strategies

#### Round-Robin
Distributes requests sequentially across all hosts in a circular order. Ideal for stateless services with even capacity.

#### Random
Selects a random host for each request. Good for simple load distribution without state maintenance.

---

## Caching

Implements RFC 9110 compliant HTTP caching.

### Supported Features
- **Validation**: Conditional requests using `ETag` and `Last-Modified`.
- **Directives**: Support for `Cache-Control` directives (`public`, `private`, `no-cache`, `no-store`, `max-age`, `s-maxage`).
- **Vary Support**: Caches different responses based on `Vary` headers.
- **Eviction**: Thread-safe LRU eviction policy.

### Cache Architecture

```
┌──────────────────────────────────────┐
│         CacheService                 │
│  ┌────────────────────────────────┐  │
│  │    ConcurrentLinkedHashMap     │  │
│  │         (LRU Eviction)         │  │
│  └────────────────────────────────┘  │
│              ▲         │              │
│              │         ▼              │
│         CacheKey   CachedResponse    │
└──────────────────────────────────────┘
```

### Cache Key Composition

Cache keys are composite to support `Vary` header:

```
CacheKey = {
  method: String          // GET, HEAD
  url: String            // Full request URL
  varyHeaders: Map       // Headers specified in Vary
}
```
