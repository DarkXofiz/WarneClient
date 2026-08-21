package warne.client.features.modules.client;

import warne.client.features.modules.Module;
import warne.client.setting.Setting;

public final class Notifications extends Module {
    public Notifications() {
        super("Notifications", Category.CLIENT);
    }

    public final Setting<Mode> mode = new Setting<>("Mode", Mode.CrossHair);

    public enum Mode {
        Default, CrossHair, Text
    }
}
