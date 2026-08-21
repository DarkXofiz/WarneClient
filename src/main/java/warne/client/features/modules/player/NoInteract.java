package warne.client.features.modules.player;

import warne.client.features.modules.Module;
import warne.client.setting.Setting;

public class NoInteract extends Module {
    public NoInteract() {
        super("NoInteract", Category.PLAYER);
    }

    public static Setting<Boolean> onlyAura = new Setting<>("OnlyAura", false);
}
