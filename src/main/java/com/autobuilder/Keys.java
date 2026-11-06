package com.autobuilder;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import org.lwjgl.glfw.GLFW;

public final class Keys {
    public static final String CATEGORY = "key.categories.autobuilder";
    public static final KeyMapping OPEN = new KeyMapping("key.autobuilder.open", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_B, CATEGORY);
    public static final KeyMapping TOGGLE = new KeyMapping("key.autobuilder.toggle", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_N, CATEGORY);
    public static final KeyMapping ROTATE = new KeyMapping("key.autobuilder.rotate", InputConstants.UNKNOWN.getValue(), CATEGORY);
    public static final KeyMapping LOAD_HERE = new KeyMapping("key.autobuilder.here", InputConstants.UNKNOWN.getValue(), CATEGORY);
    public static final KeyMapping POS1 = new KeyMapping("key.autobuilder.pos1", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_LEFT_BRACKET, CATEGORY);
    public static final KeyMapping POS2 = new KeyMapping("key.autobuilder.pos2", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_RIGHT_BRACKET, CATEGORY);

    private Keys() {}

    public static void register(RegisterKeyMappingsEvent e) {
        e.register(OPEN);
        e.register(TOGGLE);
        e.register(ROTATE);
        e.register(LOAD_HERE);
        e.register(POS1);
        e.register(POS2);
    }
}
