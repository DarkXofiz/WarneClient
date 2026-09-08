package warne.client.features.modules.combat;

import meteordevelopment.orbit.EventHandler;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.PlayerInteractItemC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;
import net.minecraft.util.Hand;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import warne.client.core.Managers;
import warne.client.events.impl.EventPostSync;
import warne.client.features.modules.Module;
import warne.client.setting.Setting;
import warne.client.utility.Timer;
import warne.client.utility.player.InventoryUtility;
import warne.client.utility.player.PlayerUtility;
import warne.client.utility.player.SearchInvResult;

/**
 * ElytraTarget — Elytra ile uçan hedefleri önceliklendirme ve takip modülü.
 *
 * <p>Aura modülünün {@code elytraTarget} ayarıyla entegre çalışır.
 * Elytra'da uçan bir oyuncu algılandığında:</p>
 * <ol>
 *   <li>Aura hedefini bu oyuncuya kilitler (Aura.target override).</li>
 *   <li>Hedefe orbit yaparak yaklaşır ve onu intercept eder.</li>
 *   <li>Firework rocket ile ivme sağlar.</li>
 *   <li>Packet crit ile her vuruda kritik hasar garantiler.</li>
 *   <li>En keskin kılıcı sessizce seçer.</li>
 * </ol>
 *
 * <p>Bypass notu: Bu modül yalnızca ElytraOverride aktifken ve Aura'nın
 * WallsBypass ayarı V3/V4 iken maksimum etkinliğe ulaşır.</p>
 */
public final class ElytraTarget extends Module {

    // ── ROCKET ──────────────────────────────────────────────────────────────
    private final Setting<Boolean>  rocketBoost      = new Setting<>("RocketBoost", true);
    private final Setting<Boolean>  instantFire      = new Setting<>("InstantFire", false,
            v -> rocketBoost.getValue());
    private final Setting<Integer>  rocketDelay      = new Setting<>("RocketDelay", 5, 0, 300,
            v -> rocketBoost.getValue() && !instantFire.getValue());
    private final Setting<Integer>  rocketBurst      = new Setting<>("RocketBurst", 2, 1, 10,
            v -> rocketBoost.getValue());
    private final Setting<Boolean>  silentRockets    = new Setting<>("SilentRocketUsage", true,
            v -> rocketBoost.getValue());
    private final Setting<Boolean>  autoSwitchRocket = new Setting<>("AutoSwitchRocket", true,
            v -> rocketBoost.getValue());
    private final Setting<Boolean>  alwaysBoost      = new Setting<>("AlwaysBoost", false,
            v -> rocketBoost.getValue());

    // ── HEDEF ────────────────────────────────────────────────────────────────
    private final Setting<Float>    targetRange      = new Setting<>("TargetRange", 64f, 5f, 128f);
    private final Setting<Boolean>  onlyWhenFlying   = new Setting<>("OnlyWhenFlying", true);
    private final Setting<Boolean>  onlyElytraTargets = new Setting<>("OnlyElytraTargets", true);
    private final Setting<Boolean>  overrideAuraTarget = new Setting<>("OverrideAuraTarget", true,
            v -> onlyElytraTargets.getValue());
    private final Setting<Boolean>  followTarget     = new Setting<>("FollowTarget", true);
    private final Setting<Float>    followSpeed      = new Setting<>("FollowSpeed", 0.8f, 0.1f, 3.0f,
            v -> followTarget.getValue());
    private final Setting<Float>    orbitRadius      = new Setting<>("OrbitRadius", 4.0f, 1.0f, 15.0f,
            v -> followTarget.getValue());
    private final Setting<Boolean>  interceptTarget  = new Setting<>("InterceptTarget", true,
            v -> followTarget.getValue());
    private final Setting<Float>    interceptLeadMult = new Setting<>("InterceptLeadMult", 1.0f, 0.2f, 3.0f,
            v -> interceptTarget.getValue() && followTarget.getValue());

    // ── KRİTİK VURUŞ ─────────────────────────────────────────────────────────
    private final Setting<Boolean>  autoCrit         = new Setting<>("AutoCrit", true);
    private final Setting<CritMode> critMode         = new Setting<>("CritMode", CritMode.Packet,
            v -> autoCrit.getValue());
    private final Setting<Integer>  critInterval     = new Setting<>("CritIntervalMs", 200, 50, 1000,
            v -> autoCrit.getValue());

    // ── KILIÇ ────────────────────────────────────────────────────────────────
    private final Setting<Boolean>  autoSharpestSword = new Setting<>("AutoSwitchToSharpestSword", true);

    private final Timer rocketTimer = new Timer();
    private final Timer critTimer   = new Timer();
    private double orbitAngle = 0;

    /** Modül tarafından seçilen elytra hedefi (null ise aktif değil). */
    private Entity elytraLockedTarget = null;

