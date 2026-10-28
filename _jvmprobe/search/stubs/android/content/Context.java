package android.content;

import java.io.File;

/**
 * 仅编译期桩：DramaIndex.load()/rebuild() 会引用 context.filesDir，
 * search() 路径不触碰它。运行时只要不调用 load/rebuild 就不会用到。
 */
public class Context {
    private File filesDir = new File(".");

    public File getFilesDir() {
        return filesDir;
    }
}
