package com.houg.tv.util;

/** JVM 探针专用 AppLog 桩（Kotlin object 在 Java 侧是 INSTANCE 单例）。 */
public final class AppLog {
    public static final AppLog INSTANCE = new AppLog();

    private AppLog() {
    }

    public void d(String tag, String message) {
        System.out.println("[D] " + tag + " " + message);
    }

    public void e(String tag, String message, Throwable t) {
        System.out.println("[E] " + tag + " " + message + (t == null ? "" : " : " + t));
    }

    public void setInterfaceInfo(String base, String dev, String install) {
    }
}
