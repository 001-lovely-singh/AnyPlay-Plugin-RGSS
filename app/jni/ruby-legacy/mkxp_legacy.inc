/*
 * mkxp_legacy.inc -- AnyPlay "Ruby 1.8 compatibility" layer for the r18 runtime.
 *
 * This file is NOT compiled on its own. apply-legacy-patches.py copies it into the
 * ruby-r18 source tree and appends `#include "mkxp_legacy.inc"` to vm_eval.c, so it
 * is built as part of libruby (the r18 one only; r3 never gets it).
 *
 * Exported API (names follow what the engine calls, see binding/legacy-ruby.h):
 *   int  mkxp_legacy_set_enabled(int on)       turn legacy mode on/off, returns old value
 *   int  mkxp_legacy_enabled_p(void)
 *   void mkxp_legacy_prepare_next_eval(const char *path)
 *   void mkxp_legacy_cancel_next_eval(void)
 *   int  mkxp_legacy_caller_p(void)            is the calling Ruby frame legacy code?
 *
 * "Legacy code" = code compiled from a file name registered through
 * prepare_next_eval(). Child iseqs (methods, blocks) of a script keep the script's
 * path, so everything defined by a game script stays legacy for its whole life,
 * while Ruby's own stdlib (other paths) keeps normal Ruby 3.1 behaviour.
 */

#define MKXP_LEGACY_EXPORT __attribute__((visibility("default")))

#include "mkxp_legacy_prelude.h"   /* generated: mkxp_legacy_prelude_src[] */

static int mkxp_legacy_enabled = 0;
static int mkxp_legacy_spoofed = 0;
static int mkxp_legacy_shims_done = 0;
static __thread int mkxp_legacy_next_eval = 0;
static st_table *mkxp_legacy_paths = NULL;

/* one-entry cache for mkxp_legacy_caller_p (all callers hold the GVL). The key is a
 * COPY of the path text, not its address: a freed eval string's address can be reused
 * by a different file name, so an address comparison could return a stale answer. */
static char mkxp_legacy_last_file[256];
static int mkxp_legacy_last_result = 0;

MKXP_LEGACY_EXPORT int
mkxp_legacy_enabled_p(void)
{
    return mkxp_legacy_enabled;
}

MKXP_LEGACY_EXPORT void
mkxp_legacy_prepare_next_eval(const char *path)
{
    if (!mkxp_legacy_enabled) return;
    mkxp_legacy_next_eval = 1;
    if (!path || !*path) return;
    if (!mkxp_legacy_paths) mkxp_legacy_paths = st_init_strtable();
    if (!st_lookup(mkxp_legacy_paths, (st_data_t)path, NULL)) {
        char *copy = strdup(path);
        if (copy) st_insert(mkxp_legacy_paths, (st_data_t)copy, (st_data_t)1);
    }
    mkxp_legacy_last_file[0] = '\0'; /* drop the cache */
}

/* Only clears the "next eval" flag. The path stays registered on purpose: a script
 * that failed half way may already have defined methods that run later. */
MKXP_LEGACY_EXPORT void
mkxp_legacy_cancel_next_eval(void)
{
    mkxp_legacy_next_eval = 0;
}

MKXP_LEGACY_EXPORT int
mkxp_legacy_next_eval_pending_p(void)
{
    return mkxp_legacy_next_eval;
}

MKXP_LEGACY_EXPORT int
mkxp_legacy_caller_p(void)
{
    const char *file;
    if (!mkxp_legacy_enabled || !mkxp_legacy_paths) return 0;
    file = rb_sourcefile();
    if (!file) return 0;
    if (mkxp_legacy_last_file[0] && strcmp(file, mkxp_legacy_last_file) == 0)
        return mkxp_legacy_last_result;
    mkxp_legacy_last_result = st_lookup(mkxp_legacy_paths, (st_data_t)file, NULL) ? 1 : 0;
    if (strlen(file) < sizeof(mkxp_legacy_last_file)) strcpy(mkxp_legacy_last_file, file);
    else mkxp_legacy_last_file[0] = '\0';
    return mkxp_legacy_last_result;
}

/* ---- String#[] with an Integer index gives the byte NUMBER in Ruby 1.8 ---- */

static ID mkxp_legacy_id_orig_aref;

static VALUE
mkxp_legacy_str_aref(int argc, VALUE *argv, VALUE str)
{
    if (argc == 1 && FIXNUM_P(argv[0]) && mkxp_legacy_caller_p()) {
        long len = RSTRING_LEN(str);
        long idx = FIX2LONG(argv[0]);
        if (idx < 0) idx += len;
        if (idx < 0 || idx >= len) return Qnil;
        return INT2FIX((unsigned char)RSTRING_PTR(str)[idx]);
    }
    return rb_funcallv(str, mkxp_legacy_id_orig_aref, argc, argv);
}

