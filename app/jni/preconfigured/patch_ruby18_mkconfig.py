#!/usr/bin/env python3
"""Keep Ruby 1.8's host-side mkconfig helper from shadowing host rbconfig."""

from pathlib import Path
import sys


path = Path(sys.argv[1])
source = path.read_text(encoding="utf-8")
old = '$:.unshift(".")'
new = '$:.unshift(".") unless defined?(CROSS_COMPILING)'

if new not in source:
    if old not in source:
        raise SystemExit(f"Expected load-path line not found in {path}")
    source = source.replace(old, new, 1)
    path.write_text(source, encoding="utf-8")
