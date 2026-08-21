package warne.client.features.modules.combat;

import meteordevelopment.orbit.EventHandler;
import net.minecraft.util.hit.EntityHitResult;
import warne.client.events.impl.EventAttack;
import warne.client.events.impl.EventHandleBlockBreaking;
import warne.client.features.modules.Module;

public class AntiLegitMiss extends Module {
    public AntiLegitMiss() {
        super("AntiLegitMiss", Category.COMBAT);
    }

    @EventHandler
    public void onAttack(EventAttack e) {
        if (!(mc.crosshairTarget instanceof EntityHitResult) && e.isPre())
            e.cancel();
    }

    @EventHandler
    public void onBlockBreaking(EventHandleBlockBreaking e) {
        if (!(mc.crosshairTarget instanceof EntityHitResult))
            e.cancel();
    }
}