/* ---- RUBY_VERSION & co. -------------------------------------------------- */

static VALUE
mkxp_legacy_const_swap_body(VALUE ary)
{
    long i;
    for (i = 0; i + 1 < RARRAY_LEN(ary); i += 2) {
        VALUE name = RARRAY_AREF(ary, i);
        VALUE val = RARRAY_AREF(ary, i + 1);
        ID id = SYM2ID(name);
        if (rb_const_defined_at(rb_cObject, id)) {
            VALUE old = rb_const_get_at(rb_cObject, id);
            char realname[64];
            ID realid;
            snprintf(realname, sizeof(realname), "%s_REAL", rb_id2name(id));
            realid = rb_intern(realname);
            if (!rb_const_defined_at(rb_cObject, realid))
                rb_define_global_const(realname, old);
            rb_funcall(rb_cObject, rb_intern("remove_const"), 1, name);
        }
        rb_define_global_const(rb_id2name(id), val);
    }
    return Qnil;
}

static void
mkxp_legacy_apply_version_spoof(void)
{
    int state = 0;
    VALUE platform = rb_const_defined_at(rb_cObject, rb_intern("RUBY_PLATFORM"))
        ? rb_const_get_at(rb_cObject, rb_intern("RUBY_PLATFORM")) : rb_str_new_cstr("unknown");
    VALUE desc = rb_sprintf("ruby 1.8.7 (2013-06-27 patchlevel 374) [%"PRIsVALUE"]", platform);
    VALUE ary = rb_ary_new();
    VALUE old_verbose = ruby_verbose;
    rb_ary_push(ary, ID2SYM(rb_intern("RUBY_VERSION")));       rb_ary_push(ary, rb_obj_freeze(rb_str_new_cstr("1.8.7")));
    rb_ary_push(ary, ID2SYM(rb_intern("RUBY_PATCHLEVEL")));    rb_ary_push(ary, INT2FIX(374));
    rb_ary_push(ary, ID2SYM(rb_intern("RUBY_RELEASE_DATE")));  rb_ary_push(ary, rb_obj_freeze(rb_str_new_cstr("2013-06-27")));
    rb_ary_push(ary, ID2SYM(rb_intern("RUBY_COPYRIGHT")));     rb_ary_push(ary, rb_obj_freeze(rb_str_new_cstr("ruby - Copyright (C) 1993-2013 Yukihiro Matsumoto")));
    rb_ary_push(ary, ID2SYM(rb_intern("RUBY_DESCRIPTION")));   rb_ary_push(ary, rb_obj_freeze(desc));
    ruby_verbose = Qnil;
    rb_protect(mkxp_legacy_const_swap_body, ary, &state);
    ruby_verbose = old_verbose;
    if (state) rb_set_errinfo(Qnil);
    else mkxp_legacy_spoofed = 1;
    /* RUBY_API_VERSION / RUBY_ENGINE are deliberately left alone: the load path
     * (lib/ruby/3.1.0) and the C extensions depend on them. */
}

static VALUE
mkxp_legacy_eval_prelude(VALUE unused)
{
    (void)unused;
    return rb_eval_string(mkxp_legacy_prelude_src);
}

MKXP_LEGACY_EXPORT int
mkxp_legacy_set_enabled(int on)
{
    int old = mkxp_legacy_enabled;
    mkxp_legacy_enabled = on ? 1 : 0;
    if (on && !mkxp_legacy_shims_done) {
        int state = 0;
        mkxp_legacy_shims_done = 1;
        mkxp_legacy_id_orig_aref = rb_intern("__mkxp_legacy_aref");
        rb_define_alias(rb_cString, "__mkxp_legacy_aref", "[]");
        rb_define_method(rb_cString, "[]", mkxp_legacy_str_aref, -1);
        rb_define_method(rb_cString, "slice", mkxp_legacy_str_aref, -1);
        mkxp_legacy_apply_version_spoof();
        /* prelude: Ruby-level shims. It reports its own failures in
         * $__mkxp_legacy_errors, so one bad shim cannot stop the others. */
        rb_protect(mkxp_legacy_eval_prelude, Qnil, &state);
        if (state) {
            rb_gv_set("$__mkxp_legacy_errors", rb_str_new_cstr("prelude failed to load"));
            rb_set_errinfo(Qnil);
        }
    }
    return old;
}