    public ElytraTarget() {
        super("ElytraTarget", Category.COMBAT);
    }

    @Override
    public void onEnable() {
        elytraLockedTarget = null;
        orbitAngle = 0;
        rocketTimer.reset();
        critTimer.reset();
    }

    @Override
    public void onDisable() {
        // Kilitlenen hedefi temizle; Aura kendi hedefini kendisi yönetir.
        elytraLockedTarget = null;
    }

    @EventHandler
    public void onPostSync(EventPostSync e) {
        if (mc.player == null || mc.world == null) return;

        // Biz elytra ile uçuyor muyuz?
        if (onlyWhenFlying.getValue() && !mc.player.isFallFlying()) return;

        // Elytra'da uçan en yakın düşman oyuncuyu bul
        if (onlyElytraTargets.getValue()) {
            elytraLockedTarget = findBestElytraTarget();
        } else {
            elytraLockedTarget = null;
        }

        // Aura hedefini override et
        Entity target;
        if (overrideAuraTarget.getValue() && elytraLockedTarget != null) {
            Aura.target = elytraLockedTarget;
            target = elytraLockedTarget;
        } else {
            target = Aura.target;
        }

        boolean hasValidTarget = target != null
                && PlayerUtility.squaredDistanceFromEyes(target.getPos())
                   < (targetRange.getValue() * targetRange.getValue());

        // Hedefe yörüngede yaklaş ve intercept et
        if (followTarget.getValue() && hasValidTarget) {
            followAndOrbit(target);
        }

        // En keskin kılıcı sessizce seç
        if (autoSharpestSword.getValue() && hasValidTarget) {
            SearchInvResult sword = InventoryUtility.getHighestSharpnessSwordHotBar();
            if (sword.found() && mc.player.getInventory().selectedSlot != sword.slot()) {
                sendPacket(new UpdateSelectedSlotC2SPacket(sword.slot()));
            }
        }

        // Kritik vuruş paketleri
        if (autoCrit.getValue() && hasValidTarget && critTimer.passedMs(critInterval.getValue())) {
            doCritPacket();
            critTimer.reset();
        }

        // Fişek boost
        if (rocketBoost.getValue()) {
            boolean shouldBoost = alwaysBoost.getValue() || hasValidTarget;
            if (shouldBoost) {
                if (instantFire.getValue() || rocketTimer.passedMs(rocketDelay.getValue())) {
                    for (int i = 0; i < rocketBurst.getValue(); i++) {
                        fireRocket();
                    }
                    rocketTimer.reset();
                }
            }
        }
    }

    /**
     * Dünyada elytra ile uçan, düşman olan ve menzil içindeki en yakın oyuncuyu döndürür.
     * Arkadaş listesindeki oyuncular atlanır.
     */
    private Entity findBestElytraTarget() {
        Entity best = null;
        double bestDist = Double.MAX_VALUE;

        for (PlayerEntity player : mc.world.getPlayers()) {
            if (player == mc.player) continue;
            if (Managers.FRIEND.isFriend(player)) continue;
            if (!player.isFallFlying()) continue;
            if (player.isDead() || !player.isAlive()) continue;

            double dist = PlayerUtility.squaredDistanceFromEyes(player.getPos());
            if (dist > targetRange.getValue() * targetRange.getValue()) continue;

            if (dist < bestDist) {
                bestDist = dist;
                best = player;
            }
        }
        return best;
    }

