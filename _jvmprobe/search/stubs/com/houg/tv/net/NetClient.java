package com.houg.tv.net;

import java.util.Collections;
import java.util.List;

/**
 * 仅编译期桩：DramaIndex 只依赖 NetClient.catalogAll(...) 与 Drama 的
 * id/title/cover 字段。search() 探针不会调用 catalogAll。
 */
public class NetClient {

    public static class Drama {
        public final String id;
        public final String title;
        public final String cover;

        public Drama(String id, String title, String cover) {
            this.id = id;
            this.title = title;
            this.cover = cover;
        }
    }

    public List<Drama> catalogAll(String genreKey, String scene, int maxPages) {
        return Collections.emptyList();
    }
}
