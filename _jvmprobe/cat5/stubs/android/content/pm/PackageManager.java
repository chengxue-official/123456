package android.content.pm;

/** JVM 探针专用 PackageManager 桩：只供 firstInstallTime 的 try/catch 兜底路径使用。 */
public class PackageManager {
    public PackageInfo getPackageInfo(String packageName, int flags) {
        PackageInfo pi = new PackageInfo();
        pi.firstInstallTime = System.currentTimeMillis();
        return pi;
    }
}
