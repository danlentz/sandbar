#!/bin/bash

# Project Metrics Script
# Provides comprehensive statistics for the sandbag codebase

set -e

echo "================================================================================"
echo "                         SANDBAG PROJECT METRICS"
echo "================================================================================"
echo ""

# Source Code (Clojure)
echo "SOURCE CODE (src/)"
echo "--------------------------------------------------------------------------------"
find src -name "*.clj" -type f | xargs wc -l 2>/dev/null | tail -1 | awk '{print "  Lines:      " $1}'
find src -name "*.clj" -type f | wc -l | awk '{print "  Files:      " $1}'
find src -name "*.clj" -type f -exec grep -c "^[[:space:]]*;" {} + 2>/dev/null | awk -F: '{sum+=$2} END {print "  Comments:   " sum}'
echo ""

# Test Code
echo "TEST CODE (test/)"
echo "--------------------------------------------------------------------------------"
find test -name "*.clj" -type f | xargs wc -l 2>/dev/null | tail -1 | awk '{print "  Lines:      " $1}'
find test -name "*.clj" -type f | wc -l | awk '{print "  Files:      " $1}'
echo ""

# Schema Files (EDN)
echo "SCHEMA (schema/)"
echo "--------------------------------------------------------------------------------"
find schema -name "*.edn" -type f | xargs wc -l 2>/dev/null | tail -1 | awk '{print "  Lines:      " $1}'
find schema -name "*.edn" -type f | wc -l | awk '{print "  Files:      " $1}'
echo ""

# Config Files
echo "CONFIG (config/)"
echo "--------------------------------------------------------------------------------"
find config -name "*.edn" -type f | xargs wc -l 2>/dev/null | tail -1 | awk '{print "  Lines:      " $1}'
find config -name "*.edn" -type f | wc -l | awk '{print "  Files:      " $1}'
echo ""

# Resources
echo "RESOURCES (resources/)"
echo "--------------------------------------------------------------------------------"
if [ -d "resources" ]; then
  find resources -type f | wc -l | awk '{print "  Files:      " $1}'
else
  echo "  (none)"
fi
echo ""

# Project Files
echo "PROJECT FILES"
echo "--------------------------------------------------------------------------------"
wc -l project.clj 2>/dev/null | awk '{print "  project.clj:" $1 " lines"}'
if [ -f "CLAUDE.md" ]; then
  wc -l CLAUDE.md | awk '{print "  CLAUDE.md:  " $1 " lines"}'
fi
echo ""

# Totals
echo "================================================================================"
echo "TOTALS"
echo "================================================================================"
total_clj=$(find src test -name "*.clj" -type f | xargs cat 2>/dev/null | wc -l)
total_edn=$(find schema config -name "*.edn" -type f | xargs cat 2>/dev/null | wc -l)
total_files=$(find src test schema config -type f \( -name "*.clj" -o -name "*.edn" \) | wc -l)
echo "  Clojure:    $total_clj lines"
echo "  EDN:        $total_edn lines"
echo "  All Code:   $((total_clj + total_edn)) lines"
echo "  Files:      $total_files"
echo ""

# Namespace breakdown
echo "================================================================================"
echo "NAMESPACES BY DIRECTORY"
echo "================================================================================"
for dir in src/sandbag/*/; do
  if [ -d "$dir" ]; then
    name=$(basename "$dir")
    count=$(find "$dir" -name "*.clj" -type f | xargs cat 2>/dev/null | wc -l)
    files=$(find "$dir" -name "*.clj" -type f | wc -l)
    printf "  %-20s %5d lines  (%d files)\n" "$name/" "$count" "$files"
  fi
done
# Top-level src files
top_count=$(find src/sandbag -maxdepth 1 -name "*.clj" -type f | xargs cat 2>/dev/null | wc -l)
top_files=$(find src/sandbag -maxdepth 1 -name "*.clj" -type f | wc -l)
if [ "$top_files" -gt 0 ]; then
  printf "  %-20s %5d lines  (%d files)\n" "(top-level)" "$top_count" "$top_files"
fi
echo ""

# Git stats (if available)
if [ -d ".git" ]; then
  echo "================================================================================"
  echo "GIT STATISTICS"
  echo "================================================================================"
  echo "  Branch:     $(git branch --show-current)"
  echo "  Commits:    $(git rev-list --count HEAD)"
  echo "  Last:       $(git log -1 --format='%h %s' 2>/dev/null)"
  echo "  Modified:   $(git status --porcelain | wc -l | tr -d ' ') files"
  echo ""
fi

echo "Generated: $(date)"
