L_PATH := $(call my-dir)

# Include dependecies
include $(L_PATH)/SDL2/Android.mk 
include $(L_PATH)/SDL2_image/Android.mk
include $(L_PATH)/SDL2_ttf/Android.mk
include $(L_PATH)/SDL2_sound.mk
include $(L_PATH)/libogg.mk
include $(L_PATH)/libvorbis.mk
include $(L_PATH)/libtheora.mk
include $(L_PATH)/openal.mk
include $(L_PATH)/pixman.mk
include $(L_PATH)/physfs.mk
include $(L_PATH)/uchardet.mk
include $(L_PATH)/libiconv.mk
include $(L_PATH)/openssl.mk

# ---------------------------------------------------------------------------
# Runtimes. A runtime = one libruby + one engine, loaded together in a fresh
# process (one process = one runtime; Ruby cannot be initialised twice).
#
#   id    libruby (file in APK)  engine (file in APK)       Ruby
#   r3    libruby_r3.so          libmkxp-z-r3.so            3.1, no compat patches
#   r18   libruby_r18.so         libmkxp-z-r18.so           3.1 source, own build
#                                                           (Phase B adds the
#                                                           Ruby 1.8 patches here)
#   r187  libruby18.so           libmkxp-z-ruby18.so        real Ruby 1.8.7
#                                                           (TEMPORARY, removed in
#                                                           Phase B)
#
# r3 and r18 libraries both have the internal SONAME "libruby.so" (see ruby.mk).
# ---------------------------------------------------------------------------

# Ruby 3.1 libraries (prebuilt by the Makefile)
RUBY_PB_MODULE := ruby_r3
RUBY_PB_DIR    := build-$(TARGET_ARCH_ABI)
include $(L_PATH)/ruby.mk

RUBY_PB_MODULE := ruby_r18
RUBY_PB_DIR    := build-r18-$(TARGET_ARCH_ABI)
include $(L_PATH)/ruby.mk

# Real Ruby 1.8.7 (temporary, see above)
include $(L_PATH)/ruby18.mk

# r3 engine
MKXPZ_MODULE := mkxp-z-r3
MKXPZ_RUBY_MODULE := ruby_r3
MKXPZ_RUNTIME_ID := r3
MKXPZ_LEGACY_RUBY := 0
include $(L_PATH)/mkxp-z.mk

# r18 engine (same engine source, compiled against the r18 Ruby headers)
MKXPZ_MODULE := mkxp-z-r18
MKXPZ_RUBY_MODULE := ruby_r18
MKXPZ_RUNTIME_ID := r18
MKXPZ_LEGACY_RUBY := 0
include $(L_PATH)/mkxp-z.mk

# r187 engine: native Ruby 1.8.7. Still loaded in a fresh process, never together
# with another Ruby.
MKXPZ_MODULE := mkxp-z-ruby18
MKXPZ_RUBY_MODULE := ruby18
MKXPZ_RUNTIME_ID := r187
MKXPZ_LEGACY_RUBY := 1
include $(L_PATH)/mkxp-z.mk
