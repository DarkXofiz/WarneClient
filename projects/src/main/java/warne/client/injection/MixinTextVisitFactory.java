package warne.client.injection;

import warne.client.core.manager.player.FriendManager;
import warne.client.core.manager.client.ModuleManager;
import warne.client.features.modules.misc.NameProtect;
import net.minecraft.text.TextVisitFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

import static warne.client.features.modules.Module.mc;

@Mixin(value = {TextVisitFactory.class})
public class MixinTextVisitFactory {
    @ModifyArg(at = @At(value = "INVOKE", target = "Lnet/minecraft/text/TextVisitFactory;visitFormatted(Ljava/lang/String;ILnet/minecraft/text/Style;Lnet/minecraft/text/Style;Lnet/minecraft/text/CharacterVisitor;)Z", ordinal = 0), method = {"visitFormatted(Ljava/lang/String;ILnet/minecraft/text/Style;Lnet/minecraft/text/CharacterVisitor;)Z" }, index = 0)
    private static String adjustText(String text) {
        return protect(text);
    }

    private static String protect(String string) {
        if (!ModuleManager.nameProtect.isEnabled() || mc.player == null)
            return string;

        String me = mc.getSession().getUsername();

        // Kendi ismimizi gizle
        if (string.contains(me)) {
            return string.replace(me, NameProtect.getCustomName());
        }

        // Arkadaş isimlerini gizle - DUZELTILDI: string.contains(i) degil i.equalsIgnoreCase(string)
        if (NameProtect.hideFriends.getValue()) {
            for (String friend : FriendManager.friends) {
                if (!friend.isEmpty() && string.contains(friend)) {
                    return string.replace(friend, "Friend");
                }
            }
        }

        return string;
    }
}
