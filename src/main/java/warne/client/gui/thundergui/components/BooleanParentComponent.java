package warne.client.gui.thundergui.components;

import net.minecraft.client.util.math.MatrixStack;
import warne.client.features.modules.client.HudEditor;
import warne.client.gui.font.FontRenderers;
import warne.client.gui.thundergui.ThunderGui;
import warne.client.setting.Setting;
import warne.client.setting.impl.BooleanSettingGroup;
import warne.client.utility.render.Render2DEngine;

import java.awt.*;

import static warne.client.utility.render.animation.AnimationUtility.fast;

public class BooleanParentComponent extends SettingElement {
    float animation = 0f;
    private final Setting<BooleanSettingGroup> parentSetting;

    public BooleanParentComponent(Setting setting) {
        super(setting);
        this.parentSetting = setting;
    }

    @Override
    public void render(MatrixStack stack, int mouseX, int mouseY, float partialTicks) {
        super.render(stack, mouseX, mouseY, partialTicks);
        if ((getY() > ThunderGui.getInstance().main_posY + ThunderGui.getInstance().height) || getY() < ThunderGui.getInstance().main_posY) {
            return;
        }
        FontRenderers.modules.drawString(stack, getSetting().getName(), (float) getX(), (float) getY() + 5, isHovered() ? -1 : new Color(0xB0FFFFFF, true).getRGB());
        animation = fast(animation, getParentSetting().getValue().isEnabled() ? 1 : 0, 15f);
        double paddingX = 7 * animation;
        Color color = HudEditor.getColor(1);
        Render2DEngine.drawRound(stack, (float) (x + width - 18), (float) (y + height / 2 - 4), 15, 8, 4, paddingX > 4 ? color : new Color(0xFFB2B1B1));
        Render2DEngine.drawRound(stack, (float) (x + width - 17 + paddingX), (float) (y + height / 2 - 3), 6, 6, 3, new Color(-1));
    }

    @Override
    public void mouseClicked(int mouseX, int mouseY, int button) {
        if ((getY() > ThunderGui.getInstance().main_posY + ThunderGui.getInstance().height) || getY() < ThunderGui.getInstance().main_posY) {
            return;
        }
        if (isHovered()) getParentSetting().getValue().setEnabled(!getParentSetting().getValue().isEnabled());
    }

    public Setting<BooleanSettingGroup> getParentSetting() {
        return parentSetting;
    }
}
