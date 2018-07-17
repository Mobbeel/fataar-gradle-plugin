LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)

LOCAL_MODULE     := example-nested-native-lib
LOCAL_SRC_FILES  :=

LOCAL_C_INCLUDES += $(LOCAL_PATH)

LOCAL_LDLIBS     += -llog -ldl

include $(BUILD_SHARED_LIBRARY)
