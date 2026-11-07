#ifndef RUBY187_SOURCE_NORMALIZER_H
#define RUBY187_SOURCE_NORMALIZER_H

#include <ruby.h>

struct Config;

/* Rewrites Ruby 1.8-era script sources so a modern MRI accepts them. `scriptArray`
 * is the decoded Scripts.rxdata array (entry[3] = source, entry[1] = name).
 * Returns an Array of Strings (same length), or Qnil on failure. */
VALUE ruby187NormalizeScripts(VALUE scriptArray, long scriptCount, const Config &conf);

#endif
