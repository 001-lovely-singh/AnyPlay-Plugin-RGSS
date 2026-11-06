APP_ABI := arm64-v8a armeabi-v7a
APP_PLATFORM := android-23

ifeq ($(NDK_DEBUG),1)
    APP_OPTIM := debug
else
    APP_OPTIM := release
endif

APP_STL := c++_shared
APP_CPPFLAGS := -std=c++14 -frtti -fexceptions
