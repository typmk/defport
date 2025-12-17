#!/bin/bash
# Defport Performance Benchmark Runner
#
# Usage: ./bench.sh [suite] [options]
#
# This script provides a convenient wrapper around the benchmark suite.

set -e

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# Default values
SUITE="${1:-quick}"
shift || true
OPTIONS="$@"

# Banner
echo ""
echo "╔═══════════════════════════════════════════════════════════════╗"
echo "║            DEFPORT PERFORMANCE BENCHMARK SUITE                ║"
echo "╚═══════════════════════════════════════════════════════════════╝"
echo ""

# Check for Clojure
if ! command -v clojure &> /dev/null; then
    echo -e "${RED}Error: Clojure CLI not found${NC}"
    echo "Please install Clojure: https://clojure.org/guides/install_clojure"
    exit 1
fi

# Run benchmarks
echo -e "${YELLOW}Running benchmark suite: ${SUITE}${NC}"
echo ""

clojure -M:benchmark -m defport.benchmark.runner "${SUITE}" ${OPTIONS}

EXIT_CODE=$?

if [ $EXIT_CODE -eq 0 ]; then
    echo ""
    echo -e "${GREEN}✓ Benchmarks completed successfully${NC}"
else
    echo ""
    echo -e "${RED}❌ Benchmarks failed or regressions detected${NC}"
fi

exit $EXIT_CODE
