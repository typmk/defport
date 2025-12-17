# Performance Benchmarking Guide

**Status:** Phase 5+ - Performance Testing & Validation

This guide covers how to run, interpret, and integrate performance benchmarks for defport using Criterium.

---

## Table of Contents

- [Overview](#overview)
- [Quick Start](#quick-start)
- [Benchmark Suites](#benchmark-suites)
- [Running Benchmarks](#running-benchmarks)
- [Interpreting Results](#interpreting-results)
- [Regression Detection](#regression-detection)
- [CI Integration](#ci-integration)
- [Writing Custom Benchmarks](#writing-custom-benchmarks)
- [Best Practices](#best-practices)

---

## Overview

Defport uses **Criterium** for statistically rigorous performance benchmarking. Criterium provides:

- **JVM warmup** - Eliminates cold-start bias
- **Statistical analysis** - Mean, median, std dev, percentiles
- **GC measurement** - Tracks garbage collection overhead
- **Outlier detection** - Identifies and flags anomalies

### Benchmark Coverage

| Area | Benchmarks | Purpose |
|------|-----------|---------|
| **Batch Processing** | 15+ benchmarks | Compare concurrent strategies (sequential, pmap, futures, core.async) |
| **Latency** | 12+ benchmarks | Single-request end-to-end timing for all MCP operations |
| **Registry** | 10+ benchmarks | Port lookup, registration, listing performance at scale |

---

## Quick Start

### 1. Install Dependencies

Benchmarks use the `:benchmark` alias with Criterium:

```bash
# Verify setup
clojure -M:benchmark -e "(require 'criterium.core) (println \"✓ Criterium loaded\")"
```

### 2. Run Quick Benchmarks

```bash
# Unix/Mac
./bench.sh quick

# Windows
bench.bat quick

# Direct invocation
clojure -M:benchmark -m defport.benchmark.runner quick
```

**Output:** Quick benchmarks complete in ~2-5 minutes.

### 3. Interpret Results

```
Evaluation count : 120 in 60 samples of 2 calls.
             Execution time mean : 523.456 µs
    Execution time std-deviation : 12.345 µs
   Execution time lower quantile : 510.123 µs ( 2.5%)
   Execution time upper quantile : 545.678 µs (97.5%)
```

**Key metrics:**
- **Mean** - Average execution time
- **Std deviation** - Consistency (lower is better)
- **Quantiles** - Performance distribution (2.5% to 97.5%)

---

## Benchmark Suites

### 1. Quick Suite (`quick`)

**Purpose:** Fast smoke test for CI/rapid development

**Duration:** 2-5 minutes

**Coverage:**
- Basic batch processing (10 requests)
- Fast tool latency
- Small registry operations

```bash
./bench.sh quick
```

---

### 2. Batch Processing Suite (`batch`)

**Purpose:** Comprehensive concurrent batch testing

**Duration:** 10-15 minutes

**Coverage:**
- All strategies: sequential, pmap, futures, core.async
- Scalability tests: 10, 50, 100, 500, 1000 requests
- Concurrency tuning: 2, 5, 10, 20 workers
- MCP integration benchmarks

```bash
./bench.sh batch
```

**Example Benchmarks:**
- `compare-strategies` - Side-by-side strategy comparison
- `benchmark-scalability` - How performance scales with batch size
- `benchmark-concurrency-levels` - Optimal worker count

---

### 3. Latency Suite (`latency`)

**Purpose:** Single-request end-to-end timing

**Duration:** 5-10 minutes

**Coverage:**
- `initialize` - Handshake latency
- `tools/list` - Tool listing
- `tools/call` - Fast, medium, complex handlers
- `prompts/list`, `prompts/get`
- `resources/list`, `resources/read`
- JSON serialization overhead
- Request validation overhead

```bash
./bench.sh latency
```

---

### 4. Registry Suite (`registry`)

**Purpose:** Port registry performance at scale

**Duration:** 5-10 minutes

**Coverage:**
- Port lookup: 10, 100, 1000 ports
- Bulk registration: 10, 100, 1000 ports
- Listing performance
- Concurrent reads/writes
- Memory footprint analysis

```bash
./bench.sh registry
```

---

### 5. Comprehensive Suite (`all`)

**Purpose:** Full benchmark coverage for releases

**Duration:** 30-45 minutes

**Coverage:** All of the above

```bash
./bench.sh all
```

---

## Running Benchmarks

### Basic Usage

```bash
# Quick benchmarks
./bench.sh quick

# Specific suite
./bench.sh batch

# All benchmarks
./bench.sh all
```

### Advanced Options

```bash
# Save results as baseline
./bench.sh all --save-baseline

# Compare against baseline (for regression detection)
./bench.sh all --compare

# Save results to JSON file
./bench.sh batch --output results.json

# Verbose output
./bench.sh quick --verbose
```

### Direct Namespace Invocation

```bash
# Run specific benchmark namespace
clojure -M:benchmark -m defport.benchmark.batch-benchmark quick
clojure -M:benchmark -m defport.benchmark.latency-benchmark all
clojure -M:benchmark -m defport.benchmark.registry-benchmark comprehensive
```

### REPL Usage

```clojure
(require '[defport.benchmark.batch-benchmark :as batch])

;; Run individual benchmarks
(batch/benchmark-sequential-batch 10 inc)
(batch/benchmark-pmap-batch 10 inc)

;; Compare strategies
(batch/compare-strategies 10 inc "My handler")

;; Run full suite
(batch/run-quick-benchmarks)
```

---

## Interpreting Results

### Criterium Output Explained

```
Evaluation count : 120 in 60 samples of 2 calls.
             Execution time mean : 523.456 µs
    Execution time std-deviation : 12.345 µs
   Execution time lower quantile : 510.123 µs ( 2.5%)
   Execution time upper quantile : 545.678 µs (97.5%)
                   Overhead used : 1.234 ns

Found 2 outliers in 60 samples (3.3333%)
	low-severe	 1 (1.6667%)
	low-mild	 1 (1.6667%)
 Variance from outliers : 13.4567% Variance is moderately inflated by outliers
```

**Breakdown:**

1. **Evaluation count** - Total iterations (120 = 60 samples × 2 calls)
2. **Mean** - Average execution time (523.456 µs = 0.523 ms)
3. **Std deviation** - Variability (12.345 µs = ±2.4%)
4. **Quantiles** - 95% of executions fall between 510-545 µs
5. **Outliers** - 2 unusually slow iterations (3.3%)
6. **Variance** - Outliers moderately inflate variance

### What to Look For

**Good Benchmark:**
```
Execution time mean : 100 µs
Std-deviation : 2 µs (±2%)
Outliers : 0-2 (< 5%)
Variance : < 20%
```

**Problematic Benchmark:**
```
Execution time mean : 500 µs
Std-deviation : 150 µs (±30%)    ⚠️ High variance
Outliers : 15 (25%)               ⚠️ Many outliers
Variance : 75% severely inflated  ⚠️ Inconsistent
```

**Common causes of variance:**
- JVM not warmed up (Criterium handles this)
- GC pauses (check GC metrics)
- Background processes
- I/O contention
- Lock contention

---

## Regression Detection

### Establishing a Baseline

Run benchmarks and save as baseline:

```bash
./bench.sh all --save-baseline
```

Creates `benchmark-baseline.json` with:
- Timestamp
- System info (OS, JVM, processors)
- All benchmark results

### Detecting Regressions

Compare current run against baseline:

```bash
./bench.sh all --compare
```

**Output:**
```
═══════════════════════════════════════════════════════════════
REGRESSION ANALYSIS
═══════════════════════════════════════════════════════════════

Baseline timestamp: 2025-01-13T10:30:00Z
Current timestamp: 2025-01-14T15:45:00Z

⚠️  REGRESSIONS DETECTED:
  batch/pmap-batch-10: 150.23 ms → 185.45 ms (23.5% SLOWER)
  latency/tools-call: 1.23 ms → 1.52 ms (23.6% SLOWER)

✓ IMPROVEMENTS DETECTED:
  registry/port-lookup-large: 2.34 ms → 1.89 ms (19.2% FASTER)

❌ Benchmark regressions detected!
```

**Exit codes:**
- `0` - No significant regressions
- `1` - Regressions detected (fails CI)

**Thresholds:**
- Regression: > 10% slower
- Improvement: > 10% faster

---

## CI Integration

### GitHub Actions

```yaml
name: Performance Benchmarks

on:
  push:
    branches: [main]
  pull_request:
    branches: [main]

jobs:
  benchmark:
    runs-on: ubuntu-latest

    steps:
      - uses: actions/checkout@v3

      - name: Setup Clojure
        uses: DeLaGuardo/setup-clojure@master
        with:
          cli: latest

      - name: Cache dependencies
        uses: actions/cache@v3
        with:
          path: ~/.m2
          key: ${{ runner.os }}-m2-${{ hashFiles('deps.edn') }}

      - name: Download baseline
        run: |
          wget https://your-storage/benchmark-baseline.json || echo "No baseline"

      - name: Run quick benchmarks
        run: ./bench.sh quick

      - name: Run regression check (on main branch)
        if: github.ref == 'refs/heads/main'
        run: ./bench.sh all --compare

      - name: Update baseline (on main branch)
        if: github.ref == 'refs/heads/main'
        run: |
          ./bench.sh all --save-baseline
          # Upload to storage
          # aws s3 cp benchmark-baseline.json s3://your-bucket/

      - name: Upload results
        uses: actions/upload-artifact@v3
        with:
          name: benchmark-results
          path: benchmark-*.json
```

### GitLab CI

```yaml
benchmark:
  stage: test
  script:
    - ./bench.sh quick
  artifacts:
    paths:
      - benchmark-*.json
    expire_in: 30 days

benchmark-regression:
  stage: test
  only:
    - main
  script:
    - wget https://your-storage/benchmark-baseline.json || true
    - ./bench.sh all --compare
    - ./bench.sh all --save-baseline
  artifacts:
    paths:
      - benchmark-baseline.json
```

---

## Writing Custom Benchmarks

### Basic Structure

```clojure
(ns my-app.benchmark.custom
  (:require [criterium.core :as crit]
            [defport.core :as core]))

(defn benchmark-my-feature
  "Benchmark description."
  []
  (println "\n=== My Feature Benchmark ===" )
  (let [setup (do-setup)]
    (crit/with-progress-reporting
      (crit/bench
        (my-feature-fn setup)
        :verbose))))

(defn -main [& args]
  (benchmark-my-feature)
  (shutdown-agents))
```

### Quick Bench (Fast Iteration)

```clojure
;; Use quick-bench for faster results (less accurate)
(crit/quick-bench
  (my-fast-function))
```

### Bench vs Quick-Bench

| Feature | `bench` | `quick-bench` |
|---------|---------|---------------|
| Duration | ~60 seconds | ~6 seconds |
| Samples | 60+ | 6+ |
| Accuracy | High | Medium |
| Use case | Release benchmarks | Development |

### Best Practices

1. **Warm up JVM**
   ```clojure
   ;; Criterium handles this automatically
   (crit/bench (my-fn))
   ```

2. **Isolate benchmarks**
   ```clojure
   ;; Bad: Mixing setup and execution
   (crit/bench
     (let [setup (expensive-setup)]
       (my-fn setup)))

   ;; Good: Setup outside bench
   (let [setup (expensive-setup)]
     (crit/bench (my-fn setup)))
   ```

3. **Avoid side effects**
   ```clojure
   ;; Bad: I/O in benchmark
   (crit/bench (spit "file.txt" data))

   ;; Good: Pure computation
   (crit/bench (process-data data))
   ```

4. **Use realistic data**
   ```clojure
   ;; Bad: Trivial input
   (crit/bench (my-fn 1))

   ;; Good: Representative workload
   (let [realistic-data (generate-test-data 1000)]
     (crit/bench (my-fn realistic-data)))
   ```

---

## Best Practices

### 1. Run on Dedicated Hardware

**Avoid:**
- Running other applications
- Background downloads
- VM with variable CPU allocation

**Prefer:**
- Dedicated CI runners
- Consistent hardware across runs
- Server JVM (`-server` flag)

### 2. Statistical Significance

**Minimum differences to trust:**
- > 10% difference for regression alerts
- > 5% for optimization verification
- Multiple runs for consistency

### 3. Benchmark Maintenance

**Frequency:**
- Quick benchmarks: Every commit (CI)
- Full benchmarks: Weekly (scheduled)
- Baseline updates: On releases

**Retention:**
- Keep last 10 baselines
- Archive major release baselines
- Track trends over time

### 4. Interpreting Changes

**Performance improved:**
- Verify on multiple runs
- Check for correctness changes
- Document optimization

**Performance regressed:**
- Identify cause (profiling)
- Assess impact (critical path?)
- Fix or document trade-off

### 5. GC Considerations

Monitor GC overhead:
```
Overhead used : 1.234 ns
```

High GC overhead (> 10% of execution time) indicates:
- Excessive allocations
- Consider object pooling
- Review data structure choices

---

## Troubleshooting

### Issue: High Variance

**Symptoms:**
```
Variance from outliers : 75% severely inflated
```

**Solutions:**
1. Run on quieter system
2. Increase warmup iterations
3. Check for GC pauses
4. Verify consistent test data

### Issue: Slow Benchmarks

**Symptoms:** Benchmarks take hours

**Solutions:**
1. Use `quick-bench` for development
2. Run specific suites, not `all`
3. Reduce iteration counts (custom Criterium config)
4. Profile slow benchmarks

### Issue: Inconsistent Results

**Symptoms:** Results vary 20%+ between runs

**Solutions:**
1. Check system load
2. Verify JVM flags (`-server`)
3. Ensure consistent data sizes
4. Lock CPU frequency (on physical hardware)

---

## Reference

### Benchmark Files

```
test/defport/benchmark/
├── batch_benchmark.clj       # Batch processing benchmarks
├── latency_benchmark.clj     # Single-request latency
├── registry_benchmark.clj    # Registry operations
└── runner.clj                # Orchestration and CLI
```

### Scripts

- `bench.sh` - Unix/Mac runner
- `bench.bat` - Windows runner

### Configuration

- `:benchmark` alias in `deps.edn`
- JVM opts: `-Xmx2g -server`
- Criterium version: 0.4.6

---

## Further Reading

- **Criterium Documentation:** https://github.com/hugoduncan/criterium
- **JVM Performance Tuning:** https://docs.oracle.com/en/java/javase/11/gctuning/
- **[PERFORMANCE.md](PERFORMANCE.md)** - Batch processing optimization guide
- **[CONCURRENCY.md](CONCURRENCY.md)** - Thread safety and concurrency model

---

*Last updated: January 2025 - Phase 5+ Performance Testing*
