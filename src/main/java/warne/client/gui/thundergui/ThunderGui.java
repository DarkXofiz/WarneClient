package warne.client.gui.thundergui;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;
import warne.client.WarneClient;
import warne.client.core.Managers;
import warne.client.core.manager.client.ConfigManager;
import warne.client.features.modules.Module;
import warne.client.features.modules.client.WarneClientGui;
import warne.client.gui.font.FontRenderers;
import warne.client.gui.thundergui.components.*;
import warne.client.setting.Setting;
import warne.client.setting.impl.BooleanSettingGroup;
import warne.client.setting.impl.ColorSetting;
import warne.client.setting.impl.SettingGroup;
import warne.client.utility.render.Render2DEngine;
import warne.client.utility.render.animation.EaseOutBack;

import java.awt.*;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

import static warne.client.features.modules.Module.mc;

/**
 * ThunderGui — Sütun bazlı layout.
 *
 * Her kategori ekranda yatay olarak yan yana bir sütundur.
 * Sütun başlığı = kategori adı, altında o kategorinin modülleri sıralanır.
 * Modüle sağ tık → sağ panelde ayarlar açılır.
 * Sol tık → toggle.
 *
 * Görsel referans: görseldeki ClickGUI düzeni.
 */
public class ThunderGui extends Screen {

    /* ── Boyutlar ────────────────────────────────────────────────── */
    private static final int COL_W        = 160;  // Her kategori sütununun genişliği
    private static final int COL_GAP      = 6;    // Sütunlar arası boşluk
    private static final int HEADER_H     = 22;   // Kategori başlığı yüksekliği
    private static final int MODULE_H     = 18;   // Her modül satırı yüksekliği
    private static final int MODULE_PAD   = 2;    // Modüller arası boşluk
    private static final int PANEL_PAD_X  = 10;   // Sol kenar boşluğu
    private static final int PANEL_PAD_Y  = 10;   // Üst kenar boşluğu
    private static final int SETTINGS_W   = 200;  // Ayarlar paneli genişliği
    private static final int SETTINGS_PAD = 8;    // Ayarlar paneli ile sütunlar arası boşluk

    /* ── Renkler ─────────────────────────────────────────────────── */
    private static final Color C_BG          = new Color(20, 16, 26, 230);
    private static final Color C_COL_BG      = new Color(28, 22, 36, 220);
    private static final Color C_COL_HEADER  = new Color(38, 28, 52, 255);
    private static final Color C_ACCENT1     = new Color(120, 50, 200, 255);
    private static final Color C_ACCENT2     = new Color(60, 15, 140, 255);
    private static final Color C_MOD_HOVER   = new Color(50, 35, 70, 180);
    private static final Color C_MOD_ON_1    = new Color(90, 25, 160, 210);
    private static final Color C_MOD_ON_2    = new Color(45, 10, 100, 210);
    private static final Color C_TEXT_BRIGHT = new Color(240, 230, 255, 255);
    private static final Color C_TEXT_DIM    = new Color(150, 130, 175, 200);
    private static final Color C_SEPARATOR   = new Color(60, 40, 80, 160);
    private static final Color C_SETTINGS_BG = new Color(24, 18, 32, 240);
    private static final Color C_SETTINGS_HDR= new Color(35, 25, 50, 255);

    /* ── Durum ───────────────────────────────────────────────────── */
    public static EaseOutBack open_animation = new EaseOutBack(5);
    public static boolean     open_direction  = false;
    public static boolean     scroll_lock     = false;
    public static boolean     mouse_state;
    public static int         mouse_x, mouse_y;

    private static ThunderGui INSTANCE;
    static { INSTANCE = new ThunderGui(); }

    /* ── Bileşenler ──────────────────────────────────────────────── */
    // Her kategori için modül listesi (sütun bazlı)
    private final List<ColumnData>            columns    = new ArrayList<>();
    public  final ArrayList<SettingElement>   settings   = new ArrayList<>();
    public  final CopyOnWriteArrayList<ConfigComponent>  configs  = new CopyOnWriteArrayList<>();
    public  final CopyOnWriteArrayList<FriendComponent>  friends  = new CopyOnWriteArrayList<>();

