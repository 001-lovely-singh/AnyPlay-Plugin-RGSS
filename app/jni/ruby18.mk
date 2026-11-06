LOCAL_PATH := $(call my-dir)/ruby18
RUBY18_BUILD_PATH := $(call my-dir)/build-ruby18-$(TARGET_ARCH_ABI)

include $(CLEAR_VARS)

LOCAL_MODULE := ruby18
# Per-ABI headers copied by the Makefile after each build. Do NOT point at the
# in-tree ruby18/ directory: its config.h belongs to whichever ABI was built last.
LOCAL_C_INCLUDES := $(RUBY18_BUILD_PATH)/include

LOCAL_EXPORT_C_INCLUDES := $(LOCAL_C_INCLUDES)
LOCAL_SRC_FILES := $(RUBY18_BUILD_PATH)/lib/libruby.so

include $(PREBUILT_SHARED_LIBRARY)
