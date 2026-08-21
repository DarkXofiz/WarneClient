package warne.client.injection;

import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import warne.client.core.manager.client.ModuleManager;
import warne.client.features.modules.client.ClientSettings;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.screen.ConfirmScreen;
import net.minecraft.util.Util;
import warne.client.WarneClient;
import net.minecraft.client.util.InputUtil;
import warne.client.gui.misc.DialogScreen;
import warne.client.utility.render.TextureStorage;

import java.net.URI;

import static warne.client.features.modules.Module.mc;
import static warne.client.features.modules.client.ClientSettings.isRu;

@Mixin(TitleScreen.class)
public class MixinTitleScreen extends Screen {
    protected MixinTitleScreen(Text title) {
        super(title);
    }

    @Inject(method = "init", at = @At("RETURN"))
    public void postInitHook(CallbackInfo ci) {
        // Özel ana menü devre dışı bırakıldı — Minecraft'ın varsayılan menüsü gösterilir.
        // P tuşu bind edilmemişse otomatik olarak ata
        if (ModuleManager.clickGui.getBind().getKey() == -1) {
            ModuleManager.clickGui.setBind(InputUtil.fromTranslationKey("key.keyboard.p").getCode(), false, false);
        }
    }
}
