LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)
LOCAL_MODULE:= libtermux
LOCAL_SRC_FILES:= termux.c
# Android 15 loads 16 KB pages; a 4 KB-aligned .so is what makes the
# compatibility dialog appear, and a 16 KB device refuses to load it.
LOCAL_LDFLAGS := -Wl,-z,max-page-size=16384
include $(BUILD_SHARED_LIBRARY)
