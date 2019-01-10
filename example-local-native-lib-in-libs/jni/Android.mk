LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)

LOCAL_MODULE     := example-local-native-lib-in-libs
LOCAL_SRC_FILES  :=

LOCAL_C_INCLUDES += $(LOCAL_PATH)

LOCAL_LDLIBS     += -llog -ldl

include $(BUILD_SHARED_LIBRARY)
