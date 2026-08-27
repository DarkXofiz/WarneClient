
package warne.client.features.modules.combat;

import meteordevelopment.orbit.EventHandler;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.Box;
import warne.client.core.Managers;
import warne.client.events.impl.EventPostSync;
import warne.client.features.modules.Module;
import warne.client.setting.Setting;

/**
 * ElytraTarget — Bytecode'dan uyarlanmış, tüm derleme hataları düzeltilmiş.
 * 
 * Düzeltmeler:
 * - GLOW_TEXTURE kaldırıldı (kullanılmıyordu)
 * - ModeSetting'ler Setting<Boolean>'a çevrildi (projede ModeSetting yok)
 * - Module constructor'dan description kaldırıldı
 * - Aura.INSTANCE yerine kendi target'ını buluyor (findTarget)
 * - isFriend() PlayerEntity cast ile düzeltildi
 */
public final class ElytraTarget extends Module {

    public static ElytraTarget INSTANCE;

    // Bytecode'daki sabitler (render için - kullanılmıyor ama korunuyor)
    private static final float BOX_GLOW_OUTER_THICKNESS = 0.17f;
    private static final float BOX_GLOW_MID_THICKNESS  = 0.13f;
    private static final float BOX_GLOW_CORE_THICKNESS = 0.11f;
    private static final float BOX_GLOW_LINE_U         = 0.4f;
    private static final int[][] BOX_EDGES = {
        {0, 1}, {1, 2}, {2, 3}, {3, 0},
        {4, 5}, {5, 6}, {6, 7}, {7, 4},
        {0, 4}, {1, 5}, {2, 6}, {3, 7}
    };

    // Bytecode'daki field'lar
    private Box smoothedPredictionBox;
    private LivingEntity smoothedTarget;

    // Bytecode'daki ayarlar (ModeSetting'ler Boolean'a çevrildi)
    public final Setting<Boolean> target = new Setting<>("Перегонять", false);

    public final Setting<Float> pursuitDistance = new Setting<>("Расстояние преследования",
            30.0f, 10.0f, 100.0f);

    // ModeSetting yerine Boolean (projede ModeSetting yok)
    public final Setting<Boolean> predictMode = new Setting<>("Режим предикта", true,
            v -> target.getValue());

    public final Setting<Boolean> predictCube = new Setting<>("Рисовать предикт", true,
            v -> target.getValue());

    public final Setting<Float> predictFillAlpha = new Setting<>("Прозрачность",
            40.0f, 0.0f, 255.0f,
            v -> predictCube.getValue());

    public final Setting<Boolean> predictFromTheme = new Setting<>("От темы", true,
            v -> predictCube.getValue());

    // ModeSetting yerine Boolean
    public final Setting<Boolean> predictBoxMode = new Setting<>("Вид квадрата", true,
            v -> predictCube.getValue());

    public final Setting<Float> forward = new Setting<>("Сила предикта",
            2.7f, 1.0f, 5.0f,
            v -> target.getValue());

    // Bytecode'daki private state field'ları
    private boolean disableForward = false;
    private long lastHurtTime = 0L;

    public ElytraTarget() {
        // Description kaldırıldı, sadece (name, category)
        super("ElytraTarget", Category.MOVEMENT);
        INSTANCE = this;
    }

    /**
     * Bytecode'daki onUpdate(EventUpdate) metodunun düzeltilmiş hâli.
     * Aura.INSTANCE yerine kendi target'ını buluyor.
     */
    @EventHandler
    public void onUpdate(EventPostSync event) {
        if (mc.player == null || mc.world == null) return;

        // Kendi target'ını bul (Aura bağımlılığı kaldırıldı)
        LivingEntity auraTarget = findTarget();

        if (auraTarget == null) {
            disableForward = true;
            smoothedPredictionBox = null;
            smoothedTarget = null;
            return;
        }

        // hurtTime > 0 ise forward'ı devre dışı bırak
        if (mc.player.hurtTime > 0) {
            disableForward = true;
            lastHurtTime = System.currentTimeMillis();
        }

        // 500ms geçtiyse forward'ı tekrar aktif et
        if (System.currentTimeMillis() - lastHurtTime >= 500L) {
            disableForward = false;
        }
    }

    /**
     * Kendi target'ını bul (Aura modülüne bağımlılık yok).
     * Bytecode'daki Aura.getTarget() mantığının yerini alıyor.
     */
    private LivingEntity findTarget() {
        LivingEntity bestTarget = null;
        float closestDistance = pursuitDistance.getValue();

        for (PlayerEntity player : mc.world.getPlayers()) {
            if (player == mc.player) continue;
            if (!player.isAlive()) continue;
            if (player.isSpectator()) continue;
            if (player.getHealth() <= 0f) continue;
            
            // Friend kontrolü (PlayerEntity cast ile düzeltildi)
            if (Managers.FRIEND.isFriend(player)) continue;

            // Bytecode'daki mantık: elytra ile uçan veya belirli durumda olan target
            if (!player.isFallFlying()) continue;

            float distance = (float) Math.sqrt(player.squaredDistanceTo(mc.player));
            if (distance < closestDistance) {
                bestTarget = player;
                closestDistance = distance;
            }
        }

        return bestTarget;
    }

    /**
     * Bytecode'daki isPursuitActive metodu.
     */
    public boolean isPursuitActive() {
        return isEnabled() && target.getValue() && !disableForward;
    }

    /**
     * Bytecode'da yarıda kesilen shouldTarget metodunun tamamlanmış hâli.
     * isFriend() PlayerEntity cast ile düzeltildi.
     */
    public boolean shouldTarget(LivingEntity entity) {
        if (!isPursuitActive()) return false;
        if (entity == null || !entity.isAlive()) return false;
        if (entity instanceof PlayerEntity && ((PlayerEntity) entity).isSpectator()) return false;
        if (entity.getHealth() <= 0f) return false;
        
        // Friend manager kontrolü (PlayerEntity cast ile düzeltildi)
        if (entity instanceof PlayerEntity) {
            if (Managers.FRIEND.isFriend((PlayerEntity) entity)) return false;
        }
        
        return true;
    }
}
