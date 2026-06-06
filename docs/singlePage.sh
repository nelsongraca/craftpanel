#!/bin/bash

echo "### mkdocs.yml" > all-docs.md
cat mkdocs.yml >> all-docs.md
echo >> all-docs.md

find src -name "*.md" | sort | while read f; do
  echo "### $f"
    cat "$f"
      echo
      done >> all-docs.md
