package com.autobuilder.build;

import com.autobuilder.Lang;
import java.util.Locale;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.network.chat.Component;

/**
 * 服务器端禁用开关（server-side opt-out）。
 * 服务器不想让玩家用自动建造时，只要满足下面任意一条，本 mod 在该服务器上就不会自动建造
 * （加载蓝图、预览、保存建筑这些不影响游戏的功能照常可用）：
 * <ul>
 *   <li>服务器列表里的 MOTD 含有 {@link #TOKEN}</li>
 *   <li>服务器发给玩家的系统消息含有 {@link #TOKEN}（比如进服欢迎语，或 tellraw）；
 *       只有这个标记的消息不会显示在聊天栏</li>
 * </ul>
 * 单人游戏不受影响。断开连接后重置。
 */
public final class ServerPolicy {
    /** 不区分大小写 */
    public static final String TOKEN = "[NoAutoBuilder]";
    private static final String TOKEN_LC = TOKEN.toLowerCase(Locale.ROOT);

    private static boolean disabledByMessage;
    private static String checkedMotdOf;
    private static boolean disabledByMotd;

    private ServerPolicy() {}

    /** 当前世界是否允许自动建造 */
    public static boolean allowed() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.hasSingleplayerServer()) return true;
        if (disabledByMessage) return false;
        ServerData sd = mc.getCurrentServer();
        if (sd == null) return true;
        // MOTD 来自服务器列表的 ping，进服后可能才刷新出来，变了就重新检查
        String motd = sd.motd == null ? "" : sd.motd.getString();
        if (!motd.equals(checkedMotdOf)) {
            checkedMotdOf = motd;
            disabledByMotd = motd.toLowerCase(Locale.ROOT).contains(TOKEN_LC);
        }
        return !disabledByMotd;
    }

    /**
     * 收到服务器消息时调用。
     * @return true 表示这条消息只有禁用标记，不需要显示
     */
    public static boolean onServerMessage(Component message) {
        if (Minecraft.getInstance().hasSingleplayerServer()) return false;
        String s = message.getString();
        if (!s.toLowerCase(Locale.ROOT).contains(TOKEN_LC)) return false;
        if (!disabledByMessage) {
            disabledByMessage = true;
            if (BuildController.state() == BuildController.State.RUNNING || BuildController.state() == BuildController.State.PAUSED) {
                BuildController.stop();
            }
            BuildController.msg(Lang.t("msg.server_disabled_notice"), ChatFormatting.GOLD);
        }
        return s.trim().equalsIgnoreCase(TOKEN);
    }

    public static void reset() {
        disabledByMessage = false;
        checkedMotdOf = null;
        disabledByMotd = false;
    }
}
