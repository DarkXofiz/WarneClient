package warne.client.features.modules.player;

import meteordevelopment.orbit.EventHandler;
import meteordevelopment.orbit.EventPriority;
import warne.client.WarneClient;
import warne.client.core.Managers;
import warne.client.core.manager.client.ModuleManager;
import warne.client.events.impl.EventTick;
import warne.client.features.modules.Module;

public class TpsSync extends Module {
    public TpsSync() {
        super("TpsSync", Module.Category.PLAYER);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onTick(EventTick e) {
        if (ModuleManager.timer.isEnabled()) return;
        if (Managers.SERVER.getTPS() > 1)
            WarneClient.TICK_TIMER = Managers.SERVER.getTPS() / 20f;
        else WarneClient.TICK_TIMER = 1f;
    }

    @Override
    public void onDisable() {
        WarneClient.TICK_TIMER = 1f;
    }
}
