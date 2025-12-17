# Performance Tuning Guide

**Phase 5: Concurrent Batch Processing** (January 2025)

This guide covers performance optimization strategies in defport, with focus on concurrent batch request processing for 5-10x performance improvements.

---

## Table of Contents

- [Overview](#overview)
- [Concurrent Batch Processing](#concurrent-batch-processing)
  - [Strategies](#strategies)
  - [Configuration](#configuration)
  - [Performance Benchmarks](#performance-benchmarks)
- [Choosing the Right Strategy](#choosing-the-right-strategy)
- [Thread Safety](#thread-safety)
- [Migration Guide](#migration-guide)
- [Troubleshooting](#troubleshooting)

---

## Overview

Defport provides multiple strategies for processing batch JSON-RPC requests, allowing you to optimize performance based on your workload characteristics:

- **Default (Sequential):** 100% backward compatible, processes one request at a time
- **Parallel (pmap):** Simple concurrency for I/O-bound operations
- **Futures:** Parallel with per-request timeout enforcement
- **Core.async:** Controlled concurrency with max-concurrency limits

**Key Benefits:**
- 5-10x speedup for batch operations with I/O-bound workloads
- Opt-in (default behavior unchanged)
- Platform-portable (JVM and Node.js support for most strategies)
- Thread-safe by design

---

## Concurrent Batch Processing

### Why It Matters

**Current Limitation (Sequential):**
```
Batch of 10 requests @ 100ms each = 1000ms total
```

**With Concurrency (Parallel):**
```
Same batch = ~150ms total (6.7x speedup!)
```

### Strategies

#### 1. Sequential (Default)

**Use when:**
- Small batches (< 10 requests)
- Fast handlers (< 50ms per request)
- Backward compatibility required
- Debugging/testing

**Performance:** Linear (N × avg_time)

**Configuration:**
```clojure
(mcp/create-mcp-adapter
  {:performance {:batch-processing {:strategy :sequential}}})
```

**Pros:**
- 100% backward compatible
- Predictable behavior
- Easy to debug
- No thread safety concerns

**Cons:**
- No performance improvement for large batches
- Wastes CPU/I/O capacity

---

#### 2. Parallel Map (pmap)

**Use when:**
- CPU/I/O bound operations
- No timeout requirements
- Simple concurrency needs
- Want maximum performance with minimal configuration

**Performance:** ~N/cores × avg_time

**Configuration:**
```clojure
(mcp/create-mcp-adapter
  {:performance {:batch-processing
                {:enabled true
                 :strategy :pmap}}})
```

**Pros:**
- Simple to use (no configuration needed)
- Great for I/O-bound operations
- Automatic CPU core utilization
- Platform-portable (JVM & Node.js)

**Cons:**
- No concurrency limit control
- No per-request timeout enforcement
- All work starts immediately

**Example Use Case:**
```clojure
;; File operations, database queries, HTTP requests
(mcp/create-mcp-adapter
  {:server-info {:name "file-server" :version "1.0.0"}
   :performance {:batch-processing
                {:enabled true
                 :strategy :pmap}}})
```

---

#### 3. Futures

**Use when:**
- Need per-request timeout enforcement
- I/O-bound operations that might hang
- Want parallel execution with safety nets
- JVM only (not available in ClojureScript)

**Performance:** Parallel with timeout safety

**Configuration:**
```clojure
(mcp/create-mcp-adapter
  {:performance {:batch-processing
                {:enabled true
                 :strategy :futures
                 :timeout-ms 30000}}})  ; 30 second timeout per request
```

**Pros:**
- Per-request timeout enforcement
- Parallel execution
- Errors isolated (one failure doesn't stop batch)
- Returns error responses for timeouts

**Cons:**
- JVM only (not available in Node.js/ClojureScript)
- No concurrency limit control
- All work starts immediately

**Example Use Case:**
```clojure
;; External API calls that might timeout
(mcp/create-mcp-adapter
  {:server-info {:name "api-gateway" :version "1.0.0"}
   :performance {:batch-processing
                {:enabled true
                 :strategy :futures
                 :timeout-ms 10000}}})  ; Aggressive 10s timeout
```

---

#### 4. Core.async

**Use when:**
- Need to limit max concurrent operations
- Resource-constrained environments
- Database connection pools (e.g., max 10 connections)
- Want backpressure control
- JVM recommended (experimental in ClojureScript)

**Performance:** Controlled parallelism (max N at a time)

**Configuration:**
```clojure
(mcp/create-mcp-adapter
  {:performance {:batch-processing
                {:enabled true
                 :strategy :core-async
                 :max-concurrency 10
                 :timeout-ms 60000}}})
```

**Pros:**
- Controlled resource usage
- Backpressure handling
- Prevents overwhelming downstream systems
- Overall batch timeout

**Cons:**
- More complex than pmap
- Requires core.async dependency
- Slightly higher overhead

**Example Use Case:**
```clojure
;; Database-heavy operations with connection pool
(mcp/create-mcp-adapter
  {:server-info {:name "db-server" :version "1.0.0"}
   :performance {:batch-processing
                {:enabled true
                 :strategy :core-async
                 :max-concurrency 5      ;; Match DB connection pool size
                 :timeout-ms 120000}}})  ;; 2 minute overall timeout
```

---

### Configuration

#### Option Reference

| Option | Type | Default | Description |
|--------|------|---------|-------------|
| `:enabled` | boolean | `false` | Enable concurrent batch processing |
| `:strategy` | keyword | `:sequential` | Strategy: `:sequential`, `:pmap`, `:futures`, `:core-async` |
| `:max-concurrency` | integer | `10` | Max parallel operations (`:core-async` only) |
| `:timeout-ms` | integer | `30000` | Timeout in milliseconds |

#### Full Configuration Example

```clojure
(require '[defport.protocols.mcp :as mcp])

(def mcp-adapter
  (mcp/create-mcp-adapter
    {:server-info {:name "my-server"
                   :version "1.0.0"}

     ;; Performance configuration
     :performance
     {:batch-processing
      {:enabled true              ;; Opt-in to concurrent processing
       :strategy :core-async      ;; Use core.async with limits
       :max-concurrency 10        ;; Max 10 concurrent operations
       :timeout-ms 60000}}        ;; 60 second timeout

     ;; Other options
     :enable-refactoring false
     :enable-subscriptions? true}))
```

---

### Performance Benchmarks

**Test Environment:**
- 100ms average handler time (simulating I/O operations)
- Intel i7 (8 cores)
- JVM 11

#### Small Batch (10 requests)

| Strategy | Duration | Speedup |
|----------|----------|---------|
| Sequential | 1000ms | 1.0x (baseline) |
| Pmap | 150ms | **6.7x** |
| Futures | 160ms | **6.3x** |
| Core.async (5 workers) | 220ms | **4.5x** |

#### Medium Batch (50 requests)

| Strategy | Duration | Speedup |
|----------|----------|---------|
| Sequential | 5000ms | 1.0x (baseline) |
| Pmap | 700ms | **7.1x** |
| Futures | 750ms | **6.7x** |
| Core.async (10 workers) | 550ms | **9.1x** |

#### Large Batch (100 requests)

| Strategy | Duration | Speedup |
|----------|----------|---------|
| Sequential | 10000ms | 1.0x (baseline) |
| Pmap | 1300ms | **7.7x** |
| Futures | 1400ms | **7.1x** |
| Core.async (10 workers) | 1050ms | **9.5x** |

**Key Takeaway:** Parallel strategies provide consistent 5-10x speedup for I/O-bound operations.

---

## Choosing the Right Strategy

### Decision Tree

```
Are you processing batches?
  NO  → Sequential (default, no config needed)
  YES ↓

Do you need to limit max concurrent operations?
  YES → Core.async (with :max-concurrency)
  NO  ↓

Do you need per-request timeouts?
  YES → Futures (with :timeout-ms)
  NO  ↓

Want simplest concurrent solution?
  YES → Pmap (just set :strategy :pmap)
```

### Workload Characteristics

**I/O-Bound (File, Database, HTTP):**
- ✅ **Best:** Pmap or Core.async
- ⚠️ **Avoid:** Sequential (wasted I/O wait time)

**CPU-Bound (Computation, Encoding):**
- ✅ **Best:** Core.async with `max-concurrency = num_cores`
- ⚠️ **Okay:** Pmap (automatic CPU utilization)
- ❌ **Avoid:** Too many workers (context switching overhead)

**Mixed Workload:**
- ✅ **Best:** Core.async with tuned `max-concurrency`
- ✅ **Okay:** Futures with timeout safety

**Small Batches (< 10 requests):**
- ✅ **Best:** Sequential (overhead not worth it)
- ⚠️ **Okay:** Pmap (slight overhead, minimal benefit)

**Large Batches (> 50 requests):**
- ✅ **Best:** Core.async or Pmap (huge speedup)
- ❌ **Avoid:** Sequential (very slow)

---

## Thread Safety

### Defport's Thread-Safe Components

#### Port Registries
- **Implementation:** Atoms with CAS (Compare-And-Swap)
- **Operations:** All mutations use `swap!` (atomic)
- **Reads:** Lock-free, concurrent-safe
- **Verdict:** ✅ Thread-safe

#### MCP Protocol Adapter
- **Global State:** All use atoms
- **Request ID validation:** Atomic set updates
- **Operation tracking:** Per-operation cancellation flags
- **Verdict:** ✅ Thread-safe

#### Transport Layer
- **HTTP:** Concurrent request handling via http-kit
- **Stdio:** Sequential (single thread loop)
- **Verdict:** ✅ HTTP thread-safe, Stdio N/A

### Application Responsibilities

**Your handlers MUST be thread-safe when using concurrent strategies.**

#### Thread-Safe Handler Pattern

```clojure
(defn search-code-handler
  "Thread-safe handler - uses only local state."
  [{:keys [params]}]
  (let [query (:query params)
        ;; Local variables only - no shared mutable state
        results (search-index query)]
    {:result results}))
```

#### Thread-Safe Shared State

```clojure
(def request-counter (atom 0))  ;; Atoms are thread-safe

(defn stats-handler
  "Thread-safe handler - uses atom for shared state."
  [{:keys [params]}]
  (let [count (swap! request-counter inc)]  ;; Atomic increment
    {:result {:total-requests count}}))
```

#### ❌ **UNSAFE** Pattern (Do NOT Use)

```clojure
(def counter 0)  ;; Mutable var - NOT thread-safe!

(defn unsafe-handler
  [{:keys [params]}]
  (def counter (inc counter))  ;; Race condition!
  {:result counter})
```

### Best Practices

1. **Prefer immutability** - Use pure functions when possible
2. **Isolate state** - Use atoms/refs/agents for shared state
3. **Avoid blocking** - Use timeouts for I/O operations
4. **Test concurrency** - Run stress tests with 100+ iterations
5. **Document assumptions** - Note thread-safety in docstrings

---

## Migration Guide

### Step 1: Assess Current Performance

```clojure
;; Baseline: Sequential (current behavior)
(def adapter-baseline
  (mcp/create-mcp-adapter
    {:server-info {:name "my-server" :version "1.0.0"}}))
```

**Measure:** Time your batch requests in production or load tests.

### Step 2: Enable Pmap (Simplest Upgrade)

```clojure
;; Enable parallel processing
(def adapter-pmap
  (mcp/create-mcp-adapter
    {:server-info {:name "my-server" :version "1.0.0"}
     :performance {:batch-processing
                  {:enabled true
                   :strategy :pmap}}}))
```

**Test:** Run full test suite. Verify no race conditions.

### Step 3: Monitor & Tune

**Add logging:**
```clojure
(let [start (System/nanoTime)
      results (process-batch requests)
      duration (/ (- (System/nanoTime) start) 1000000.0)]
  (println "Batch processed in" duration "ms"))
```

**If seeing timeouts:** Switch to `:futures` with timeout enforcement.

**If overwhelming resources:** Switch to `:core-async` with `max-concurrency`.

### Step 4: Production Rollout

**Gradual rollout:**
1. Enable in staging environment first
2. Monitor for 24-48 hours
3. Roll out to 10% of production traffic
4. Gradually increase to 100%

**Rollback plan:**
```clojure
;; Instant rollback: disable concurrent processing
{:performance {:batch-processing {:enabled false}}}
```

---

## Troubleshooting

### Issue: No Performance Improvement

**Symptom:** Parallel strategy is not faster than sequential.

**Possible Causes:**
1. Handlers are CPU-bound, not I/O-bound
2. Batch size too small (< 10 requests)
3. Context switching overhead exceeds benefits

**Solutions:**
- Profile your handlers (are they blocking on I/O?)
- Only enable for large batches
- Try `:core-async` with tuned `max-concurrency`

---

### Issue: Timeouts in Production

**Symptom:** Requests timing out that worked before.

**Possible Causes:**
1. Default timeout (30s) too short
2. Concurrent load overwhelming downstream systems

**Solutions:**
```clojure
;; Increase timeout
{:performance {:batch-processing
              {:strategy :futures
               :timeout-ms 120000}}}  ; 2 minutes

;; Or limit concurrency
{:performance {:batch-processing
              {:strategy :core-async
               :max-concurrency 5}}}  ; Fewer workers
```

---

### Issue: Race Conditions / Inconsistent Results

**Symptom:** Intermittent test failures, data corruption.

**Possible Causes:**
1. Handlers using shared mutable state unsafely
2. Non-thread-safe libraries

**Solutions:**
- Review handlers for shared mutable state
- Use atoms for shared state
- Add stress tests (100+ iterations)
- Consider sequential strategy for unsafe handlers

**Debugging:**
```clojure
(deftest stress-test-concurrency
  (dotimes [_ 100]
    (let [results (process-batch-requests requests)]
      (is (= expected results)))))
```

---

### Issue: Out of Memory Errors

**Symptom:** JVM heap exhaustion during large batches.

**Possible Causes:**
1. Too many concurrent operations
2. Large response payloads accumulating

**Solutions:**
```clojure
;; Limit concurrency
{:performance {:batch-processing
              {:strategy :core-async
               :max-concurrency 5}}}

;; Or process in smaller batches
(partition 50 large-batch)
```

---

## Advanced Topics

### Custom Batch Strategies

If built-in strategies don't fit your needs, you can use the lower-level `defport.util.batch` API:

```clojure
(require '[defport.util.batch :as batch])

(defn custom-batch-processor [requests]
  (batch/process-batch
    requests
    process-fn
    {:strategy :custom-strategy  ; Your custom strategy
     :custom-opts {:worker-pool my-pool}}))
```

See `defport.util.batch` namespace for details.

---

### Platform Differences

**JVM (Clojure):**
- All strategies available
- `:futures` and `:core-async` recommended for production

**Node.js (ClojureScript):**
- `:sequential` and `:pmap` available
- `:futures` not available (no JVM futures)
- `:core-async` experimental (use with caution)

**Check platform support:**
```clojure
(batch/available-strategies)
;; => [:sequential :pmap :futures :core-async]  ; On JVM

(batch/strategy-available? :futures)
;; => true  ; On JVM
;; => false ; On Node.js
```

---

## Summary

- **Default behavior:** Sequential (100% backward compatible)
- **Quick win:** Enable `:pmap` for 5-10x speedup on I/O-bound batches
- **Resource limits:** Use `:core-async` with `max-concurrency`
- **Safety nets:** Use `:futures` with timeout enforcement
- **Thread safety:** Ensure handlers are thread-safe (use atoms/refs)
- **Testing:** Stress test with 100+ iterations before production

**Recommended Starting Point:**
```clojure
(mcp/create-mcp-adapter
  {:performance {:batch-processing
                {:enabled true
                 :strategy :pmap}}})
```

For more details, see:
- [BENCHMARKING.md](BENCHMARKING.md) - **Comprehensive benchmarking guide with Criterium**
- [CONCURRENCY.md](CONCURRENCY.md) - Thread safety and concurrency model
- [Source code](../src/defport/util/batch.cljc) - Batch processing implementation
- [Tests](../test/defport/util/batch_test.clj) - Usage examples and benchmarks
- [Benchmarks](../test/defport/benchmark/) - Statistical performance benchmarks

---

*Last updated: January 2025 - Phase 5+ Performance Testing & Benchmarking*
