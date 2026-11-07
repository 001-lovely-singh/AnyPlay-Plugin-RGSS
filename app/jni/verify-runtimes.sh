#!/bin/bash
# Checks the native libraries of a built APK (or of an unzipped lib/ folder).
#
#   ./verify-runtimes.sh AnyPlay-RGSS-Plugin.apk
#   ./verify-runtimes.sh path/to/folder-that-contains-lib/
#
# For every ABI it verifies, per runtime:
#   * libruby_r3.so / libruby_r18.so exist and have the internal SONAME "libruby.so"
#   * the engine (libmkxp-z-r3.so / libmkxp-z-r18.so) has NEEDED libruby.so
#   * the engine exports ONLY SDL_main
#   * the temporary real-1.8.7 pair (libruby18.so / libmkxp-z-ruby18.so) is consistent
#   * there is NO file called libruby.so or libmkxp-z.so (old names)
# Exit code 1 if anything is wrong. Tool: llvm-readelf from the NDK, else readelf.

set -u

SRC="${1:-}"
if [ -z "$SRC" ] || [ ! -e "$SRC" ]; then
  echo "usage: $0 <apk | folder containing lib/>" >&2
  exit 2
fi

READELF="${READELF:-}"
if [ -z "$READELF" ]; then
  for c in "${ANDROID_NDK_HOME:-/nonexistent}"/toolchains/llvm/prebuilt/*/bin/llvm-readelf; do
    [ -x "$c" ] && READELF="$c" && break
  done
fi
[ -z "$READELF" ] && READELF="$(command -v llvm-readelf || command -v readelf || true)"
if [ -z "$READELF" ]; then echo "no readelf found" >&2; exit 2; fi
echo "using: $READELF"

if [ -d "$SRC" ]; then
  ROOT="$SRC"
else
  ROOT="$(mktemp -d)"
  trap 'rm -rf "$ROOT"' EXIT
  unzip -q -o "$SRC" 'lib/*' -d "$ROOT" || { echo "cannot unzip lib/ from $SRC" >&2; exit 2; }
fi

FAIL=0
fail() { echo "  FAIL: $*"; FAIL=1; }
ok()   { echo "  ok:   $*"; }

soname_of()  { "$READELF" -d -W "$1" 2>/dev/null | sed -n 's/.*(SONAME).*\[\(.*\)\].*/\1/p'; }
needed_of()  { "$READELF" -d -W "$1" 2>/dev/null | sed -n 's/.*(NEEDED).*\[\(.*\)\].*/\1/p'; }
# defined, global/weak dynamic symbols (what other libraries can see)
exports_of() {
  "$READELF" --dyn-syms -W "$1" 2>/dev/null \
    | awk '$1 ~ /^[0-9]+:$/ && $7 != "UND" && ($5 == "GLOBAL" || $5 == "WEAK") { n=$8; sub(/@.*/, "", n); print n }' \
    | sort -u
}

check_engine() {   # abi_dir engine_file expected_needed forbidden_needed
  local dir="$1" eng="$2" want="$3" forbid="$4"
  local f="$dir/$eng"
  if [ ! -f "$f" ]; then fail "$eng is missing"; return; fi
  local needed; needed="$(needed_of "$f")"
  if echo "$needed" | grep -qx "$want"; then ok "$eng NEEDED $want"; else fail "$eng does not NEED $want (needs: $(echo $needed))"; fi
  if [ -n "$forbid" ] && echo "$needed" | grep -qx "$forbid"; then fail "$eng NEEDS $forbid (wrong Ruby)"; fi
  if echo "$needed" | grep -Eq '^libruby_r[0-9]+\.so$'; then fail "$eng NEEDS a renamed Ruby file instead of its SONAME"; fi
  local ex; ex="$(exports_of "$f" | tr '\n' ' ' | sed 's/ $//')"
  if [ "$ex" = "SDL_main" ]; then ok "$eng exports only SDL_main"; else fail "$eng exports: [$ex] (expected only SDL_main)"; fi
}

check_ruby() {     # abi_dir file expected_soname
  local dir="$1" file="$2" want="$3"
  local f="$dir/$file"
  if [ ! -f "$f" ]; then fail "$file is missing"; return; fi
  local so; so="$(soname_of "$f")"
  if [ "$so" = "$want" ]; then ok "$file SONAME $so"; else fail "$file SONAME is '$so' (expected $want)"; fi
}

FOUND=0
for dir in "$ROOT"/lib/*/; do
  [ -d "$dir" ] || continue
  dir="${dir%/}"
  abi="$(basename "$dir")"
  FOUND=1
  echo "== $abi"

  for old in libruby.so libmkxp-z.so; do
    [ -e "$dir/$old" ] && fail "$old must not be in the APK (old name; risks loading the wrong runtime)"
  done

  check_ruby "$dir" libruby_r3.so  libruby.so
  check_ruby "$dir" libruby_r18.so libruby.so
  check_ruby "$dir" libruby18.so   libruby18.so   # temporary real 1.8.7

  check_engine "$dir" libmkxp-z-r3.so      libruby.so   libruby18.so
  check_engine "$dir" libmkxp-z-r18.so     libruby.so   libruby18.so
  check_engine "$dir" libmkxp-z-ruby18.so  libruby18.so libruby.so

  # r3 and r18 are different builds of the same source today; they must at least
  # be separate files. (After Phase B they will also differ in content.)
  if [ -f "$dir/libruby_r3.so" ] && [ -f "$dir/libruby_r18.so" ]; then
    ok "libruby_r3.so and libruby_r18.so are separate files"
  fi
done

if [ "$FOUND" = 0 ]; then echo "no lib/<abi>/ folders found" >&2; exit 2; fi

if [ "$FAIL" = 0 ]; then echo "ALL RUNTIME CHECKS PASSED"; else echo "RUNTIME CHECKS FAILED"; fi
exit "$FAIL"
