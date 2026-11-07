#ifndef LEGACY_RUBY_H
#define LEGACY_RUBY_H

/*
 * AnyPlay: engine side of the Ruby 1.8 compatibility layer.
 *
 * The functions below are implemented INSIDE libruby of the r18 runtime
 * (jni/ruby-legacy/mkxp_legacy.inc). Only the r18 engine is compiled with
 * -DMKXPZ_RUBY18_COMPAT, so only it references them; the r3 engine does not and
 * keeps working with an unpatched libruby.
 *
 * Without MKXPZ_RUBY18_COMPAT every helper here is an empty inline function.
 */

#include <stdlib.h>

#ifdef MKXPZ_RUBY18_COMPAT

extern "C" {
int  mkxp_legacy_set_enabled(int on);
int  mkxp_legacy_enabled_p(void);
void mkxp_legacy_prepare_next_eval(const char *path);
void mkxp_legacy_cancel_next_eval(void);
}

/* Settings that must exist before Ruby's GC reads them (ruby_options()). The
 * values are only defaults (setenv overwrite=0): a developer can still override
 * them from the environment. Ruby 3.1's defaults are tiny for games written for
 * 1.8: a 10 000 slot heap and a 16 MB malloc limit mean a collection every few
 * frames while bitmaps and scenes are created. */
static inline void legacyPrepareEnvironment()
{
    setenv("RUBY_GC_HEAP_INIT_SLOTS",        "400000",    0);
    setenv("RUBY_GC_HEAP_FREE_SLOTS",        "100000",    0);
    setenv("RUBY_GC_MALLOC_LIMIT",           "67108864",  0);   /* 64 MB  */
    setenv("RUBY_GC_MALLOC_LIMIT_MAX",       "268435456", 0);   /* 256 MB */
    setenv("RUBY_GC_OLDMALLOC_LIMIT",        "67108864",  0);
    setenv("RUBY_GC_OLDMALLOC_LIMIT_MAX",    "268435456", 0);
}

static inline bool legacyEnable()                         { return mkxp_legacy_set_enabled(1) >= 0; }
static inline bool legacyEnabled()                        { return mkxp_legacy_enabled_p() != 0; }
static inline void legacyPrepareEval(const char *path)    { mkxp_legacy_prepare_next_eval(path); }
static inline void legacyCancelEval()                     { mkxp_legacy_cancel_next_eval(); }

#else

static inline void legacyPrepareEnvironment()             {}
static inline bool legacyEnable()                         { return false; }
static inline bool legacyEnabled()                        { return false; }
static inline void legacyPrepareEval(const char *)        {}
static inline void legacyCancelEval()                     {}

#endif /* MKXPZ_RUBY18_COMPAT */

#endif /* LEGACY_RUBY_H */
