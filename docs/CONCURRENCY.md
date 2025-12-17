# Concurrency Model and Thread Safety

**Phase 5: Concurrent Batch Processing** (January 2025)

This document explains defport's concurrency model, thread safety guarantees, and best practices for writing thread-safe handlers.

---

## Table of Contents

- [Overview](#overview)
- [Architecture](#architecture)
- [Thread-Safe Components](#thread-safe-components)
- [Application Responsibilities](#application-responsibilities)
- [Batch Processing Model](#batch-processing-model)
- [Best Practices](#best-practices)
- [Common Pitfalls](#common-pitfalls)
- [Testing Concurrency](#testing-concurrency)

---

## Overview

Defport supports concurrent batch processing while maintaining thread safety through careful design:

- **Defport-managed state:** Thread-safe by default (atoms, immutable data)
- **Application handlers:** Responsibility of application developer
- **Opt-in concurrency:** Sequential by default, concurrent when enabled

**Key Principle:** Defport provides the **mechanism** (safe concurrent execution), applications provide the **policy** (thread-safe handlers).

---

## Architecture

### Concurrency Layers

```
┌─────────────────────────────────────────┐
│ JSON-RPC Batch Request                  │
│ [{...}, {...}, {...}]                   │
└─────────────────┬───────────────────────┘
                  │
                  ▼
┌─────────────────────────────────────────┐
│ Batch Dispatcher                        │
│ (Strategy: Sequential, Pmap, etc.)      │
└─────────────────┬───────────────────────┘
                  │
       ┌──────────┼──────────┐
       │          │          │
       ▼          ▼          ▼
   ┌─────┐   ┌─────┐   ┌─────┐
   │ Req1│   │ Req2│   │ Req3│  (Parallel)
   └──┬──┘   └──┬──┘   └──┬──┘
      │         │         │
      ▼         ▼         ▼
┌─────────────────────────────────────────┐
│ MCP Adapter (Thread-safe)               │
│ - Request validation (atom-based)       │
│ - Operation tracking (atom-based)       │
└─────────────────┬───────────────────────┘
                  │
                  ▼
┌─────────────────────────────────────────┐
│ Port Registry (Thread-safe)             │
│ - Concurrent reads (lock-free)          │
│ - Atomic updates (CAS)                  │
└─────────────────┬───────────────────────┘
                  │
       ┌──────────┼──────────┐
       │          │          │
       ▼          ▼          ▼
┌──────────┐ ┌──────────┐ ┌──────────┐
│ Handler1 │ │ Handler2 │ │ Handler3 │
│ (YOUR    │ │ (YOUR    │ │ (YOUR    │
│  CODE)   │ │  CODE)   │ │  CODE)   │
└──────────┘ └──────────┘ └──────────┘
    ⚠️           ⚠️           ⚠️
  Must be    Must be    Must be
thread-safe thread-safe thread-safe
```

---

## Thread-Safe Components

### 1. Port Registries

**Implementation:**
```clojure
(defonce registry-state* (atom {}))  ; Atom = thread-safe

(defn register-port! [registry port]
  (swap! registry-state* assoc (:id port) port))  ; CAS = atomic
```

**Guarantees:**
- ✅ All mutations use `swap!` (Compare-And-Swap)
- ✅ Reads are lock-free
- ✅ Concurrent reads and writes are safe
- ✅ No data corruption possible

**Operations:**
- `register-port!` - Atomic insertion
- `get-port` - Lock-free read
- `list-ports` - Snapshot of current state (immutable)

---

### 2. MCP Protocol Adapter

**State Management:**
```clojure
(defonce seen-request-ids* (atom #{}))          ; Thread-safe set
(defonce active-operations* (atom {}))          ; Thread-safe map
(defonce resource-subscriptions* (atom {}))     ; Thread-safe map
```

**Guarantees:**
- ✅ Request ID validation uses atomic set operations
- ✅ Operation cancellation flags use atoms
- ✅ Subscription management uses atomic updates
- ✅ No global mutable vars

**Thread Safety:**
```clojure
(defn validate-request-id [request-id]
  (if (contains? @seen-request-ids* request-id)
    false  ; Duplicate
    (do
      (swap! seen-request-ids* conj request-id)  ; Atomic!
      true)))
```

---

### 3. Transport Layer

**HTTP (http-kit):**
- ✅ Thread-safe by design
- ✅ Concurrent request handling built-in
- ✅ Multiple threads can call transport simultaneously

**Stdio:**
- ✅ Sequential (single thread loop)
- ✅ No concurrency issues (one request at a time)

---

## Application Responsibilities

### Handler Thread Safety

**When using concurrent batch strategies (`:pmap`, `:futures`, `:core-async`), your handlers MAY be called concurrently from multiple threads.**

#### ✅ Thread-Safe Handler (Pure Function)

```clojure
(defn search-code-handler
  "Thread-safe: no shared mutable state."
  [{:keys [params]}]
  (let [query (:query params)
        ;; Local variables only - thread-local
        results (search-index query)]
    {:result results}))
```

**Why safe:**
- No shared mutable state
- All variables local to function
- Pure function (same input → same output)

---

#### ✅ Thread-Safe Handler (Atom for Shared State)

```clojure
(def request-counter (atom 0))  ; Atoms are thread-safe!

(defn stats-handler
  "Thread-safe: uses atom for shared state."
  [{:keys [params]}]
  (let [count (swap! request-counter inc)]  ; Atomic increment
    {:result {:total-requests count
              :timestamp (System/currentTimeMillis)}}))
```

**Why safe:**
- Atoms provide atomic updates via CAS
- `swap!` ensures no race conditions
- Reading atom value is also safe

---

#### ✅ Thread-Safe Handler (Ref for Coordinated Updates)

```clojure
(def user-sessions (ref {}))

(defn login-handler
  "Thread-safe: uses ref with dosync for coordinated updates."
  [{:keys [params]}]
  (dosync
    (let [user-id (:user-id params)
          session-id (generate-session-id)]
      (alter user-sessions assoc user-id session-id)
      {:result {:session-id session-id}})))
```

**Why safe:**
- Refs provide coordinated, synchronous updates
- `dosync` ensures transactional semantics

---

#### ❌ **UNSAFE** Handler (Mutable Var)

```clojure
(def counter 0)  ; ⚠️ Mutable var - NOT thread-safe!

(defn unsafe-handler
  [{:keys [params]}]
  (def counter (inc counter))  ; ❌ Race condition!
  {:result counter})
```

**Why unsafe:**
- Multiple threads reading and writing `counter` simultaneously
- Race condition: lost updates possible
- Example: Thread 1 reads 0, Thread 2 reads 0, both write 1 (should be 2!)

**Fix:**
```clojure
(def counter (atom 0))  ; ✅ Use atom instead

(defn safe-handler
  [{:keys [params]}]
  (let [new-count (swap! counter inc)]  ; ✅ Atomic!
    {:result new-count}))
```

---

#### ❌ **UNSAFE** Handler (Non-Thread-Safe Library)

```clojure
(def connection (create-non-thread-safe-db-connection))

(defn query-handler
  [{:keys [params]}]
  (let [result (.query connection (:sql params))]  ; ❌ Unsafe!
    {:result result}))
```

**Why unsafe:**
- Some libraries (JDBC, HTTP clients) are not thread-safe
- Concurrent calls may corrupt internal state

**Fix: Use connection pool:**
```clojure
(def connection-pool (create-connection-pool {:max-connections 10}))

(defn query-handler
  [{:keys [params]}]
  (with-open [conn (borrow-connection connection-pool)]
    (let [result (.query conn (:sql params))]
      {:result result})))
```

---

## Batch Processing Model

### Sequential Strategy (Default)

```
Request 1 → Handler → Result 1
Request 2 → Handler → Result 2
Request 3 → Handler → Result 3

Timeline: [========][========][========]
          100ms     100ms     100ms
Total: 300ms
```

**Thread Safety:** Not an issue (one at a time)

---

### Parallel Strategy (pmap, futures, core.async)

```
Request 1 ──┐
Request 2 ──┼─→ Parallel Execution ──> Results [1,2,3]
Request 3 ──┘

Timeline: [========================]
          100ms (all concurrent)
Total: ~100ms
```

**Thread Safety:** ⚠️ Handlers MUST be thread-safe!

---

## Best Practices

### 1. Prefer Immutability

```clojure
;; ✅ Good: Pure function
(defn calculate-stats [data]
  (let [total (reduce + data)
        avg (/ total (count data))]
    {:total total :average avg}))

;; ❌ Bad: Mutable accumulator
(defn calculate-stats-bad [data]
  (def total 0)  ; Mutable!
  (doseq [x data]
    (def total (+ total x)))  ; Race condition!
  {:total total})
```

---

### 2. Isolate State with Atoms

```clojure
;; ✅ Good: Atom for shared state
(def cache (atom {}))

(defn get-cached [key]
  (if-let [value (get @cache key)]
    value
    (let [computed (expensive-computation key)]
      (swap! cache assoc key computed)
      computed)))
```

---

### 3. Avoid Blocking Operations

```clojure
;; ❌ Bad: Blocking I/O without timeout
(defn fetch-url-bad [url]
  (slurp url))  ; May hang forever!

;; ✅ Good: With timeout
(defn fetch-url-good [url]
  (with-timeout 5000  ; 5 second timeout
    (slurp url)))
```

---

### 4. Document Thread Safety

```clojure
(defn process-order
  "Process customer order. Thread-safe: uses atoms for inventory updates.

  CONCURRENT: This handler is safe to call from multiple threads."
  [{:keys [params]}]
  (let [order-id (:order-id params)
        quantity (:quantity params)]
    (swap! inventory-atom update order-id - quantity)
    {:result {:order-id order-id :status "processed"}}))
```

---

### 5. Use Connection Pools

```clojure
;; Database connection pool
(def db-pool (jdbc/make-datasource
               {:maximum-pool-size 10}))

(defn db-query-handler
  "Thread-safe: uses connection pool."
  [{:keys [params]}]
  (jdbc/with-transaction [conn db-pool]
    (let [results (jdbc/execute! conn (:sql params))]
      {:result results})))
```

---

## Common Pitfalls

### Pitfall 1: Hidden Mutable State

```clojure
;; ❌ Problem: Mutable Java object
(def date-formatter (java.text.SimpleDateFormat. "yyyy-MM-dd"))

(defn format-date-bad [date]
  (.format date-formatter date))  ; SimpleDateFormat NOT thread-safe!

;; ✅ Solution: Thread-local formatter
(def ^:dynamic *date-formatter*)

(defn format-date-good [date]
  (binding [*date-formatter* (java.text.SimpleDateFormat. "yyyy-MM-dd")]
    (.format *date-formatter* date)))
```

---

### Pitfall 2: Lazy Sequences

```clojure
;; ❌ Problem: Lazy seq shared across threads
(def numbers (range 1000000))  ; Lazy!

(defn process-numbers-bad []
  (map expensive-fn numbers))  ; Not thread-safe!

;; ✅ Solution: Realize sequence
(def numbers (vec (range 1000000)))  ; Realized!

(defn process-numbers-good []
  (map expensive-fn numbers))  ; Thread-safe!
```

---

### Pitfall 3: Resource Leaks

```clojure
;; ❌ Problem: Resources not cleaned up on error
(defn read-file-bad [path]
  (let [reader (io/reader path)]
    (slurp reader)))  ; Reader never closed on error!

;; ✅ Solution: with-open ensures cleanup
(defn read-file-good [path]
  (with-open [reader (io/reader path)]
    (slurp reader)))  ; Always closed!
```

---

## Testing Concurrency

### Stress Testing

```clojure
(deftest test-handler-thread-safety
  (testing "Handler is thread-safe under concurrent load"
    ;; Run 100 iterations to expose race conditions
    (dotimes [_ 100]
      (let [requests (vec (repeat 50 {:params {:value 1}}))
            results (process-batch-requests requests)]

        ;; All requests succeed
        (is (= 50 (count results)))

        ;; Results are consistent
        (is (every? #(= 2 (:value %)) results))))))
```

---

### Race Condition Detection

```clojure
(deftest test-no-race-conditions
  (testing "Concurrent updates don't lose data"
    (let [counter (atom 0)
          handler (fn [_]
                   (swap! counter inc)
                   {:result "ok"})

          requests (vec (repeat 1000 {:params {}}))
          _ (process-batch-requests requests handler)]

      ;; Counter should be exactly 1000 (no lost updates)
      (is (= 1000 @counter)))))
```

---

### Property-Based Testing

```clojure
(require '[clojure.test.check :as tc]
         '[clojure.test.check.generators :as gen]
         '[clojure.test.check.properties :as prop])

(def concurrent-batch-property
  (prop/for-all [requests (gen/vector gen/int 1 100)]
    (let [results (process-batch-requests requests)]
      (= (count requests) (count results)))))

(tc/quick-check 100 concurrent-batch-property)
```

---

## Summary

### Thread-Safe by Default (Defport)
- ✅ Port registries (atoms)
- ✅ MCP adapter state (atoms)
- ✅ Transport layer (http-kit, stdio)

### Your Responsibility (Application)
- ⚠️ Handler thread safety
- ⚠️ Shared state management
- ⚠️ Resource cleanup
- ⚠️ Third-party library safety

### Quick Checklist
- [ ] No mutable vars (`def`, `defonce` with mutation)
- [ ] Shared state uses atoms/refs/agents
- [ ] No non-thread-safe Java objects (SimpleDateFormat, etc.)
- [ ] Connection pools for databases
- [ ] Timeouts for I/O operations
- [ ] Stress tests with 100+ iterations

**When in doubt:** Use sequential strategy or audit handlers carefully before enabling concurrency.

---

For more details, see:
- [PERFORMANCE.md](PERFORMANCE.md) - Performance tuning guide
- [Source code](../src/defport/util/batch.cljc) - Batch processing implementation
- [Tests](../test/defport/util/batch_test.clj) - Concurrency test examples

---

*Last updated: January 2025 - Phase 5 Performance Optimization*
