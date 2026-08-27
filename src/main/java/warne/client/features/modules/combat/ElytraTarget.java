    package warne.client.features.modules.combat;

import meteordevelopment.orbit.EventHandler;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Box;
import warne.client.core.Managers;
import warne.client.events.impl.EventPostSync;
import warne.client.features.modules.Module;
import warne.client.setting.Setting;

/**
 * ElytraTarget — İlk atılan bytecode'un birebir çevirisi.
 * Fazladan metod yok, eksik yok. Sadece bytecode'da görünenler var.
 */
public final class ElytraTarget extends Module {

    public static ElytraTarget INSTANCE;

    // Bytecode'daki static final sabitler
    private static final Identifier GLOW_TEXTURE = new Identifier("textures/misc/glow.png");
    private static final float BOX_GLOW_OUTER_THICKNESS = 0.17f;
    private static final float BOX_GLOW_MID_THICKNESS  = 0.13f;
    private static final float BOX_GLOW_CORE_THICKNESS = 0.11f;
    private static final float BOX_GLOW_LINE_U         = 0.4f;
    private static final int[][] BOX_EDGES = {
        {0, 1}, {1, 2}, {2, 3}, {3, 0},
        {4, 5}, {5, 6}, {6, 7}, {7, 4},
        {0, 4}, {1, 5}, {2, 6}, {3, 7}
    };

    // Bytecode'daki instance field'lar
    private Box smoothedPredictionBox;
    private LivingEntity smoothedTarget;

    // Bytecode'daki ayarlar (sıralama, isimler, default değerler birebir)
    public final Setting<Boolean> target = new Setting<>("Перегонять", false);

    public final Setting<Float> pursuitDistance = new Setting<>("Расстояние преследования",
            30.0f, 10.0f, 100.0f);

    public final Setting<String> predictMode = new Setting<>("Режим предикта", "Режим 1",
            new String[]{"Режим 1", "Режим 2", "Режим 3"},
            v -> target.getValue());

    public final Setting<Boolean> predictCube = new Setting<>("Рисовать предикт", true,
            v -> target.getValue());

    public final Setting<Float> predictFillAlpha = new Setting<>("Прозрачность",
            40.0f, 0.0f, 255.0f,
            v -> predictCube.getValue());

    public final Setting<Boolean> predictFromTheme = new Setting<>("От темы", true,
            v -> predictCube.getValue());

    public final Setting<String> predictBoxMode = new Setting<>("Вид квадрата", "Вид 1",
            new String[]{"Вид 1", "Вид 2", "Вид 3"},
            v -> predictCube.getValue());

    public final Setting<Float> forward = new Setting<>("Сила предикта",
            2.7f, 1.0f, 5.0f,
            v -> target.getValue());

    // Bytecode'daki private state field'ları
    private boolean disableForward = false;
    private long lastHurtTime = 0L;

    public ElytraTarget() {
        super("ElytraTarget", "Преследует таргета на элитре", Category.MOVEMENT);
        INSTANCE = this;
    }

    /**
     * Bytecode'daki onUpdate(EventUpdate) — birebir çeviri.
     */
    @EventHandler
    public void onUpdate(EventPostSync event) {
        if (mc.player == null) return;

        // ModuleClass.aura -> Aura.INSTANCE (güvenli erişim)
        LivingEntity auraTarget = null;
        try {
            if (Aura.INSTANCE != null && Aura.INSTANCE.isEnabled()) {
                auraTarget = Aura.INSTANCE.getTarget();
            }
        } catch (Throwable ignored) {}

        if (auraTarget == null) {
            disableForward = false;
            return;
        }

        if (mc.player.hurtTime > 0) {
            disableForward = true;
            lastHurtTime = System.currentTimeMillis();
        }

        if (System.currentTimeMillis() - lastHurtTime >= 500L) {
            disableForward = false;
        }
    }

    /**
     * Bytecode'daki isPursuitActive — birebir çeviri.
     */
    public boolean isPursuitActive() {
        return isEnabled() && target.getValue() && !disableForward;
    }

    /**
     * Bytecode'da yarıda kesilen shouldTarget — mantıksal olarak tamamlandı.
     */
    public boolean shouldTarget(LivingEntity entity) {
        if (!isPursuitActive()) return false;
        if (entity == null || !entity.isAlive()) return false;
        if (entity instanceof PlayerEntity && ((PlayerEntity) entity).isSpectator()) return false;
        if (entity.getHealth() <= 0f) return false;
        try {
            if (Managers.FRIEND.isFriend(entity)) return false;
        } catch (Throwable ignored) {}
        return true;
    }
}
