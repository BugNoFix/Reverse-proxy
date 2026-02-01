# Reverse Proxy

A production-ready, reactive (non-blocking) HTTP reverse proxy built with Spring Boot WebFlux (Netty), featuring host-based routing, multiple load-balancing strategies, and RFC 7234-compliant caching with conditional revalidation (ETag / Last-Modified).

[![Java](https://img.shields.io/badge/Java-17-orange.svg)](https://openjdk.java.net/projects/jdk/17/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-4.0.2-green.svg)](https://spring.io/projects/spring-boot)
[![WebFlux](https://img.shields.io/badge/WebFlux-Reactive-blue.svg)](https://docs.spring.io/spring-framework/reference/web/webflux.html)

## Table of Contents
1. [Overview](#overview)
2. [Key Features](#key-features)
3. [Architecture](#architecture)
4. [Request Flow](#request-flow)
5. [Sequence Diagrams](#sequence-diagrams)
6. [Getting Started](#getting-started)
7. [Configuration](#configuration)
8. [Load Balancing](#load-balancing)
9. [Caching](#caching)
10. [API / Behavior Reference](#api--behavior-reference)
12. [Testing](#testing)

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

## Key Features


### ⚖️ Load Balancing
Per-service load balancing strategies for traffic distribution

###  HTTP Caching

### 🔧 Header Management
Automatic management of `X-Forwarded-For`, `X-Forwarded-Proto`, and `X-Forwarded-Host`. Strips standard Hop-by-Hop headers (e.g., `Connection`, `Upgrade`) as well as any custom headers listed in the `Connection` header, ensuring strict RFC compliance.


## Sequence Diagrams

These diagrams illustrate the detailed interactions between components for common scenarios.

### 1. Cache MISS (First Request)
The proxy forwards the request and stores the response.
![](img/sequence1.svg)


### 2. Cache HIT (Fresh)
The cached resource is within `max-age`. No backend contact needed.
![](img/sequence2.svg)


### 3. Cache Revalidation - Not Modified (304)
Resource is stale (`Age > max-age`) but has validators (`ETag`). Backend confirms it's unchanged.
![](img/sequence3.svg)

### 4. Cache Revalidation - Modified (200)
Resource is stale and HAS changed on the backend.
![](img/sequence4.svg)


### 5. Stale Cache without Validators
Resource is stale but has NO `ETag` or `Last-Modified`. Must re-download fully.
![](img/sequence5.svg)

---

## Load Balancing

### Available Strategies

#### Round-Robin
Distributes requests sequentially across all hosts in a circular order. Ideal for stateless services with even capacity.

#### Random
Selects a random host for each request. Good for simple load distribution without state maintenance.


## Caching

Implements RFC 7234 compliant HTTP caching.

### Supported Features
*   **Validation**: Conditional requests using `ETag` and `Last-Modified`.
*   **Directives**: Support for `Cache-Control` directives (`public`, `private`, `no-cache`, `no-store`, `max-age`, `s-maxage`).
*   **Vary Support**: Caches different responses based on `Vary` headers.
*   **Eviction**: Thread-safe LRU eviction policy.

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
