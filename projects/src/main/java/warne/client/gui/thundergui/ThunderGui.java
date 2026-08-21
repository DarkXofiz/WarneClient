package warne.client.gui.thundergui;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;
import warne.client.WarneClient;
import warne.client.core.Managers;
import warne.client.core.manager.client.ConfigManager;
import warne.client.features.cmd.Command;
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
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

import static warne.client.features.modules.Module.mc;
import static warne.client.utility.render.animation.AnimationUtility.fast;

/**
 * WarneClient GUI – Tamamen yeniden yazılmış, kare, simetrik panel
 * Düzen: Sol kolon kategoriler | Orta kolon modüller | Sağ kolon ayarlar
 * Tüm köşeler kare (radius=0), 60-slot yükseklik (~510px), genişlik 480px
 */
public class ThunderGui extends Screen {
    // ── Boyutlar ──────────────────────────────────────────────────
    private static final int PANEL_W      = 480;  // Toplam panel genişliği
    private static final int PANEL_H      = 360;  // 60 slot × 6px ≈ 360px (kare)
    private static final int COL_CAT_W   = 90;   // Kategori sütunu genişliği
    private static final int COL_MOD_W   = 130;  // Modül sütunu genişliği
    private static final int COL_SET_W   = PANEL_W - COL_CAT_W - COL_MOD_W - 10; // Ayarlar sütunu
    private static final int HEADER_H    = 30;   // Başlık barı yüksekliği
    private static final int FOOTER_H    = 22;   // Alt bar yüksekliği

    // Renkler
    private static final Color C_BG          = new Color(18,  14,  22,  252); // Ana arka plan
    private static final Color C_HEADER      = new Color(28,  20,  36,  255); // Başlık
    private static final Color C_COL_CAT     = new Color(24,  18,  30,  255); // Kategori kolon
    private static final Color C_COL_MOD     = new Color(22,  16,  28,  255); // Modül kolon
    private static final Color C_COL_SET     = new Color(20,  14,  26,  255); // Ayarlar kolon
    private static final Color C_FOOTER      = new Color(14,  10,  18,  255); // Alt bar
    private static final Color C_SEPARATOR   = new Color(50,  35,  65,  180); // Ayırıcı çizgi
    private static final Color C_ACCENT1     = new Color(110, 40,  180, 255); // Vurgu rengi 1
    private static final Color C_ACCENT2     = new Color(60,  10,  130, 255); // Vurgu rengi 2
    private static final Color C_CAT_ACTIVE  = new Color(90,  30,  150, 200); // Aktif kategori
    private static final Color C_CAT_HOVER   = new Color(60,  20,  100, 140); // Hover kategori
    private static final Color C_TEXT_BRIGHT = new Color(240, 230, 255, 255);
    private static final Color C_TEXT_DIM    = new Color(140, 120, 160, 200);
    private static final Color C_MOD_ON_G1   = new Color(80,  20,  140, 210);
    private static final Color C_MOD_ON_G2   = new Color(40,   8,  90,  210);
    private static final Color C_SEARCH_BG   = new Color(30,  22,  40,  200);
    private static final Color C_INPUT_ACTIVE= new Color(70,  40, 110,  200);

    // ── Durum ─────────────────────────────────────────────────────
    public static CurrentMode currentMode  = CurrentMode.Modules;
    public static boolean     scroll_lock  = false;
    public static ModulePlate selected_plate, prev_selected_plate;
    public static EaseOutBack open_animation  = new EaseOutBack(5);
    public static boolean     open_direction  = false;
    private static ThunderGui INSTANCE;

    static { INSTANCE = new ThunderGui(); }

