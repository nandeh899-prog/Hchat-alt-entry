package com.aeonlab.toolkit.utils;

import com.aeonlab.toolkit.xposed.compat.XposedBridge;

/**
 * LSPosed treats Throwable logs as error logs. Use this only for real failures.
 */
public final class HLog {
    private HLog() {
    }

    /** 普通信息日志（不抛栈，LSPosed 不标记为错误） */
    public static void i(String message) {
        XposedBridge.log(message);
    }

    public static void e(String message) {
        XposedBridge.log(new RuntimeException(message));
    }

    public static void e(String message, Throwable throwable) {
        if (throwable == null) {
            e(message);
            return;
        }
        XposedBridge.log(new RuntimeException(message, throwable));
    }
}