    // Seçili modül (ayarlar paneli için)
    public static ModulePlate selected_plate;
    public static ModulePlate prev_selected_plate;

    /* ── Konum / sürükleme ───────────────────────────────────────── */
    public int main_posX = 20;
    public int main_posY = 20;
    // ThunderGui'nin eski kodla uyumlu alanları
    public final int main_width  = 0; // artık dinamik
    public       int main_height = 0;
    public       int height      = 4000; // scroll sınırı için büyük değer

    private boolean dragging  = false;
    private int     drag_x, drag_y;

    /* ── Mod ─────────────────────────────────────────────────────── */
    public enum CurrentMode { Modules, CfgManager, FriendManager }
    public static CurrentMode currentMode = CurrentMode.Modules;

    /* ════════════════════════════════════════════════════════════════
       KURUCU
    ════════════════════════════════════════════════════════════════ */

    public ThunderGui() {
        super(Text.of("WarneGui"));
        INSTANCE = this;
        load();
    }

    @Override public boolean shouldPause() { return false; }

    public static ThunderGui getInstance() {
        if (INSTANCE == null) INSTANCE = new ThunderGui();
        return INSTANCE;
    }

    public static ThunderGui getThunderGui() {
        open_animation = new EaseOutBack();
        open_direction = true;
        return getInstance();
    }

    public static String removeLastChar(String str) {
        return (str != null && str.length() > 0) ? str.substring(0, str.length() - 1) : "";
    }

    /* ════════════════════════════════════════════════════════════════
       YÜKLEME
    ════════════════════════════════════════════════════════════════ */

    /**
     * Her kategori için bir ColumnData oluşturur.
     * ColumnData: kategori + o kategorideki ModulePlate listesi.
     */
    public void load() {
        columns.clear();
        Module.Category[] cats = Managers.MODULE.getCategories().toArray(new Module.Category[0]);
        for (int i = 0; i < cats.length; i++) {
            Module.Category cat = cats[i];
            int colX = main_posX + PANEL_PAD_X + i * (COL_W + COL_GAP);
            int colY = main_posY + PANEL_PAD_Y;

            List<ModulePlate> plates = new ArrayList<>();
            int moduleY = colY + HEADER_H + MODULE_PAD;
            int idx = 0;
            for (Module module : Managers.MODULE.getModulesByCategory(cat)) {
                plates.add(new ModulePlate(module, colX + 4, moduleY + idx * (MODULE_H + MODULE_PAD), idx));
                idx++;
            }
            columns.add(new ColumnData(cat, colX, colY, plates, i));
        }
    }

    public void loadConfigs() {
        configs.clear();
        new Thread(() -> {
            int y = 0;
            for (String file1 : Objects.requireNonNull(Managers.CONFIG.getConfigList())) {
                configs.add(new ConfigComponent(file1, ConfigManager.getConfigDate(file1),
                        main_posX, main_posY + y, y / 22));
                y += 22;
            }
        }).start();
    }

    public void loadFriends() {
        friends.clear();
        int y = 0;
        for (String friend : Managers.FRIEND.getFriends()) {
            friends.add(new FriendComponent(friend, main_posX, main_posY + y, y / 22));
            y += 22;
        }
    }

