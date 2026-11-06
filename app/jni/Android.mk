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
include $(L_PATH)/ruby.mk
include $(L_PATH)/ruby18.mk

# Ruby 3.1 engine. Keep the historical module names for existing installs.
MKXPZ_MODULE := mkxp-z
MKXPZ_RUBY_MODULE := ruby
MKXPZ_LEGACY_RUBY := 0
include $(L_PATH)/mkxp-z.mk

# Native Ruby 1.8.7 engine. It is loaded in a fresh process instead of
# attempting to initialize two MRI VMs in one process.
MKXPZ_MODULE := mkxp-z-ruby18
MKXPZ_RUBY_MODULE := ruby18
MKXPZ_LEGACY_RUBY := 1
include $(L_PATH)/mkxp-z.mk
