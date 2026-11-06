package com.autobuilder;

import com.autobuilder.dev.AutoTest;
import com.autobuilder.render.HudOverlay;
import com.mojang.logging.LogUtils;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;

/**
 * AutoBuilder：纯客户端的生存自动建造 mod。
 * 只在客户端加载，服务器不需要安装。
 */
@Mod(value = AutoBuilder.MODID, dist = Dist.CLIENT)
public class AutoBuilder {
    public static final String MODID = "autobuilder";
    public static final Logger LOG = LogUtils.getLogger();
    private static ModContainer container;

    public AutoBuilder(IEventBus modBus, ModContainer container) {
        AutoBuilder.container = container;
        container.registerConfig(ModConfig.Type.CLIENT, ABConfig.SPEC);
        container.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);
        modBus.addListener(Keys::register);
        modBus.addListener(HudOverlay::register);
        NeoForge.EVENT_BUS.register(ClientEvents.class);
        if (AutoTest.ENABLED) {
            NeoForge.EVENT_BUS.register(AutoTest.class);
        }
    }

    /** 打开设置界面（和 模组列表 → AutoBuilder → 配置 是同一个），关掉后回到 parent */
    public static void openConfig(Screen parent) {
        Minecraft.getInstance().setScreen(new ConfigurationScreen(container, parent));
    }
}
