package com.autobuilder;

import net.minecraft.client.resources.language.I18n;

/**
 * 界面文字的翻译：键都在 assets/autobuilder/lang/ 下，统一加前缀 "autobuilder."。
 * 译文里用 %s 依次填参数；要显示百分号写 %%。
 */
public final class Lang {
    private Lang() {}

    public static String t(String key, Object... args) {
        return I18n.get("autobuilder." + key, args);
    }
}
