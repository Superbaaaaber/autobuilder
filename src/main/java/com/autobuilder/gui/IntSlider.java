package com.autobuilder.gui;

import com.autobuilder.Lang;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.network.chat.Component;

/** 整数滑块：标签用 Lang 键，%s 填当前值 */
public class IntSlider extends AbstractSliderButton {
    private final String key;
    private final int min, max;

    public IntSlider(int x, int y, int w, String key, int min, int max, int value) {
        super(x, y, w, 20, Component.empty(), (Math.max(min, Math.min(max, value)) - min) / (double) (max - min));
        this.key = key;
        this.min = min;
        this.max = max;
        updateMessage();
    }

    public int value() {
        return min + (int) Math.round(value * (max - min));
    }

    @Override
    protected void updateMessage() {
        setMessage(Component.literal(Lang.t(key, value())));
    }

    @Override
    protected void applyValue() {}
}
