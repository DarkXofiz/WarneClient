package warne.client.features.modules.player;

import warne.client.features.modules.Module;
import warne.client.setting.Setting;

public final class NoEntityTrace extends Module {
    public NoEntityTrace() {
        super("NoEntityTrace", Category.PLAYER);
    }

    public static final Setting<Boolean> ponly = new Setting<>("Pickaxe Only", true);
    public static final Setting<Boolean> noSword = new Setting<>("No Sword", true);
}