    // ── Bileşenler ────────────────────────────────────────────────
    public  final ArrayList<ModulePlate>              components = new ArrayList<>();
    public  final CopyOnWriteArrayList<CategoryPlate> categories = new CopyOnWriteArrayList<>();
    public  final ArrayList<SettingElement>           settings   = new ArrayList<>();
    public  final CopyOnWriteArrayList<ConfigComponent>  configs = new CopyOnWriteArrayList<>();
    public  final CopyOnWriteArrayList<FriendComponent>  friends = new CopyOnWriteArrayList<>();

    // ── Konum ─────────────────────────────────────────────────────
    public  int     main_posX    = 100;
    public  int     main_posY    = 80;
    // main_width/main_height artık sabit; ModulePlate uyumluluğu için:
    private final int main_width  = PANEL_W;
    private       int main_height = PANEL_H;

    // ── Dahili değişkenler ────────────────────────────────────────
    public  Module.Category current_category = Module.Category.COMBAT;
    public  Module.Category new_category     = Module.Category.COMBAT;
    float   category_animation = 1f;
    float   settings_animation = 1f;
    float   manager_animation  = 1f;
    int     prevCategoryY, CategoryY;
    private boolean dragging        = false;
    private int     drag_x, drag_y;
    private float   scroll          = 0;
    private boolean first_open      = true;
    private boolean searching       = false;
    private boolean listening_friend= false;
    private boolean listening_config= false;
    private String  search_string   = "Search";
    private String  config_string   = "Save config";
    private String  friend_string   = "Add friend";
    private CurrentMode prevMode    = CurrentMode.Modules;

    public static boolean mouse_state;
    public static int     mouse_x, mouse_y;

