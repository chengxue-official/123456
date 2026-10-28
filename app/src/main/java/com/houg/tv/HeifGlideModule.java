package com.houg.tv;

import android.content.Context;

import com.bumptech.glide.Glide;
import com.bumptech.glide.Registry;
import com.bumptech.glide.annotation.GlideModule;
import com.bumptech.glide.module.AppGlideModule;

/**
 * Glide 模块：启用 HEIC/HEIF 封面解码（红果封面为 HEIC 格式）。
 * GlideHeifDecoder 库通过本模块的 annotation 处理自动注册解码器。
 */
@GlideModule
public final class HeifGlideModule extends AppGlideModule {

    @Override
    public void registerComponents(Context context, Glide glide, Registry registry) {
        super.registerComponents(context, glide, registry);
    }
}