    /* ════════════════════════════════════════════════════════════════
       RENDER
    ════════════════════════════════════════════════════════════════ */

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        if (Module.fullNullCheck()) renderBackground(context, mouseX, mouseY, delta);
        context.getMatrices().push();
        mouse_x = mouseX; mouse_y = mouseY;
        if (open_animation.getAnimationd() > 0) renderGui(context, mouseX, mouseY, delta);
        if (open_animation.getAnimationd() <= 0.01 && !open_direction) {
            open_animation = new EaseOutBack();
            mc.currentScreen = null;
            mc.setScreen(null);
        }
        context.getMatrices().pop();
    }

    public void renderGui(DrawContext context, int mouseX, int mouseY, float delta) {
        /* Sürükleme */
        if (dragging) {
            float dX = (mouseX - drag_x) - main_posX;
            float dY = (mouseY - drag_y) - main_posY;
            main_posX = mouseX - drag_x;
            main_posY = mouseY - drag_y;
            for (ColumnData col : columns) col.move(dX, dY);
            configs.forEach(c -> c.movePosition(dX, dY));
            friends.forEach(c -> c.movePosition(dX, dY));
        }

        int catCount = columns.size();
        if (catCount == 0) return;

        /* Toplam panel genişliği */
        int totalW = catCount * COL_W + (catCount - 1) * COL_GAP + PANEL_PAD_X * 2;
        /* Ayarlar paneli ekliyse daha geniş */
        int settingsPanelW = (selected_plate != null) ? SETTINGS_PAD + SETTINGS_W : 0;

        /* En uzun sütun yüksekliği */
        int maxModules = columns.stream().mapToInt(c -> c.plates.size()).max().orElse(0);
        int colContentH = HEADER_H + maxModules * (MODULE_H + MODULE_PAD) + MODULE_PAD;
        int panelH = colContentH + PANEL_PAD_Y * 2;

        int px = main_posX, py = main_posY;

        /* ── Ana arka plan ──────────────────────────────────────── */
        Render2DEngine.drawRect(context.getMatrices(),
                px, py, totalW + settingsPanelW, panelH, C_BG);

        /* Üst gradient şerit */
        Render2DEngine.draw2DGradientRect(context.getMatrices(),
                px, py, px + totalW + settingsPanelW, py + 2,
                C_ACCENT1, C_ACCENT2, C_ACCENT1, C_ACCENT2);

        /* ── Sütunlar ────────────────────────────────────────────── */
        for (int i = 0; i < columns.size(); i++) {
            ColumnData col = columns.get(i);
            int cx = main_posX + PANEL_PAD_X + i * (COL_W + COL_GAP);
            int cy = main_posY + PANEL_PAD_Y;
            int ch = colContentH;

            /* Sütun arka planı */
            Render2DEngine.drawRect(context.getMatrices(), cx, cy, COL_W, ch, C_COL_BG);

            /* Kategori başlığı */
            Render2DEngine.draw2DGradientRect(context.getMatrices(),
                    cx, cy, cx + COL_W, cy + HEADER_H,
                    C_COL_HEADER, C_COL_HEADER, C_ACCENT2, C_ACCENT2);

            String catName = col.category.getName();
            int nameW = FontRenderers.modules.getStringWidth(catName);
            FontRenderers.modules.drawString(context.getMatrices(), catName,
                    cx + COL_W / 2 - nameW / 2,
                    cy + HEADER_H / 2 - 4,
                    C_TEXT_BRIGHT.getRGB());

            /* Alt kenar çizgisi (başlık ayırıcı) */
            Render2DEngine.drawRect(context.getMatrices(),
                    cx, cy + HEADER_H - 1, COL_W, 1, C_ACCENT1);

            /* Modüller */
            Render2DEngine.addWindow(context.getMatrices(),
                    cx, cy + HEADER_H, cx + COL_W, cy + ch, 1d);

            int mY = cy + HEADER_H + MODULE_PAD;
            for (ModulePlate plate : col.plates) {
                boolean on      = plate.getModule().isOn();
                boolean hovered = isHover(mouseX, mouseY, cx + 2, mY, COL_W - 4, MODULE_H);

                /* Modül arka planı */
                if (on) {
                    Render2DEngine.draw2DGradientRect(context.getMatrices(),
                            cx + 2, mY, cx + COL_W - 2, mY + MODULE_H,
                            C_MOD_ON_1, C_MOD_ON_2, C_MOD_ON_1, C_MOD_ON_2);
                } else if (hovered || plate == selected_plate) {
                    Render2DEngine.drawRect(context.getMatrices(),
                            cx + 2, mY, COL_W - 4, MODULE_H, C_MOD_HOVER);
                }

                /* Seçili göstergesi — sol kenar çubuğu */
                if (plate == selected_plate) {
                    Render2DEngine.draw2DGradientRect(context.getMatrices(),
                            cx + 2, mY, cx + 4, mY + MODULE_H,
                            C_ACCENT1, C_ACCENT1, C_ACCENT2, C_ACCENT2);
                }

                /* Modül adı */
                Color textColor = on ? C_TEXT_BRIGHT : (hovered ? C_TEXT_BRIGHT : C_TEXT_DIM);
                FontRenderers.sf_medium.drawString(context.getMatrices(),
                        plate.getModule().getName(),
                        cx + 7, mY + MODULE_H / 2 - 4,
                        textColor.getRGB());

                /* Bind etiketi */
                String bind = plate.getModule().getBind().getBind();
                if (!bind.equals("None")) {
                    String shortBind = shortenBind(bind);
                    FontRenderers.settings.drawString(context.getMatrices(), shortBind,
                            cx + COL_W - FontRenderers.settings.getStringWidth(shortBind) - 4,
                            mY + MODULE_H / 2 - 3,
                            C_TEXT_DIM.getRGB());
                }

                mY += MODULE_H + MODULE_PAD;
            }

            Render2DEngine.popWindow();

            /* Sütun sağ ayırıcı */
            if (i < columns.size() - 1) {
                Render2DEngine.drawRect(context.getMatrices(),
                        cx + COL_W, cy, 1, ch, C_SEPARATOR);
            }
        }

        /* ── Ayarlar paneli ──────────────────────────────────────── */
        if (selected_plate != null) {
            /* Seçili modül değiştiyse ayarları yeniden yükle */
            if (prev_selected_plate != selected_plate) {
                prev_selected_plate = selected_plate;
                settings.clear();
                for (Setting<?> setting : selected_plate.getModule().getSettings()) {
                    if (setting.getValue() instanceof SettingGroup)
                        settings.add(new ParentComponent(setting));
                    if (setting.getValue() instanceof Boolean
                            && !setting.getName().equals("Enabled")
                            && !setting.getName().equals("Drawn"))
                        settings.add(new BooleanComponent(setting));
                    if (setting.getValue() instanceof BooleanSettingGroup)
                        settings.add(new BooleanParentComponent(setting));
                    if (setting.getValue().getClass().isEnum())
                        settings.add(new ModeComponent(setting));
                    if (setting.getValue() instanceof ColorSetting)
                        settings.add(new ColorPickerComponent(setting));
                    if (setting.isNumberSetting() && setting.hasRestriction())
                        settings.add(new SliderComponent(setting));
                }
            }

            int sx = main_posX + PANEL_PAD_X
                    + columns.size() * (COL_W + COL_GAP) - COL_GAP
                    + SETTINGS_PAD;
            int sy = main_posY + PANEL_PAD_Y;
            int sh = colContentH;

            /* Panel arka planı */
            Render2DEngine.drawRect(context.getMatrices(), sx, sy, SETTINGS_W, sh, C_SETTINGS_BG);

            /* Başlık */
            boolean modOn = selected_plate.getModule().isOn();
            Render2DEngine.draw2DGradientRect(context.getMatrices(),
                    sx, sy, sx + SETTINGS_W, sy + HEADER_H,
                    modOn ? C_MOD_ON_1 : C_SETTINGS_HDR,
                    modOn ? C_MOD_ON_2 : C_SETTINGS_HDR,
                    modOn ? C_MOD_ON_1 : C_SETTINGS_HDR,
                    modOn ? C_MOD_ON_2 : C_SETTINGS_HDR);

            FontRenderers.modules.drawString(context.getMatrices(),
                    selected_plate.getModule().getName(),
                    sx + 6, sy + HEADER_H / 2 - 4, C_TEXT_BRIGHT.getRGB());

            /* Durum noktası */
            Render2DEngine.drawRect(context.getMatrices(),
                    sx + SETTINGS_W - 10, sy + HEADER_H / 2 - 2, 5, 5,
                    modOn ? new Color(100, 255, 100) : new Color(200, 60, 60));

            /* Alt kenar çizgisi */
            Render2DEngine.drawRect(context.getMatrices(),
                    sx, sy + HEADER_H - 1, SETTINGS_W, 1, C_ACCENT1);

            /* Ayar listesi */
            int settingsAreaY = sy + HEADER_H + 4;
            int settingsAreaH = sh - HEADER_H - 4;
            Render2DEngine.addWindow(context.getMatrices(),
                    sx, settingsAreaY, sx + SETTINGS_W, settingsAreaY + settingsAreaH, 1d);

            float offsetY = 0;
            for (SettingElement element : settings) {
                if (!element.isVisible()) continue;
                element.setOffsetY(offsetY);
                element.setX(sx + 4);
                element.setY(settingsAreaY);
                element.setWidth(SETTINGS_W - 8);
                element.setHeight(15);
                if (element instanceof ColorPickerComponent && ((ColorPickerComponent) element).isOpen())
                    element.setHeight(56);
                if (element instanceof ModeComponent comp) {
                    comp.setWHeight(15);
                    if (comp.isOpen()) {
                        offsetY += comp.getSetting().getModes().length * 6;
                        element.setHeight(element.getHeight() + comp.getSetting().getModes().length * 6 + 3);
                    } else element.setHeight(15);
                }
                element.render(context.getMatrices(), mouseX, mouseY, delta);
                offsetY += element.getHeight() + 3f;
            }

            Render2DEngine.popWindow();

            /* Sol kenar accent çizgisi */
            Render2DEngine.draw2DGradientRect(context.getMatrices(),
                    sx, sy, sx + 2, sy + sh,
                    C_ACCENT1, C_ACCENT1, C_ACCENT2, C_ACCENT2);
        }

        /* Alt gradient şerit */
        int bottomY = main_posY + panelH - 2;
        Render2DEngine.draw2DGradientRect(context.getMatrices(),
                px, bottomY, px + totalW + settingsPanelW, bottomY + 2,
                C_ACCENT2, C_ACCENT1, C_ACCENT2, C_ACCENT1);
    }

    /* ════════════════════════════════════════════════════════════════
       MOUSE
    ════════════════════════════════════════════════════════════════ */

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        mouse_state = true;
        float mx = (float) mouseX, my = (float) mouseY;

        /* Başlık sürükleme — üst şerit */
        if (isHover(mx, my, main_posX, main_posY, getTotalWidth(), PANEL_PAD_Y + HEADER_H)) {
            drag_x = (int)(mouseX - main_posX);
            drag_y = (int)(mouseY - main_posY);
            dragging = true;
        }

        /* Modüller */
        for (int i = 0; i < columns.size(); i++) {
            ColumnData col = columns.get(i);
            int cx = main_posX + PANEL_PAD_X + i * (COL_W + COL_GAP);
            int mY = main_posY + PANEL_PAD_Y + HEADER_H + MODULE_PAD;
            for (ModulePlate plate : col.plates) {
                if (isHover(mx, my, cx + 2, mY, COL_W - 4, MODULE_H)) {
                    if (button == 0) plate.getModule().toggle();
                    if (button == 1) {
                        if (selected_plate == plate) {
                            selected_plate = null;
                            settings.clear();
                        } else {
                            selected_plate = plate;
                        }
                    }
                }
                mY += MODULE_H + MODULE_PAD;
            }
        }

        /* Ayarlar paneli + plate bind handler */
        settings.forEach(c -> c.mouseClicked((int) mouseX, (int) mouseY, button));
        configs.forEach(c -> c.mouseClicked((int) mouseX, (int) mouseY, button));
        friends.forEach(c -> c.mouseClicked((int) mouseX, (int) mouseY, button));
        for (ColumnData col : columns)
            col.plates.forEach(p -> p.mouseClicked((int) mouseX, (int) mouseY, button));

        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        mouse_state = false;
        dragging    = false;
        settings.forEach(e -> e.mouseReleased((int) mouseX, (int) mouseY, button));
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double hA, double vA) {
        float dWheel = (float)(vA * 10.0);
        settings.forEach(c -> c.checkMouseWheel(dWheel));
        configs.forEach(c -> c.scrollElement(dWheel * WarneClientGui.scrollSpeed.getValue()));
        friends.forEach(c -> c.scrollElement(dWheel * WarneClientGui.scrollSpeed.getValue()));
        return super.mouseScrolled(mouseX, mouseY, hA, vA);
    }

    /* ════════════════════════════════════════════════════════════════
       KLAVYE
    ════════════════════════════════════════════════════════════════ */

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        try { keyTyped(GLFW.glfwGetKeyName(keyCode, scanCode), keyCode); }
        catch (IOException ignored) {}
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            super.keyPressed(keyCode, scanCode, modifiers);
            return true;
        }
        return false;
    }

    public void keyTyped(String typedChar, int keyCode) throws IOException {
        if (WarneClient.currentKeyListener != WarneClient.KeyListening.Sliders
                && WarneClient.currentKeyListener != WarneClient.KeyListening.ThunderGui) return;
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) open_direction = false;
        settings.forEach(e -> e.keyTyped(typedChar, keyCode));
        for (ColumnData col : columns)
            col.plates.forEach(p -> p.keyTyped(typedChar, keyCode));
    }

    /* ════════════════════════════════════════════════════════════════
       TICK
    ════════════════════════════════════════════════════════════════ */

    public void onTick() {
        open_animation.update(open_direction);
        for (ColumnData col : columns) col.plates.forEach(ModulePlate::onTick);
        settings.forEach(SettingElement::onTick);
        configs.forEach(ConfigComponent::onTick);
        friends.forEach(FriendComponent::onTick);
    }

    /* ════════════════════════════════════════════════════════════════
       YARDIMCILAR
    ════════════════════════════════════════════════════════════════ */

    private int getTotalWidth() {
        if (columns.isEmpty()) return PANEL_PAD_X * 2;
        return columns.size() * (COL_W + COL_GAP) - COL_GAP + PANEL_PAD_X * 2;
    }

    private boolean isHover(float mx, float my, float x, float y, float w, float h) {
        return mx >= x && my >= y && mx <= x + w && my <= y + h;
    }

    public boolean isHoveringItem(float x, float y, float w, float h, float mx, float my) {
        return isHover(mx, my, x, y, w, h);
    }

    private String shortenBind(String bind) {
        return switch (bind) {
            case "LEFT_CONTROL"  -> "LCtrl";
            case "RIGHT_CONTROL" -> "RCtrl";
            case "LEFT_SHIFT"    -> "LShift";
            case "RIGHT_SHIFT"   -> "RShift";
            case "LEFT_ALT"      -> "LAlt";
            case "RIGHT_ALT"     -> "RAlt";
            default -> bind;
        };
    }

    // ── Eski ThunderGui uyumluluğu için ────────────────────────────
    // (CategoryPlate kodunun çağırdığı alanlar boş bırakıldı — sütun
    //  sistemi kategorileri doğrudan yönetiyor)
    public final ArrayList<ModulePlate>              components = new ArrayList<>();
    public final CopyOnWriteArrayList<CategoryPlate> categories = new CopyOnWriteArrayList<>();
    public Module.Category current_category = null;
    public Module.Category new_category     = null;

    /* ════════════════════════════════════════════════════════════════
       COLUMN DATA (iç sınıf)
    ════════════════════════════════════════════════════════════════ */

    private static class ColumnData {
        final Module.Category  category;
        int                    x, y;
        final List<ModulePlate> plates;
        final int              index;

        ColumnData(Module.Category category, int x, int y, List<ModulePlate> plates, int index) {
            this.category = category;
            this.x        = x;
            this.y        = y;
            this.plates   = plates;
            this.index    = index;
        }

        void move(float dx, float dy) {
            x += dx; y += dy;
            plates.forEach(p -> p.movePosition(dx, dy));
        }
    }
}