    // ── Kurucu ────────────────────────────────────────────────────
    public ThunderGui() {
        super(Text.of("WarneGui"));
        this.setInstance();
        this.load();
        CategoryY = getCategoryY(new_category);
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
    private void setInstance() { INSTANCE = this; }

    // ── Yükleme ───────────────────────────────────────────────────
    public void load() {
        categories.clear(); components.clear(); configs.clear(); friends.clear();

        // Modüller – sütun konumu: COL_CAT_W + 5 (kategoriden sonra)
        int module_y = 0;
        for (Module module : Managers.MODULE.getModulesByCategory(current_category)) {
            components.add(new ModulePlate(module,
                    main_posX + COL_CAT_W + 5,
                    main_posY + HEADER_H + 5 + module_y,
                    module_y / 22));
            module_y += 22;
        }
        // Kategoriler
        int category_y = 0;
        for (Module.Category cat : Managers.MODULE.getCategories()) {
            categories.add(new CategoryPlate(cat,
                    main_posX + 3,
                    main_posY + HEADER_H + 5 + category_y));
            category_y += 17;
        }
    }

    public void loadConfigs() {
        friends.clear(); configs.clear();
        new Thread(() -> {
            int y = 3;
            for (String file1 : Objects.requireNonNull(Managers.CONFIG.getConfigList())) {
                configs.add(new ConfigComponent(file1, ConfigManager.getConfigDate(file1),
                        main_posX + COL_CAT_W + 5, main_posY + HEADER_H + 5 + y, y / 22));
                y += 22;
            }
        }).start();
    }

    public void loadFriends() {
        configs.clear(); friends.clear();
        int y = 3;
        for (String friend : Managers.FRIEND.getFriends()) {
            friends.add(new FriendComponent(friend,
                    main_posX + COL_CAT_W + 5, main_posY + HEADER_H + 5 + y, y / 22));
            y += 22;
        }
    }

    // ── Render ────────────────────────────────────────────────────
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

    public void renderGui(DrawContext context, int mouseX, int mouseY, float partialTicks) {
        // Sürükleme
        if (dragging) {
            float dX = (mouseX - drag_x) - main_posX;
            float dY = (mouseY - drag_y) - main_posY;
            main_posX = mouseX - drag_x;
            main_posY = mouseY - drag_y;
            configs.forEach(c -> c.movePosition(dX, dY));
            friends.forEach(c -> c.movePosition(dX, dY));
            components.forEach(c -> c.movePosition(dX, dY));
            categories.forEach(c -> c.movePosition(dX, dY));
        }

        // Kategori geçiş animasyonu
        if (current_category != null && current_category != new_category) {
            prevCategoryY   = getCategoryY(current_category);
            CategoryY       = getCategoryY(new_category);
            current_category = new_category;
            category_animation = 1;
            scroll = 0;
            search_string = "Search";
            config_string = "Save config";
            friend_string = "Add friend";
            currentMode = CurrentMode.Modules;
            this.load();
        }

        manager_animation  = fast(manager_animation,  0, 15f);
        category_animation = fast(category_animation, 0, 15f);

        int px = main_posX, py = main_posY;
        int pw = main_width,  ph = main_height;

        // ── 1. Ana arka plan (kare) ────────────────────────────────
        Render2DEngine.drawRect(context.getMatrices(), px, py, pw, ph, C_BG);

        // ── 2. Başlık barı ─────────────────────────────────────────
        Render2DEngine.drawRect(context.getMatrices(), px, py, pw, HEADER_H, C_HEADER);
        // Gradient accent şeridi (üst kenar)
        Render2DEngine.draw2DGradientRect(context.getMatrices(),
                px, py, px + pw, py + 2,
                C_ACCENT1, C_ACCENT2, C_ACCENT1, C_ACCENT2);
        // Logo yazısı
        FontRenderers.thglitch.drawString(context.getMatrices(), "WARNE CLIENT",
                px + 6, py + 8, C_ACCENT1.getRGB());
        // Versiyon
        FontRenderers.settings.drawString(context.getMatrices(),
                "v" + WarneClient.VERSION,
                px + 6, py + 20, C_TEXT_DIM.getRGB());

        // ── 3. Kategori sütunu ─────────────────────────────────────
        int catX = px;
        int catY = py + HEADER_H;
        int catH = ph - HEADER_H - FOOTER_H;
        Render2DEngine.drawRect(context.getMatrices(), catX, catY, COL_CAT_W, catH, C_COL_CAT);
        // Aktif kategori vurgu çubuğu
        if (currentMode == CurrentMode.Modules) {
            float selY = first_open
                    ? CategoryY
                    : (float) Render2DEngine.interpolate(CategoryY, prevCategoryY, category_animation);
            Render2DEngine.draw2DGradientRect(context.getMatrices(),
                    catX, (int) selY, catX + COL_CAT_W, (int) selY + 17,
                    C_ACCENT1, C_ACCENT2, C_ACCENT1, C_ACCENT2);
        }
        // Kategori bileşenleri
        categories.forEach(cat -> cat.render(context.getMatrices(), mouseX, mouseY));

        // Ayırıcı – kategori | modül
        Render2DEngine.drawRect(context.getMatrices(),
                catX + COL_CAT_W, catY, 1, catH, C_SEPARATOR);

        // ── 4. Modül sütunu ────────────────────────────────────────
        int modX = catX + COL_CAT_W + 1;
        int modH = catH;
        Render2DEngine.drawRect(context.getMatrices(), modX, catY, COL_MOD_W, modH, C_COL_MOD);
        // Arama kutusu (modül sütunu üst kısmı)
        int searchBgX = modX + 2, searchBgY = catY + 2, searchBgW = COL_MOD_W - 4, searchBgH = 12;
        Render2DEngine.drawRect(context.getMatrices(), searchBgX, searchBgY, searchBgW, searchBgH,
                searching ? C_INPUT_ACTIVE : C_SEARCH_BG);
        FontRenderers.modules.drawString(context.getMatrices(),
                search_string.isEmpty() ? "Ara..." : search_string,
                searchBgX + 3, searchBgY + 3,
                searching ? C_TEXT_BRIGHT.getRGB() : C_TEXT_DIM.getRGB());
        // Modül listesi scissor
        Render2DEngine.addWindow(context.getMatrices(),
                modX, catY + 16, modX + COL_MOD_W, catY + modH, 1d);
        if (currentMode == CurrentMode.Modules)
            components.forEach(c -> c.render(context.getMatrices(), mouseX, mouseY));
        else {
            configs.forEach(c -> c.render(context, mouseX, mouseY));
            friends.forEach(c -> c.render(context, mouseX, mouseY));
        }
        Render2DEngine.popWindow();
        // Üst/alt fade gradyanı
        Render2DEngine.draw2DGradientRect(context.getMatrices(),
                modX, catY + 16, modX + COL_MOD_W, catY + 32,
                C_COL_MOD, new Color(C_COL_MOD.getRed(), C_COL_MOD.getGreen(), C_COL_MOD.getBlue(), 0),
                C_COL_MOD, new Color(C_COL_MOD.getRed(), C_COL_MOD.getGreen(), C_COL_MOD.getBlue(), 0));
        Render2DEngine.draw2DGradientRect(context.getMatrices(),
                modX, catY + modH - 16, modX + COL_MOD_W, catY + modH,
                new Color(C_COL_MOD.getRed(), C_COL_MOD.getGreen(), C_COL_MOD.getBlue(), 0), C_COL_MOD,
                new Color(C_COL_MOD.getRed(), C_COL_MOD.getGreen(), C_COL_MOD.getBlue(), 0), C_COL_MOD);
        // Ayırıcı – modül | ayarlar
        Render2DEngine.drawRect(context.getMatrices(),
                modX + COL_MOD_W, catY, 1, catH, C_SEPARATOR);

        // ── 5. Ayarlar sütunu ─────────────────────────────────────
        int setX = modX + COL_MOD_W + 1;
        int setW = pw - COL_CAT_W - COL_MOD_W - 2;
        Render2DEngine.drawRect(context.getMatrices(), setX, catY, setW, catH, C_COL_SET);

        // Seçili modül adı
        if (selected_plate != null) {
            String modName = selected_plate.getModule().getName();
            boolean modOn  = selected_plate.getModule().isOn();
            // Modül adı şeridi
            Render2DEngine.draw2DGradientRect(context.getMatrices(),
                    setX, catY, setX + setW, catY + 18,
                    modOn ? C_MOD_ON_G1 : new Color(35, 25, 48, 220),
                    modOn ? C_MOD_ON_G2 : new Color(25, 18, 35, 220),
                    modOn ? C_MOD_ON_G1 : new Color(35, 25, 48, 220),
                    modOn ? C_MOD_ON_G2 : new Color(25, 18, 35, 220));
            FontRenderers.modules.drawString(context.getMatrices(), modName,
                    setX + 4, catY + 6, C_TEXT_BRIGHT.getRGB());
            // Durum göstergesi (nokta)
            Render2DEngine.drawRect(context.getMatrices(),
                    setX + setW - 10, catY + 7, 4, 4,
                    modOn ? new Color(120, 255, 120) : new Color(180, 60, 60));
        } else {
            FontRenderers.modules.drawString(context.getMatrices(), "Modül Seç",
                    setX + 4, catY + 6, C_TEXT_DIM.getRGB());
        }

        // Ayarlar listesi (scissors)
        if (selected_plate != null && prev_selected_plate != selected_plate) {
            prev_selected_plate = selected_plate;
            settings_animation = 1;
            settings.clear();
            scroll = 0;
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

        settings_animation = fast(settings_animation, 0, 15f);

        int settingsAreaY = catY + 20;
        int settingsAreaH = catH - 20;
        Render2DEngine.addWindow(context.getMatrices(),
                setX, settingsAreaY, setX + setW, settingsAreaY + settingsAreaH, 1d);
        if (!settings.isEmpty()) {
            float offsetY = 0;
            for (SettingElement element : settings) {
                if (!element.isVisible()) continue;
                element.setOffsetY(offsetY);
                element.setX(setX + 2);
                element.setY(settingsAreaY + scroll);
                element.setWidth(setW - 4);
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
                element.render(context.getMatrices(), mouseX, mouseY, partialTicks);
                offsetY += element.getHeight() + 3f;
            }
        }
        Render2DEngine.popWindow();

        // ── 6. Alt bar (footer) ────────────────────────────────────
        int footerY = py + ph - FOOTER_H;
        Render2DEngine.drawRect(context.getMatrices(), px, footerY, pw, FOOTER_H, C_FOOTER);
        // Üst kenar çizgi
        Render2DEngine.drawRect(context.getMatrices(), px, footerY, pw, 1, C_SEPARATOR);

        // Cfg yöneticisi butonu
        boolean cfgActive = currentMode == CurrentMode.CfgManager;
        Color cfgBg = cfgActive ? C_CAT_ACTIVE : C_CAT_HOVER;
        Render2DEngine.drawRect(context.getMatrices(), px + 4, footerY + 3, 58, 16, cfgBg);
        FontRenderers.modules.drawCenteredString(context.getMatrices(), "CFG",
                px + 4 + 29, footerY + 8, (cfgActive ? C_TEXT_BRIGHT : C_TEXT_DIM).getRGB());

        // Friend yöneticisi butonu
        boolean friendActive = currentMode == CurrentMode.FriendManager;
        Color friendBg = friendActive ? C_CAT_ACTIVE : C_CAT_HOVER;
        Render2DEngine.drawRect(context.getMatrices(), px + 66, footerY + 3, 58, 16, friendBg);
        FontRenderers.modules.drawCenteredString(context.getMatrices(), "FRIENDS",
                px + 66 + 29, footerY + 8, (friendActive ? C_TEXT_BRIGHT : C_TEXT_DIM).getRGB());

        // Sağ: config adı
        String cfgLabel = "cfg: " + Managers.CONFIG.currentConfig.getName();
        FontRenderers.settings.drawString(context.getMatrices(), cfgLabel,
                px + pw - FontRenderers.settings.getStringWidth(cfgLabel) - 6,
                footerY + 8, C_TEXT_DIM.getRGB());

        // Cfg/friend metin girişi
        if (currentMode == CurrentMode.CfgManager && listening_config) {
            Render2DEngine.drawRect(context.getMatrices(), px + 130, footerY + 3, 160, 16, C_INPUT_ACTIVE);
            FontRenderers.modules.drawString(context.getMatrices(),
                    config_string.isEmpty() ? "Config adı..." : config_string,
                    px + 133, footerY + 8, C_TEXT_BRIGHT.getRGB());
        }
        if (currentMode == CurrentMode.FriendManager && listening_friend) {
            Render2DEngine.drawRect(context.getMatrices(), px + 130, footerY + 3, 160, 16, C_INPUT_ACTIVE);
            FontRenderers.modules.drawString(context.getMatrices(),
                    friend_string.isEmpty() ? "Oyuncu adı..." : friend_string,
                    px + 133, footerY + 8, C_TEXT_BRIGHT.getRGB());
        }

        // Sağ kenar vurgu şeridi (dikey)
        Render2DEngine.draw2DGradientRect(context.getMatrices(),
                px + pw - 2, py, px + pw, py + ph,
                C_ACCENT2, C_ACCENT2, C_ACCENT1, C_ACCENT1);

        // Sol kenar vurgu şeridi
        Render2DEngine.draw2DGradientRect(context.getMatrices(),
                px, py, px + 2, py + ph,
                C_ACCENT1, C_ACCENT1, C_ACCENT2, C_ACCENT2);

        if (first_open) first_open = false;
    }

    // ── Yardımcılar ───────────────────────────────────────────────
    private int getCategoryY(Module.Category category) {
        for (CategoryPlate cp : categories)
            if (cp.getCategory() == category) return cp.getPosY();
        return 0;
    }

    public void onTick() {
        open_animation.update(open_direction);
        components.forEach(ModulePlate::onTick);
        settings.forEach(SettingElement::onTick);
        configs.forEach(ConfigComponent::onTick);
        friends.forEach(FriendComponent::onTick);
    }

    public boolean isHoveringItem(float x, float y, float w, float h, float mx, float my) {
        return mx >= x && my >= y && mx <= x + w && my <= y + h;
    }

    // ── Mouse ─────────────────────────────────────────────────────
    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int clickedButton) {
        mouse_state = true;
        float mx = (float) mouseX, my = (float) mouseY;
        int px = main_posX, py = main_posY;
        int footerY = py + main_height - FOOTER_H;

        // Footer butonları
        if (isHoveringItem(px + 4, footerY + 3, 58, 16, mx, my)) {
            if (currentMode != CurrentMode.CfgManager) {
                currentMode = CurrentMode.CfgManager;
                settings.clear(); components.clear();
                loadConfigs();
            } else listening_config = true;
        }
        if (isHoveringItem(px + 66, footerY + 3, 58, 16, mx, my)) {
            if (currentMode != CurrentMode.FriendManager) {
                currentMode = CurrentMode.FriendManager;
                settings.clear(); components.clear();
                loadFriends();
            } else listening_friend = true;
        }
        // Cfg kaydet butonu (footer'da giriş yapılıyorsa enter gibi davranır – sol tık)
        if (currentMode == CurrentMode.CfgManager && listening_config
                && isHoveringItem(px + 130, footerY + 3, 160, 16, mx, my)) {
            WarneClient.currentKeyListener = WarneClient.KeyListening.ThunderGui;
        }
        if (currentMode == CurrentMode.FriendManager && listening_friend
                && isHoveringItem(px + 130, footerY + 3, 160, 16, mx, my)) {
            WarneClient.currentKeyListener = WarneClient.KeyListening.ThunderGui;
        }

        // Başlık barı → sürükle
        if (isHoveringItem(px, py, main_width, HEADER_H, mx, my)) {
            drag_x = (int) (mouseX - px);
            drag_y = (int) (mouseY - py);
            dragging = true;
        }

        // Arama kutusu
        int modX = px + COL_CAT_W + 1;
        int searchBgY = py + HEADER_H + 2;
        if (isHoveringItem(modX + 2, searchBgY, COL_MOD_W - 4, 12, mx, my)
                && currentMode == CurrentMode.Modules) {
            searching = true;
            WarneClient.currentKeyListener = WarneClient.KeyListening.ThunderGui;
        }

        settings.forEach(c -> c.mouseClicked((int) mouseX, (int) mouseY, clickedButton));
        components.forEach(c -> c.mouseClicked((int) mouseX, (int) mouseY, clickedButton));
        categories.forEach(c -> c.mouseClicked((int) mouseX, (int) mouseY, 0));
        configs.forEach(c -> c.mouseClicked((int) mouseX, (int) mouseY, clickedButton));
        friends.forEach(c -> c.mouseClicked((int) mouseX, (int) mouseY, clickedButton));
        return super.mouseClicked(mouseX, mouseY, clickedButton);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        mouse_state = false;
        dragging = false;
        settings.forEach(e -> e.mouseReleased((int) mouseX, (int) mouseY, button));
        return super.mouseReleased(mouseX, mouseY, button);
    }

    // ── Klavye ────────────────────────────────────────────────────
    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        try { keyTyped(GLFW.glfwGetKeyName(keyCode, scanCode), keyCode); }
        catch (IOException ignored) {}
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) { super.keyPressed(keyCode, scanCode, modifiers); return true; }
        return false;
    }

    public void keyTyped(String typedChar, int keyCode) throws IOException {
        if (WarneClient.currentKeyListener != WarneClient.KeyListening.Sliders
                && WarneClient.currentKeyListener != WarneClient.KeyListening.ThunderGui) return;
        if (keyCode == 1) { open_direction = false; searching = false; }
        settings.forEach(e -> e.keyTyped(typedChar, keyCode));
        components.forEach(c -> c.keyTyped(typedChar, keyCode));

        if (searching) {
            if (keyCode == GLFW.GLFW_KEY_LEFT_SHIFT || keyCode == GLFW.GLFW_KEY_RIGHT_SHIFT) return;
            components.clear();
            if (search_string.equalsIgnoreCase("search")) search_string = "";
            int module_y = 0;
            for (Module m : Managers.MODULE.getModulesSearch(search_string)) {
                ModulePlate mp = new ModulePlate(m, main_posX + COL_CAT_W + 5, main_posY + HEADER_H + 16 + module_y, module_y / 22);
                if (!components.contains(mp)) components.add(mp);
                module_y += 22;
            }
            if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) { search_string = "Search"; searching = false; return; }
            if (keyCode == GLFW.GLFW_KEY_BACKSPACE) { search_string = removeLastChar(search_string); return; }
            if ((keyCode >= GLFW.GLFW_KEY_A && keyCode <= GLFW.GLFW_KEY_Z)
                    || (keyCode >= GLFW.GLFW_KEY_0 && keyCode <= GLFW.GLFW_KEY_9))
                search_string += typedChar;
        }
        if (listening_config) {
            if (config_string.equalsIgnoreCase("Save config")) config_string = "";
            switch (keyCode) {
                case GLFW.GLFW_KEY_ESCAPE -> { config_string = "Save config"; listening_config = false; return; }
                case GLFW.GLFW_KEY_BACKSPACE -> { config_string = removeLastChar(config_string); return; }
                case GLFW.GLFW_KEY_ENTER -> {
                    if (!config_string.isEmpty() && !config_string.equals("Save config")) {
                        Managers.CONFIG.save(config_string); config_string = "Save config"; listening_config = false; loadConfigs();
                    } return;
                }
            }
            config_string += typedChar;
        }
        if (listening_friend) {
            if (friend_string.equalsIgnoreCase("Add friend")) friend_string = "";
            switch (keyCode) {
                case GLFW.GLFW_KEY_ESCAPE -> { friend_string = "Add friend"; listening_friend = false; return; }
                case GLFW.GLFW_KEY_BACKSPACE -> { friend_string = removeLastChar(friend_string); return; }
                case GLFW.GLFW_KEY_ENTER -> {
                    if (!friend_string.isEmpty()) {
                        Managers.FRIEND.addFriend(friend_string); friend_string = "Add friend"; listening_friend = false; loadFriends();
                    } return;
                }
            }
            friend_string += typedChar;
        }
    }

    // ── Scroll ────────────────────────────────────────────────────
    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double hA, double vA) {
        float dWheel = (int) (vA * 10D);
        settings.forEach(c -> c.checkMouseWheel(dWheel));
        if (scroll_lock) { scroll_lock = false; return super.mouseScrolled(mouseX, mouseY, hA, vA); }
        int setX = main_posX + COL_CAT_W + COL_MOD_W + 2;
        if (isHoveringItem(setX, main_posY + HEADER_H, COL_SET_W, main_height - HEADER_H - FOOTER_H, (float) mouseX, (float) mouseY))
            scroll += dWheel * WarneClientGui.scrollSpeed.getValue();
        else {
            components.forEach(c -> c.scrollElement(dWheel * WarneClientGui.scrollSpeed.getValue()));
        }
        configs.forEach(c -> c.scrollElement(dWheel * WarneClientGui.scrollSpeed.getValue()));
        friends.forEach(c -> c.scrollElement(dWheel * WarneClientGui.scrollSpeed.getValue()));
        return super.mouseScrolled(mouseX, mouseY, hA, vA);
    }

    // ── Enum ──────────────────────────────────────────────────────
    public enum CurrentMode { Modules, CfgManager, FriendManager, WayPointManager, MacroManager }
}
