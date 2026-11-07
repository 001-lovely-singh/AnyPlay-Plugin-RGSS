/* Minimal STUB of the Ruby C API used by mkxp_legacy.inc. Only for `gcc -fsyntax-only`
 * on a host without Ruby headers. Signatures follow Ruby 3.1's public headers. */
#ifndef STUB_RUBY_H
#define STUB_RUBY_H
#include <stdio.h>
#include <string.h>
#include <stdlib.h>
typedef unsigned long VALUE; typedef unsigned long ID; typedef unsigned long st_data_t;
typedef struct st_table st_table;
#define Qnil ((VALUE)8)
#define FIXNUM_P(x) (((x)&1)!=0)
#define FIX2LONG(x) ((long)(x)>>1)
#define INT2FIX(i) ((VALUE)(((long)(i))<<1|1))
#define RSTRING_LEN(s) rb_str_len_stub(s)
#define RSTRING_PTR(s) rb_str_ptr_stub(s)
#define RARRAY_LEN(a) rb_ary_len_stub(a)
#define RARRAY_AREF(a,i) rb_ary_aref_stub(a,i)
#define PRIsVALUE "li"
#define ID2SYM(i) ((VALUE)(i))
#define SYM2ID(v) ((ID)(v))
#define ruby_verbose (*rb_ruby_verbose_ptr())
long rb_str_len_stub(VALUE); char *rb_str_ptr_stub(VALUE); long rb_ary_len_stub(VALUE); VALUE rb_ary_aref_stub(VALUE,long);
VALUE *rb_ruby_verbose_ptr(void);
extern VALUE rb_cString, rb_cObject;
st_table *st_init_strtable(void); int st_lookup(st_table*, st_data_t, st_data_t*); int st_insert(st_table*, st_data_t, st_data_t);
const char *rb_sourcefile(void);
VALUE rb_funcallv(VALUE, ID, int, const VALUE*);
VALUE rb_funcall(VALUE, ID, int, ...);
ID rb_intern(const char*); const char *rb_id2name(ID);
void rb_define_alias(VALUE, const char*, const char*);
void rb_define_method(VALUE, const char*, VALUE(*)(), int);
int rb_const_defined_at(VALUE, ID); VALUE rb_const_get_at(VALUE, ID);
void rb_define_global_const(const char*, VALUE);
VALUE rb_str_new_cstr(const char*); VALUE rb_sprintf(const char*, ...); VALUE rb_obj_freeze(VALUE);
VALUE rb_ary_new(void); VALUE rb_ary_push(VALUE, VALUE);
VALUE rb_protect(VALUE(*)(VALUE), VALUE, int*); void rb_set_errinfo(VALUE);
VALUE rb_eval_string(const char*); void rb_gv_set(const char*, VALUE);
#endif
