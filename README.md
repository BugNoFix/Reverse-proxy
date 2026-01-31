# Reverse Proxy

A production-ready, reactive (non-blocking) HTTP reverse proxy built with Spring Boot WebFlux (Netty), featuring host-based routing, multiple load-balancing strategies, downstream health checks, and RFC 7234-compliant caching with conditional revalidation (ETag / Last-Modified).

[![Java](https://img.shields.io/badge/Java-17-orange.svg)](https://openjdk.java.net/projects/jdk/17/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-4.0.2-green.svg)](https://spring.io/projects/spring-boot)
[![WebFlux](https://img.shields.io/badge/WebFlux-Reactive-blue.svg)](https://docs.spring.io/spring-framework/reference/web/webflux.html)

## Table of Contents
1. [Overview](#overview)
2. [Key Features](#key-features)
3. [Architecture](#architecture)
4. [Request Flow](#request-flow)
5. [Getting Started](#getting-started)
6. [Configuration](#configuration)
7. [Load Balancing](#load-balancing)
8. [Caching](#caching)
9. [Health Checks](#health-checks)
10. [API / Behavior Reference](#api--behavior-reference)
11. [Testing](#testing)

---

## Overview

This service acts as a **high-performance reverse proxy** that receives inbound HTTP requests and intelligently routes them to downstream services based on the request's `Host` header. It leverages Spring WebFlux's reactive architecture to handle thousands of concurrent connections with minimal resource overhead.

### What It Does
- **Intelligent Routing**: Maps incoming requests to backend services using host-based routing
- **Load Distribution**: Balances traffic across multiple service instances
- **High Availability**: Monitors backend health and automatically routes around failed instances
- **Performance Optimization**: Caches responses following RFC 7234 standards to reduce backend load
- **Seamless Integration**: Handles header forwarding, hop-by-hop filtering, and conditional requests

### Why Use This?
- ✅ **Reactive & Non-blocking**: Built on Project Reactor for maximum throughput
- ✅ **Zero External Dependencies**: No Redis, no external cache servers
- ✅ **Production Ready**: Comprehensive health checks, metrics, and error handling
- ✅ **Easy to Configure**: YAML-based configuration with profile support
- ✅ **Fully Tested**: Extensive unit and integration test coverage

**Tech Stack**
- **Java 17** - Modern LTS version with performance improvements
- **Spring Boot 4.0.2** - Latest stable release
- **Spring WebFlux** - Reactive stack with non-blocking I/O
- **Netty Runtime** - High-performance async event-driven network framework
- **Maven** - Build automation (wrapper included)

---

## Key Features

### 🎯 Host-Based Routing
Service selection driven by the HTTP `Host` header, enabling multi-tenant architectures and domain-based routing.

**Flow Diagram:**
```
┌──────────────────────────────────────────────┐
│  Incoming Request                             │
│  GET /api/users                               │
│  Host: api.mycompany.com                      │
└──────────────┬───────────────────────────────┘
               │
               ▼
┌──────────────────────────────────────────────┐
│  ProxyController                              │
│  1. Extract Host header                       │
│  2. Parse domain (strip port)                 │
└──────────────┬───────────────────────────────┘
               │
               ▼
┌──────────────────────────────────────────────┐
│  ServiceRegistry.findByDomain()               │
│  "api.mycompany.com" → Service Config         │
└──────────────┬───────────────────────────────┘
               │
               ├─ FOUND ──▶ Continue to load balancer
               │
               └─ NOT FOUND ──▶ Return 404 Not Found
```

### ⚖️ Load Balancing
Per-service load balancing strategies with automatic health-aware host selection:

**Round-Robin Flow:**
```
Request 1 ──▶ Counter=0 ──▶ hosts[0 % 3] ──▶ host1:8080
Request 2 ──▶ Counter=1 ──▶ hosts[1 % 3] ──▶ host2:8080
Request 3 ──▶ Counter=2 ──▶ hosts[2 % 3] ──▶ host3:8080
Request 4 ──▶ Counter=3 ──▶ hosts[3 % 3] ──▶ host1:8080 (cycle)

With Unhealthy Host:
Request N ──▶ Select host2 ──▶ Unhealthy?
                               ├─ YES ──▶ Skip, try next
                               └─ NO  ──▶ Use this host
```

**Random Flow:**
```
Request 1 ──▶ Random(0,2) ──▶ 1 ──▶ host2:8080
Request 2 ──▶ Random(0,2) ──▶ 0 ──▶ host1:8080
Request 3 ──▶ Random(0,2) ──▶ 2 ──▶ host3:8080
Request 4 ──▶ Random(0,2) ──▶ 1 ──▶ host2:8080

Statistical Distribution:
Over 1000 requests → ~33% each host (for 3 hosts)
```

### 💚 Health Monitoring

**Health Check Cycle:**
```
┌────────────────────────────────────────────┐
│  @Scheduled(fixedRate = 30 seconds)        │
└─────────────────┬──────────────────────────┘
                  │
                  ▼
      ┌───────────────────────┐
      │  For each Service     │
      └──────────┬────────────┘
                 │
                 ▼
      ┌───────────────────────┐
      │  For each Host        │
      └──────────┬────────────┘
                 │
                 ├──▶ WebClient.get("/health")
                 │    .timeout(5 seconds)
                 │
                 ▼
      ┌───────────────────────┐
      │  Response?            │
      └──────────┬────────────┘
                 │
      ┌──────────┴────────────┐
      │                       │
      ▼                       ▼
   200 OK               Error/Timeout
      │                       │
      ▼                       ▼
  Mark HEALTHY            Mark UNHEALTHY
      │                       │
      └───────────┬───────────┘
                  │
                  ▼
      ┌───────────────────────┐
      │  Update Registry      │
      │  Log transition       │
      └───────────────────────┘
```

**State Transitions:**
```
         [START]
            │
            ▼
        UNKNOWN (treat as healthy)
            │
            │ First health check
            ▼
    ┌─────────────┐
    │   HEALTHY   │◄─────────────┐
    └──────┬──────┘              │
           │                     │
           │ Fail                │
           │ (timeout/error)     │
           │                     │
           ▼                     │
    ┌─────────────┐              │
    │  UNHEALTHY  │              │
    └──────┬──────┘              │
           │                     │
           │ Success             │
           │ (200 OK)            │
           └─────────────────────┘
```

### 🚀 HTTP Caching (RFC 7234 Compliant)

**Cache Lookup Flow:**
```
┌─────────────────────────────────────┐
│  Incoming GET/HEAD Request          │
└──────────────┬──────────────────────┘
               │
               ▼
┌─────────────────────────────────────┐
│  Generate Cache Key                 │
│  = (method, URL, Vary headers)      │
└──────────────┬──────────────────────┘
               │
               ▼
┌─────────────────────────────────────┐
│  Lookup in CacheService             │
└──────────────┬──────────────────────┘
               │
        ┌──────┴───────┐
        │              │
        ▼              ▼
    NOT FOUND      FOUND
        │              │
        │              ▼
        │   ┌─────────────────────┐
        │   │  Calculate Age      │
        │   │  age = now - cached │
        │   └──────┬──────────────┘
        │          │
        │          ▼
        │   ┌─────────────────────┐
        │   │  age < max-age?     │
        │   └──────┬──────────────┘
        │          │
        │     ┌────┴────┐
        │     │         │
        │     ▼         ▼
        │   FRESH    STALE
        │     │         │
        │     │         ▼
        │     │   ┌─────────────────┐
        │     │   │  Has validators? │
        │     │   │  (ETag/LastMod) │
        │     │   └──────┬──────────┘
        │     │          │
        │     │     ┌────┴────┐
        │     │     │         │
        │     │     ▼         ▼
        │     │   YES        NO
        │     │     │         │
        │     │     │         └──▶ Cache MISS
        │     │     │              (Expired, no revalidation)
        │     │     │
        │     │     └──▶ Revalidate
        │     │          (Add If-None-Match/If-Modified-Since)
        │     │
        │     └──▶ Cache HIT
        │          (Return cached, add Age header)
        │
        └──▶ Cache MISS
             (Forward to backend)
```

**Revalidation Flow:**
```
┌────────────────────────────────────────┐
│  Stale cached entry with ETag          │
│  ETag: "abc123"                        │
│  Last-Modified: Mon, 01 Jan 2024 12:00 │
└──────────────┬─────────────────────────┘
               │
               ▼
┌────────────────────────────────────────┐
│  Forward request with validators       │
│  If-None-Match: "abc123"               │
│  If-Modified-Since: Mon, 01 Jan ...    │
└──────────────┬─────────────────────────┘
               │
               ▼
┌────────────────────────────────────────┐
│  Downstream Response                   │
└──────────────┬─────────────────────────┘
               │
        ┌──────┴──────┐
        │             │
        ▼             ▼
   304 Not        200 OK
   Modified       (New data)
        │             │
        ▼             ▼
   ┌─────────┐   ┌─────────┐
   │ Refresh │   │ Replace │
   │ metadata│   │  cache  │
   │ Serve   │   │  entry  │
   │ cached  │   │  Return │
   │  body   │   │   new   │
   └─────────┘   └─────────┘
        │             │
        ▼             ▼
   X-Cache:      X-Cache:
 REVALIDATED      MISS
```

### 🔧 Header Management

**Header Processing Flow:**
```
┌────────────────────────────────────────┐
│  Request Headers from Client           │
│  Host: api.example.com                 │
│  Connection: keep-alive                │
│  Authorization: Bearer xyz             │
│  User-Agent: curl/7.64.1               │
└──────────────┬─────────────────────────┘
               │
               ▼
┌────────────────────────────────────────┐
│  Filter Hop-by-Hop Headers             │
│  REMOVE: Connection, Keep-Alive,       │
│          Proxy-Authorization, etc.     │
└──────────────┬─────────────────────────┘
               │
               ▼
┌────────────────────────────────────────┐
│  Add X-Forwarded-* Headers             │
│  X-Forwarded-For: 192.168.1.100        │
│  X-Forwarded-Proto: http               │
│  X-Forwarded-Host: api.example.com     │
│  X-Forwarded-Port: 80                  │
└──────────────┬─────────────────────────┘
               │
               ▼
┌────────────────────────────────────────┐
│  Forward to Downstream                 │
│  Host: backend-host:8080               │
│  Authorization: Bearer xyz             │
│  User-Agent: curl/7.64.1               │
│  X-Forwarded-For: 192.168.1.100        │
│  X-Forwarded-Proto: http               │
│  X-Forwarded-Host: api.example.com     │
│  X-Forwarded-Port: 80                  │
└────────────────────────────────────────┘
```

---

## Architecture

### System Overview

```
                           Internet
                              │
                              ▼
                    ┌──────────────────┐
                    │   Reverse Proxy  │
                    │   (Netty:8080)   │
                    │                  │
                    │  ┌────────────┐  │
                    │  │Controller  │  │
                    │  └─────┬──────┘  │
                    │        │         │
                    │        ▼         │
                    │  ┌────────────┐  │
                    │  │  Service   │  │
                    │  │  Registry  │  │
                    │  └─────┬──────┘  │
                    │        │         │
                    │        ▼         │
                    │  ┌────────────┐  │
                    │  │   Load     │  │
                    │  │  Balancer  │  │
                    │  └─────┬──────┘  │
                    │        │         │
                    │        ▼         │
                    │  ┌────────────┐  │
                    │  │   Proxy    │  │
                    │  │  Service   │  │
                    │  └─────┬──────┘  │
                    │        │         │
                    │  ┌─────┴──────┐  │
                    │  │   Cache    │  │
                    │  │  Service   │  │
                    │  └────────────┘  │
                    └──────┬───────────┘
                           │
            ┌──────────────┼──────────────┐
            │              │              │
            ▼              ▼              ▼
    ┌──────────────┐ ┌──────────────┐ ┌──────────────┐
    │  Service A   │ │  Service B   │ │  Service C   │
    │              │ │              │ │              │
    │ ┌──────────┐ │ │ ┌──────────┐ │ │ ┌──────────┐ │
    │ │ Host 1   │ │ │ │ Host 1   │ │ │ │ Host 1   │ │
    │ │ :9090    │ │ │ │ :8080    │ │ │ │ :7070    │ │
    │ │ HEALTHY  │ │ │ │ HEALTHY  │ │ │ │ HEALTHY  │ │
    │ └──────────┘ │ │ └──────────┘ │ │ └──────────┘ │
    │              │ │              │ │              │
    │ ┌──────────┐ │ │ ┌──────────┐ │ └──────────────┘
    │ │ Host 2   │ │ │ │ Host 2   │ │
    │ │ :9091    │ │ │ │ :8081    │ │
    │ │ UNHEALTHY│ │ │ │ HEALTHY  │ │
    │ └──────────┘ │ │ └──────────┘ │
    └──────────────┘ └──────────────┘
```

### Component Interaction Flow

```
┌─────────────────────────────────────────────────────┐
│  1. ProxyController                                 │
│     - Receives HTTP request                         │
│     - Extracts Host header                          │
│     - Returns Mono<ServerResponse>                  │
└──────────────┬──────────────────────────────────────┘
               │
               ▼
┌─────────────────────────────────────────────────────┐
│  2. ServiceRegistry                                 │
│     - Map<String, Service> services                 │
│     - findServiceByDomain(host)                     │
│     - Returns Service or throws exception           │
└──────────────┬──────────────────────────────────────┘
               │
               ▼
┌─────────────────────────────────────────────────────┐
│  3. HealthCheckService (Background)                 │
│     - @Scheduled periodic checks                    │
│     - Updates host health status                    │
│     - ServiceRegistry.updateHealth(host, status)    │
└─────────────────────────────────────────────────────┘
               │
               ▼
┌─────────────────────────────────────────────────────┐
│  4. LoadBalancer (Strategy Pattern)                 │
│     - getHealthyHosts() from registry               │
│     - Apply strategy:                               │
│       • RoundRobin: counter % hosts.size()          │
│       • Random: ThreadLocalRandom.nextInt()         │
│     - Returns selected Host                         │
└──────────────┬──────────────────────────────────────┘
               │
               ▼
┌─────────────────────────────────────────────────────┐
│  5. ProxyService                                    │
│     - buildTargetUrl(host, path)                    │
│     - prepareHeaders(request)                       │
│     - checkCache() [if GET/HEAD]                    │
│     - WebClient.method().uri()...                   │
│     - Forward request (reactive)                    │
│     - storeInCache() [if cacheable]                 │
│     - Returns Mono<ServerResponse>                  │
└──────────────┬──────────────────────────────────────┘
               │
               ▼
┌─────────────────────────────────────────────────────┐
│  6. CacheService (if applicable)                    │
│     - ConcurrentLinkedHashMap<CacheKey, Cached>     │
│     - get(key): Check freshness                     │
│     - put(key, response): Store with metadata       │
│     - LRU eviction when maxSize reached             │
└─────────────────────────────────────────────────────┘
               │
               ▼
┌─────────────────────────────────────────────────────┐
│  7. Return Response to Client                       │
│     - Status code                                   │
│     - Headers (+ X-Cache, Age, Via)                 │
│     - Body (streamed reactively)                    │
└─────────────────────────────────────────────────────┘
```

### Data Flow - Component Level

```
Request Path:
─────────────

ServerRequest → ProxyController.handleRequest()
                      │
                      └──▶ ServiceRegistry.findByDomain(host)
                              │
                              ├─ Found: Service
                              │    │
                              │    └──▶ LoadBalancer.selectHost(service)
                              │             │
                              │             └──▶ Host (healthy)
                              │                   │
                              │                   └──▶ ProxyService.forward(request, host)
                              │                            │
                              │                            ├─ Check Cache (GET/HEAD)
                              │                            │    │
                              │                            │    ├─ HIT: return cached
                              │                            │    └─ MISS: continue
                              │                            │
                              │                            └──▶ WebClient.request()
                              │                                      │
                              │                                      └──▶ Downstream
                              │                                             │
                              │                                             └──▶ Response
                              │                                                   │
                              │                                                   ├─ Store in cache?
                              │                                                   │
                              │                                                   └──▶ Return
                              │
                              └─ Not Found: 404 Error Response


Health Check Path (Background):
────────────────────────────────

@Scheduled → HealthCheckService.performChecks()
                │
                └──▶ For each Service in Registry
                       │
                       └──▶ For each Host in Service
                              │
                              └──▶ WebClient.get(host + "/health")
                                     .timeout(5s)
                                     │
                                     ├─ Success (200): 
                                     │    └──▶ ServiceRegistry.markHealthy(host)
                                     │
                                     └─ Failure/Timeout:
                                          └──▶ ServiceRegistry.markUnhealthy(host)
```

---

## Request Flow

### Complete Request Flow with Decision Points

```
┌───────────────────────────────────────────────────────┐
│  1. CLIENT SENDS REQUEST                              │
│     GET /api/users HTTP/1.1                           │
│     Host: api.mycompany.com                           │
│     Accept: application/json                          │
└──────────────────┬────────────────────────────────────┘
                   │
                   ▼
┌───────────────────────────────────────────────────────┐
│  2. PROXY RECEIVES REQUEST (Netty)                    │
│     - Parse HTTP headers                              │
│     - Extract Host header                             │
└──────────────────┬────────────────────────────────────┘
                   │
                   ▼
┌───────────────────────────────────────────────────────┐
│  3. CONTROLLER LAYER                                  │
│     ProxyController.handleRequest(request)            │
│     - Domain = "api.mycompany.com"                    │
└──────────────────┬────────────────────────────────────┘
                   │
                   ▼
┌───────────────────────────────────────────────────────┐
│  4. SERVICE REGISTRY LOOKUP                           │
│     ServiceRegistry.findByDomain("api.mycompany.com") │
└──────────────────┬────────────────────────────────────┘
                   │
            ┌──────┴───────┐
            │              │
            ▼              ▼
        FOUND          NOT FOUND
            │              │
            │              └──▶ Return 404
            │                   {"error": "Service not found"}
            │
            ▼
┌───────────────────────────────────────────────────────┐
│  5. CHECK REQUEST METHOD                              │
└──────────────────┬────────────────────────────────────┘
                   │
            ┌──────┴───────┐
            │              │
            ▼              ▼
        GET/HEAD      POST/PUT/DELETE
            │              │
            │              └──▶ Skip cache, goto step 7
            │
            ▼
┌───────────────────────────────────────────────────────┐
│  6. CACHE LOOKUP                                      │
│     CacheService.get(method, url, varyHeaders)        │
└──────────────────┬────────────────────────────────────┘
                   │
        ┌──────────┼──────────┐
        │          │          │
        ▼          ▼          ▼
     NOT FOUND   FRESH      STALE
        │          │          │
        │          │          ├─ Has validators?
        │          │          │   ├─ YES: Revalidate (step 8)
        │          │          │   └─ NO: Continue to step 7
        │          │          │
        │          └──▶ CACHE HIT
        │               - Add Age header
        │               - Add X-Cache: HIT
        │               - Return cached body
        │               - END
        │
        └──▶ CACHE MISS
             Continue to step 7
             │
             ▼
┌───────────────────────────────────────────────────────┐
│  7. LOAD BALANCER                                     │
│     LoadBalancer.selectHost(service)                  │
│     - Filter healthy hosts                            │
│     - Apply strategy (Round-Robin/Random)             │
└──────────────────┬────────────────────────────────────┘
                   │
        ┌──────────┴──────────┐
        │                     │
        ▼                     ▼
   Healthy hosts         No healthy hosts
   available                  │
        │                     └──▶ Return 503
        │                          {"error": "No healthy hosts"}
        │
        ▼
┌───────────────────────────────────────────────────────┐
│  8. PREPARE REQUEST                                   │
│     ProxyService.prepareHeaders()                     │
│     - Filter hop-by-hop headers                       │
│     - Add X-Forwarded-* headers                       │
│     - Add conditional headers (if revalidating)       │
│       • If-None-Match: "<etag>"                       │
│       • If-Modified-Since: "<date>"                   │
└──────────────────┬────────────────────────────────────┘
                   │
                   ▼
┌───────────────────────────────────────────────────────┐
│  9. FORWARD TO DOWNSTREAM                             │
│     WebClient.method(method)                          │
│       .uri("http://host:port/api/users")              │
│       .headers(prepared)                              │
│       .retrieve()                                     │
│       .toEntity()                                     │
└──────────────────┬────────────────────────────────────┘
                   │
        ┌──────────┼──────────┬──────────┐
        │          │          │          │
        ▼          ▼          ▼          ▼
     200 OK   304 Not Mod  5xx Error  Timeout
        │          │          │          │
        │          │          │          └──▶ Return 504
        │          │          │               {"error": "Timeout"}
        │          │          │
        │          │          └──▶ Return 502
        │          │               {"error": "Bad Gateway"}
        │          │
        │          └──▶ REVALIDATION SUCCESS
        │               - Refresh cache metadata
        │               - Return cached body
        │               - X-Cache: REVALIDATED
        │               - END
        │
        ▼
┌───────────────────────────────────────────────────────┐
│  10. PROCESS RESPONSE                                 │
│      - Check if cacheable                             │
│        • GET/HEAD?                                    │
│        • Cache-Control: public/max-age?               │
│        • Not private/no-store?                        │
└──────────────────┬────────────────────────────────────┘
                   │
            ┌──────┴──────┐
            │             │
            ▼             ▼
       CACHEABLE     NOT CACHEABLE
            │             │
            ▼             └──▶ Skip caching
┌───────────────────────────────────────┐
│  11. STORE IN CACHE                   │
│      CacheService.put()               │
│      - Store body + headers           │
│      - Extract max-age                │
│      - Extract validators             │
│      - Store timestamp                │
└──────────────────┬────────────────────┘
                   │
                   └───────┬
                           │
                           ▼
┌───────────────────────────────────────────────────────┐
│  12. RETURN TO CLIENT                                 │
│      - Status code                                    │
│      - Headers:                                       │
│        • Original headers (filtered)                  │
│        • X-Cache: HIT/MISS/REVALIDATED                │
│        • Age: <seconds> (if cached)                   │
│        • Via: 1.1 reverse-proxy                       │
│      - Body (streamed)                                │
└───────────────────────────────────────────────────────┘
```

### Error Handling Flow

```
┌────────────────────────────────────────┐
│  Request Processing                    │
└──────────────┬─────────────────────────┘
               │
               ▼
       ┌───────────────┐
       │  Try Process  │
       └───────┬───────┘
               │
        ┌──────┴──────┐
        │             │
        ▼             ▼
    SUCCESS        EXCEPTION
        │             │
        │             ├──▶ ServiceNotFoundException
        │             │    └──▶ 404 Not Found
        │             │
        │             ├──▶ NoHealthyHostsException
        │             │    └──▶ 503 Service Unavailable
        │             │
        │             ├──▶ WebClientRequestException
        │             │    └──▶ 502 Bad Gateway
        │             │
        │             ├──▶ TimeoutException
        │             │    └──▶ 504 Gateway Timeout
        │             │
        │             └──▶ Other Exception
        │                  └──▶ 500 Internal Server Error
        │
        └──▶ Return Response
```

---

## Load Balancing

### Available Strategies

#### Round-Robin (Default)
Distributes requests sequentially across all healthy hosts in a circular order.

**Configuration:**
```yaml
proxy:
  services:
    - name: my-service
      strategy: round-robin
      hosts:
        - address: host1
          port: 8080
        - address: host2
          port: 8080
        - address: host3
          port: 8080
```

**Behavior:**
- Request 1 → host1:8080
- Request 2 → host2:8080
- Request 3 → host3:8080
- Request 4 → host1:8080 (cycle repeats)

**Best for:**
- Even distribution of load
- Predictable traffic patterns
- Stateless services

**Implementation:**
- Thread-safe atomic counter
- O(1) selection time
- Automatic skip of unhealthy hosts

#### Random
Selects a random healthy host for each request.

**Configuration:**
```yaml
proxy:
  services:
    - name: my-service
      strategy: random
      hosts:
        - address: host1
          port: 8080
        - address: host2
          port: 8080
```

**Behavior:**
- Each request randomly assigned
- Statistical distribution over time
- No state maintained

**Best for:**
- Simple load distribution
- Services with varying capacity
- Avoiding thundering herd

**Implementation:**
- ThreadLocalRandom for performance
- No synchronization needed
- O(1) selection time

### Health-Aware Selection

Both strategies automatically filter out unhealthy hosts:

```java
// Pseudo-code
hosts = service.getHosts()
healthyHosts = hosts.filter(h -> h.isHealthy())
selectedHost = strategy.select(healthyHosts)
```

If no healthy hosts available → **503 Service Unavailable**

### Performance Characteristics

| Strategy | Selection Time | Memory | Concurrency | Distribution |
|----------|---------------|--------|-------------|--------------|
| Round-Robin | O(1) | O(1) | Lock-free | Even |
| Random | O(1) | O(1) | Lock-free | Statistical |

---

## Caching

Implements RFC 7234 compliant HTTP caching with support for validation and revalidation.

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

**Example:**
```
GET /api/users
Vary: Accept-Language, Accept-Encoding

Key 1: {GET, /api/users, {Accept-Language: en, Accept-Encoding: gzip}}
Key 2: {GET, /api/users, {Accept-Language: it, Accept-Encoding: gzip}}
```

These create separate cache entries.

### Cached Response Structure

```java
CachedResponse {
  byte[] body                    // Response body
  HttpHeaders headers            // All response headers
  int statusCode                 // HTTP status
  
  Instant cachedAt              // When cached
  Duration maxAge               // Freshness lifetime
  
  String etag                   // For validation
  Instant lastModified          // For validation
  
  Set<String> varyHeaderNames   // Headers that vary
}
```

### Cache Decision Flow

```
Request → Is GET/HEAD?
          │
          ├─ NO → Don't cache
          │
          └─ YES → Check Cache-Control
                   │
                   ├─ no-store? → Don't cache
                   ├─ private? → Don't cache (shared cache)
                   ├─ public/max-age? → CACHE
                   └─ s-maxage? → CACHE (overrides max-age)
```

### Cache-Control Directives Support

| Directive | Supported | Behavior |
|-----------|-----------|----------|
| `public` | ✅ | Explicitly cacheable |
| `private` | ✅ | Not cached (shared cache) |
| `no-cache` | ✅ | Cache but revalidate |
| `no-store` | ✅ | Never cache |
| `max-age` | ✅ | Freshness lifetime |
| `s-maxage` | ✅ | Overrides max-age for shared cache |
| `must-revalidate` | ✅ | Force revalidation when stale |
| `proxy-revalidate` | ✅ | Force revalidation for proxy |

### Freshness Calculation

Following RFC 7234 Section 4.2:

```
freshness_lifetime = s-maxage || max-age || (expires - date) || heuristic

current_age = age_header + (now - date_header) + request_time + response_time

response_is_fresh = (freshness_lifetime > current_age)
```

**Example:**
```
Response:
  Date: Mon, 01 Jan 2024 12:00:00 GMT
  Cache-Control: max-age=300

At 12:02:00 (120 seconds later):
  current_age = 120
  freshness_lifetime = 300
  is_fresh = true (120 < 300)

At 12:06:00 (360 seconds later):
  current_age = 360
  freshness_lifetime = 300
  is_fresh = false (360 > 300) → STALE
```

### Conditional Revalidation

When cached entry is stale but has validators:

#### ETag-based Validation
```
1. Cached: ETag: "abc123"
2. Forward: If-None-Match: "abc123"
3. Response: 304 Not Modified
4. Action: Serve cached body, update timestamps
```

#### Last-Modified Validation
```
1. Cached: Last-Modified: Mon, 01 Jan 2024 12:00:00 GMT
2. Forward: If-Modified-Since: Mon, 01 Jan 2024 12:00:00 GMT
3. Response: 304 Not Modified
4. Action: Serve cached body, update timestamps
```

### Cache Headers Added by Proxy

| Header | When | Value |
|--------|------|-------|
| `X-Cache` | Always | `HIT`, `MISS`, `REVALIDATED` |
| `Age` | Cache hit | Seconds since cached |
| `X-Cache-Key` | Debug mode | Cache key used |

**Example Response:**
```
HTTP/1.1 200 OK
X-Cache: HIT
Age: 45
Content-Type: application/json
Cache-Control: public, max-age=300

{"data": "..."}
```

### Cache Management

#### Eviction Policy
- **LRU (Least Recently Used)**: Evicts oldest accessed entry when max size reached
- **Configurable max entries**: Default 1000

#### Cache Statistics (via Actuator)
```bash
curl http://localhost:8080/actuator/metrics/cache.size
curl http://localhost:8080/actuator/metrics/cache.gets
curl http://localhost:8080/actuator/metrics/cache.hits
curl http://localhost:8080/actuator/metrics/cache.misses
```

### Best Practices

#### For Backend Services
```yaml
# Good: Explicit caching
Cache-Control: public, max-age=3600
ETag: "version-123"

# Good: No caching for dynamic content
Cache-Control: no-store

# Good: Cache with revalidation
Cache-Control: public, max-age=300, must-revalidate
ETag: "hash-xyz"
```

#### For Clients
```bash
# Bypass cache
curl -H "Cache-Control: no-cache" http://proxy/resource

# Only serve if fresh
curl -H "Cache-Control: max-age=0" http://proxy/resource
```

---

## Health Checks

Automatic health monitoring of downstream services with configurable checks and failover.

### How It Works

```
@Scheduled Task (every N seconds)
  └─▶ For each service
       └─▶ For each host
            ├─▶ Send GET /health
            ├─▶ Timeout: 5s
            ├─▶ Expected: 200 OK
            │
            ├─▶ Success?
            │   └─▶ Mark HEALTHY
            │
            └─▶ Failure/Timeout?
                └─▶ Mark UNHEALTHY
```

### Configuration

```yaml
proxy:
  health:
    enabled: true                 # Enable health checks
    check-interval: 30s           # Check frequency
    timeout: 5s                   # Request timeout
    path: /health                 # Health endpoint
    initial-delay: 10s            # Wait before first check
    retry-count: 2                # Retries before unhealthy
    recovery-count: 2             # Successful checks to recover
```

### Health Endpoint Requirements

Downstream services must implement a health endpoint:

**Minimal Implementation:**
```java
@RestController
public class HealthController {
    @GetMapping("/health")
    public ResponseEntity<String> health() {
        return ResponseEntity.ok("OK");
    }
}
```

**Advanced Implementation:**
```java
@GetMapping("/health")
public ResponseEntity<HealthStatus> health() {
    boolean dbHealthy = checkDatabase();
    boolean cacheHealthy = checkCache();
    
    if (dbHealthy && cacheHealthy) {
        return ResponseEntity.ok(new HealthStatus("UP"));
    }
    return ResponseEntity.status(503)
        .body(new HealthStatus("DOWN"));
}
```

### Health States

| State | Description | Behavior |
|-------|-------------|----------|
| `HEALTHY` | Passing health checks | Receives traffic |
| `UNHEALTHY` | Failed health check | Excluded from rotation |
| `UNKNOWN` | Initial state | Treated as healthy initially |

### State Transitions

```
         Initial
            │
            ▼
        [UNKNOWN]
            │
            │ First check
            ▼
    ┌─────────────┐
    │   HEALTHY   │◄──────┐
    └─────────────┘       │
            │              │
            │ Fail         │ Success
            ▼              │
    ┌─────────────┐       │
    │  UNHEALTHY  │───────┘
    └─────────────┘
```

### Circuit Breaking

When all hosts are unhealthy:

```
Request → ServiceRegistry.getHealthyHosts()
          │
          └─▶ Empty list
              │
              └─▶ ProxyService
                  │
                  └─▶ Return 503 Service Unavailable
                      {
                        "error": "No healthy hosts available",
                        "service": "my-service"
                      }
```

### Monitoring Health Status

#### View Current Status
```bash
# Via actuator endpoint
curl http://localhost:8080/actuator/health

# Via custom endpoint (if implemented)
curl http://localhost:8080/proxy/services/status
```

#### Log Output
```
2024-01-31 12:00:00 INFO  HealthCheckService - Checking health for my-service
2024-01-31 12:00:00 INFO  HealthCheckService - Host 10.0.1.10:8080 is HEALTHY
2024-01-31 12:00:00 WARN  HealthCheckService - Host 10.0.1.11:8080 is UNHEALTHY: Connection timeout
2024-01-31 12:00:00 INFO  HealthCheckService - Service my-service: 1/2 hosts healthy
```

### Best Practices

1. **Set appropriate timeouts**: Too short = false negatives, too long = slow detection
2. **Use dedicated health endpoints**: Don't use business logic endpoints
3. **Implement retries**: Transient failures shouldn't immediately mark unhealthy
4. **Test failover**: Regularly verify behavior when hosts go down

---

## API / Behavior Reference

### Spring Boot Actuator Endpoints

Enable actuator in configuration:

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,metrics,info,prometheus
  endpoint:
    health:
      show-details: always
```

### Available Endpoints

| Endpoint | Purpose | Example |
|----------|---------|---------|
| `/actuator/health` | Overall health status | `curl http://localhost:8080/actuator/health` |
| `/actuator/metrics` | Available metrics | `curl http://localhost:8080/actuator/metrics` |
| `/actuator/metrics/{name}` | Specific metric | `curl http://localhost:8080/actuator/metrics/jvm.memory.used` |
| `/actuator/prometheus` | Prometheus format | `curl http://localhost:8080/actuator/prometheus` |
| `/actuator/info` | Application info | `curl http://localhost:8080/actuator/info` |

### Key Metrics to Monitor

#### Application Metrics
```bash
# Requests per second
http_server_requests_seconds_count

# Response times (p50, p95, p99)
http_server_requests_seconds{quantile="0.95"}

# Error rate
http_server_requests_seconds_count{status="5xx"}
```

#### Proxy-Specific Metrics
```bash
# Cache hit rate
proxy_cache_hits_total / (proxy_cache_hits_total + proxy_cache_misses_total)

# Healthy hosts per service
proxy_service_healthy_hosts{service="my-service"}

# Downstream request duration
proxy_downstream_request_seconds
```

#### JVM Metrics
```bash
# Memory usage
jvm.memory.used{area="heap"}
jvm.memory.max{area="heap"}

# Garbage collection
jvm.gc.pause_seconds_count
jvm.gc.pause_seconds_sum

# Thread count
jvm.threads.live
```

#### System Metrics
```bash
# CPU usage
system.cpu.usage

# File descriptors
process.files.open
process.files.max
```

### Prometheus Integration

Add to `pom.xml`:
```xml
<dependency>
    <groupId>io.micrometer</groupId>
    <artifactId>micrometer-registry-prometheus</artifactId>
</dependency>
```

Prometheus scrape config:
```yaml
scrape_configs:
  - job_name: 'reverse-proxy'
    metrics_path: '/actuator/prometheus'
    static_configs:
      - targets: ['localhost:8080']
```

### Grafana Dashboard

Example queries:

**Request Rate:**
```promql
rate(http_server_requests_seconds_count[5m])
```

**Average Response Time:**
```promql
rate(http_server_requests_seconds_sum[5m]) 
/ 
rate(http_server_requests_seconds_count[5m])
```

**Error Rate:**
```promql
sum(rate(http_server_requests_seconds_count{status=~"5.."}[5m])) 
/ 
sum(rate(http_server_requests_seconds_count[5m]))
```

**Cache Hit Ratio:**
```promql
proxy_cache_hits_total / (proxy_cache_hits_total + proxy_cache_misses_total)
```

### Logging

Structured logging for observability:

```yaml
logging:
  pattern:
    console: "%d{yyyy-MM-dd HH:mm:ss} - %msg%n"
  level:
    com.marco.reverseproxy: INFO
    
  # Request/response logging
  level:
    com.marco.reverseproxy.service.ProxyService: DEBUG
```

Example log output:
```
2024-01-31 12:00:00 INFO  ProxyController - Received request: GET /api/users Host=api.example.com
2024-01-31 12:00:00 DEBUG ProxyService - Resolved service: api-service
2024-01-31 12:00:00 DEBUG LoadBalancer - Selected host: 10.0.1.10:8080
2024-01-31 12:00:00 DEBUG CacheService - Cache MISS for key: GET:/api/users
2024-01-31 12:00:00 DEBUG ProxyService - Forwarding to: http://10.0.1.10:8080/api/users
2024-01-31 12:00:00 INFO  ProxyService - Request completed: 200 OK in 45ms
```


#### Forwarded by Proxy

| Header | Value | Purpose |
|--------|-------|---------|
| `X-Forwarded-For` | Client IP | Original client IP address |
| `X-Forwarded-Proto` | http/https | Original protocol |
| `X-Forwarded-Host` | Original host | Original Host header |
| `X-Forwarded-Port` | Port number | Original port |

#### Filtered (Hop-by-Hop)

These headers are NOT forwarded to downstream:
- `Connection`
- `Keep-Alive`
- `Proxy-Authenticate`
- `Proxy-Authorization`
- `TE`
- `Trailer`
- `Transfer-Encoding`
- `Upgrade`

### Response Headers

#### Added by Proxy

| Header | When | Example |
|--------|------|---------|
| `X-Cache` | Always | `HIT`, `MISS`, `REVALIDATED` |
| `Age` | Cache hit | `123` (seconds) |
| `Via` | Always | `1.1 reverse-proxy` |

### Error Responses

| Status | Condition | Response |
|--------|-----------|----------|
| `404 Not Found` | Unknown Host header | `{"error": "Service not found", "host": "unknown.example.com"}` |
| `502 Bad Gateway` | Downstream connection error | `{"error": "Bad Gateway", "service": "my-service"}` |
| `503 Service Unavailable` | No healthy hosts | `{"error": "No healthy hosts", "service": "my-service"}` |
| `504 Gateway Timeout` | Downstream timeout | `{"error": "Gateway Timeout", "service": "my-service"}` |

### HTTP Methods

| Method | Supported | Cached | Notes |
|--------|-----------|--------|-------|
| GET | ✅ | ✅ | Standard retrieval |
| POST | ✅ | ❌ | Not cacheable |
| PUT | ✅ | ❌ | Not cacheable |
| DELETE | ✅ | ❌ | Not cacheable |
| HEAD | ✅ | ✅ | Cacheable like GET |
| OPTIONS | ✅ | ❌ | CORS preflight |
| PATCH | ✅ | ❌ | Not cacheable |

---

## Testing

### Running Tests

#### All Tests
```bash
./mvnw test
```

#### Specific Test Class
```bash
./mvnw test -Dtest=ProxyServiceTest
```

#### With Coverage
```bash
./mvnw clean test jacoco:report
# View: target/site/jacoco/index.html
```

### Test Structure

```
src/test/java/com/marco/reverseproxy/
├── cache/
│   ├── CacheKeyTest.java
│   ├── CachedResponseTest.java
│   └── CacheServiceTest.java
├── controller/
│   └── ProxyControllerTest.java
├── loadbalancer/
│   ├── RoundRobinLoadBalancerTest.java
│   └── RandomLoadBalancerTest.java
├── service/
│   ├── ProxyServiceTest.java
│   ├── ServiceRegistryTest.java
│   └── HealthCheckServiceTest.java
└── util/
    └── HostUtilsTest.java
```

### Integration Testing

```bash
# Start mock servers
./start-mock-servers.sh

# Run proxy
./mvnw spring-boot:run &

# Run integration tests
./test-proxy.sh

# Cleanup
./stop-mock-servers.sh
pkill -f spring-boot:run
```

### Manual Testing Scenarios

#### 1. Basic Routing
```bash
curl -v -H "Host: my-service.example.com" \
     http://localhost:8080/api/test
```

Expected: 200 OK from downstream

#### 2. Load Balancing
```bash
# Multiple requests to see round-robin
for i in {1..5}; do
  curl -H "Host: my-service.example.com" \
       http://localhost:8080/api/test
done
```

Check logs for different hosts being selected

#### 3. Caching
```bash
# First request (cache miss)
curl -v -H "Host: my-service.example.com" \
     http://localhost:8080/api/cached
# Look for: X-Cache: MISS

# Second request (cache hit)
curl -v -H "Host: my-service.example.com" \
     http://localhost:8080/api/cached
# Look for: X-Cache: HIT, Age: N
```

#### 4. Revalidation
```bash
# Wait for cache to go stale, then:
curl -v -H "Host: my-service.example.com" \
     http://localhost:8080/api/cached
# Look for: X-Cache: REVALIDATED
```

#### 5. Health Check Failover
```bash
# Stop one backend instance
kill <pid>

# Wait for health check cycle
sleep 35

# Request should route to remaining healthy host
curl -H "Host: my-service.example.com" \
     http://localhost:8080/api/test
```

#### 6. Unknown Host
```bash
curl -v -H "Host: unknown.example.com" \
     http://localhost:8080/api/test
# Expected: 404 Not Found
```

---

## License

This project is licensed under the MIT License.

---

## Acknowledgments

- Built with [Spring Boot](https://spring.io/projects/spring-boot)
- Powered by [Project Reactor](https://projectreactor.io/)
- HTTP caching based on [RFC 7234](https://tools.ietf.org/html/rfc7234)

---

**Made with ❤️ by Marco**
