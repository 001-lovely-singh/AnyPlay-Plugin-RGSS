# ruby-legacy: Ruby 1.8 compatibility layer for the r18 runtime

Applied **only** to the `ruby-r18` source tree (never to `ruby`, which is r3).
`make ruby-r18` runs `apply-legacy-patches.py` first (stamp-file driven).

| File | What it is |
|---|---|
| `mkxp_legacy.inc` | C, built into libruby via an `#include` appended to `vm_eval.c`. Flag API, legacy-code registry, `String#[]` byte numbers, `RUBY_VERSION` spoof. |
| `legacy_prelude.rb` | Ruby shims, evaluated by `mkxp_legacy_set_enabled(1)`. |
| `apply-legacy-patches.py` | Copies the two files above into the tree. Edits **no existing line** of Ruby's sources. |
| `tests/check_c_syntax.sh` | Host check of the C part against a stub of the Ruby API. |
| `tests/legacy_selftest.rb` | Game script that checks the shims on the real r18 VM. |

## Exported API (libruby_r18.so)

```
int  mkxp_legacy_set_enabled(int on)            old value; first `on` installs the shims
int  mkxp_legacy_enabled_p(void)
void mkxp_legacy_prepare_next_eval(const char*) registers the file name as legacy code
void mkxp_legacy_cancel_next_eval(void)         clears the pending flag (name stays registered)
int  mkxp_legacy_caller_p(void)                 is the calling Ruby frame legacy code?
```

"Legacy code" is decided by **file name**: the engine registers `003:Name` / `Section003`
before it evals each game script, and every method/block compiled from that file has the
same path. Ruby's stdlib has other paths, so it keeps Ruby 3.1 behaviour.

## Not done yet (needs the real Ruby tree to write and test against)

Parser / VM changes: `when x:`, `?a` as Integer, `return` inside a top-level proc,
1.8 block-variable scoping. They stay in the text normalizer
(`binding/ruby187-source-normalizer.cpp`) for now. They need edits in `parse.y` /
`vm_insnhelper.c` and a `bison` run to check for grammar conflicts.
