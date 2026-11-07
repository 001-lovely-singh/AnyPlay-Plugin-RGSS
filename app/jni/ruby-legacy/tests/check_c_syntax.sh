#!/bin/sh
# Host-side sanity check of mkxp_legacy.inc against a stub of the Ruby C API.
# Catches typos and type errors in OUR code. It cannot catch a wrong assumption
# about Ruby's real headers: only the real build (ruby-r18) proves that.
set -e
D=$(cd "$(dirname "$0")" && pwd)
W=$(mktemp -d); trap 'rm -rf "$W"' EXIT
python3 - "$D/.." "$W" <<'PY'
import sys,os
sys.path.insert(0, sys.argv[1])
src=open(os.path.join(sys.argv[1],"legacy_prelude.rb"),"rb").read()
open(os.path.join(sys.argv[2],"mkxp_legacy_prelude.h"),"w").write(
 "static const char mkxp_legacy_prelude_src[] = {"+",".join(str(b) for b in src)+",0};\n")
PY
cp "$D/../mkxp_legacy.inc" "$W/"
printf '#include "ruby.h"\n#include "mkxp_legacy.inc"\n' > "$W/t.c"
gcc -std=gnu99 -Wall -Wextra -Wno-unused-parameter -Wno-incompatible-pointer-types -fsyntax-only -I"$D/stub" -I"$W" "$W/t.c"
echo "mkxp_legacy.inc: syntax/type check OK"