    /**
     * Hedefi orbit eder: hedef elytra ile kaçıyorsa önüne geçmeye çalışır (intercept).
     * Smooth (kademeli) yaw/pitch geçişi ile tespit riskini azaltır.
     */
    private void followAndOrbit(Entity target) {
        Vec3d targetPos    = target.getPos();
        Vec3d targetMotion = target.getVelocity();
        float radius = orbitRadius.getValue();
        float speed  = followSpeed.getValue();

        // Intercept: elytra ile uçan hedefin gelecekteki konumunu tahmin et
        Vec3d predictedPos = targetPos;
        if (interceptTarget.getValue()
                && target instanceof LivingEntity le
                && le.isFallFlying()) {
            double dist  = Math.sqrt(PlayerUtility.squaredDistanceFromEyes(targetPos));
            double ticks = dist / Math.max(0.1, speed * 2.0);
            predictedPos = targetPos.add(targetMotion.multiply(ticks * interceptLeadMult.getValue()));
        }

        // Sürekli yörünge açısı artışı (etrafında dön)
        orbitAngle += 0.04 * speed;

        double orbitX = predictedPos.x + Math.cos(orbitAngle) * radius;
        double orbitZ = predictedPos.z + Math.sin(orbitAngle) * radius;
        // Hedefin biraz üzerinden yaklaş — aşağıdan gelen saldırılar daha az engellenir
        double orbitY = predictedPos.y + 2.5;

        double dx = orbitX - mc.player.getX();
        double dy = orbitY - (mc.player.getY() + mc.player.getEyeHeight(mc.player.getPose()));
        double dz = orbitZ - mc.player.getZ();
        double horizontalDist = Math.sqrt(dx * dx + dz * dz);

        float yaw   = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90f;
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.max(0.001, horizontalDist)));

        // Smooth dönüş — ani atlama anti-cheat flagı oluşturur
        float currentYaw   = mc.player.getYaw();
        float currentPitch = mc.player.getPitch();
        float lerpFactor   = speed * 0.3f;
        float newYaw   = currentYaw   + MathHelper.wrapDegrees(yaw   - currentYaw)   * lerpFactor;
        float newPitch = MathHelper.clamp(
                currentPitch + MathHelper.wrapDegrees(pitch - currentPitch) * lerpFactor,
                -90f, 90f);

        mc.player.setYaw(newYaw);
        mc.player.setPitch(newPitch);
    }

    /**
     * Kritik vuruş paketleri gönderir.
     *
     * <ul>
     *   <li>Packet  — minimum miktar, çoğu Grim versiyonunda bypass eder.</li>
     *   <li>Strict  — daha büyük yükseklik delta'sı; katı anti-cheat'ler için.</li>
     * </ul>
     */
    private void doCritPacket() {
        if (mc.player.isInLava() || mc.player.isSubmergedInWater()) return;
        switch (critMode.getValue()) {
            case Packet -> {
                // Minimal delta — Grim'in fall-distance kontrolünü tetiklemez
                sendPacket(new PlayerMoveC2SPacket.PositionAndOnGround(
                        mc.player.getX(), mc.player.getY() + 0.000000271875, mc.player.getZ(), false));
                sendPacket(new PlayerMoveC2SPacket.PositionAndOnGround(
                        mc.player.getX(), mc.player.getY(), mc.player.getZ(), false));
            }
            case Strict -> {
                // Daha büyük delta — NCP ve Vulcan gibi strict anti-cheat'ler için
                sendPacket(new PlayerMoveC2SPacket.PositionAndOnGround(
                        mc.player.getX(), mc.player.getY() + 0.062600301692775, mc.player.getZ(), false));
                sendPacket(new PlayerMoveC2SPacket.PositionAndOnGround(
                        mc.player.getX(), mc.player.getY() + 0.07260029960661,  mc.player.getZ(), false));
                sendPacket(new PlayerMoveC2SPacket.PositionAndOnGround(
                        mc.player.getX(), mc.player.getY(), mc.player.getZ(), false));
            }
            case NoMove -> {
                // Hiç pozisyon paketi gönderme — sadece vurma paketi (Matrix için)
                // Bu mod Aura'nın GrimRayTrace ile birlikte kullanılmalıdır.
            }
        }
    }

    /**
     * Hotbar'dan fişek seçer ve kullanır.
     * {@code silentRockets} aktifken slot swap paket düzeyinde kalır, envanter ekranında görünmez.
     * {@code autoSwitchRocket} aktifken envanterden hotbar'a taşımayı dener.
     */
    private void fireRocket() {
        SearchInvResult rocketHotbar = InventoryUtility.findItemInHotBar(Items.FIREWORK_ROCKET);
        int rocketSlot = rocketHotbar.slot();

        if (rocketSlot == -1) {
            // Hotbar'da yok — envanterde ara
            if (!autoSwitchRocket.getValue()) return;
            // Envanter slotlarından hotbar'a sessiz taşıma yapılamaz (güvenli değil).
            // Kullanıcıyı bilgilendirmek için burada notification gönderilebilir.
            return;
        }

        int prevSlot   = mc.player.getInventory().selectedSlot;
        boolean doSwap = prevSlot != rocketSlot;

        if (silentRockets.getValue()) {
            // Paket düzeyinde slot değiştir — client envanterini değiştirme
            if (doSwap) sendPacket(new UpdateSelectedSlotC2SPacket(rocketSlot));
            sendSequencedPacket(id -> new PlayerInteractItemC2SPacket(
                    Hand.MAIN_HAND, id, mc.player.getYaw(), mc.player.getPitch()));
            if (doSwap) sendPacket(new UpdateSelectedSlotC2SPacket(prevSlot));
        } else {
            if (doSwap) InventoryUtility.switchTo(rocketSlot);
            sendSequencedPacket(id -> new PlayerInteractItemC2SPacket(
                    Hand.MAIN_HAND, id, mc.player.getYaw(), mc.player.getPitch()));
        }
    }

    public enum CritMode {
        /** Minimal paket, Grim uyumlu. */
        Packet,
        /** Büyük delta, Vulcan/NCP uyumlu. */
        Strict,
        /** Sadece vurma paketi, pozisyon paketi yok (Matrix). */
        NoMove
    }
}
