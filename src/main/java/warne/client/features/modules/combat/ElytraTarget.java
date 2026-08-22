package warne.client.features.modules.combat;

import meteordevelopment.orbit.EventHandler;
import net.minecraft.entity.Entity;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.PlayerInteractItemC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;
import net.minecraft.util.Hand;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import warne.client.events.impl.EventPostSync;
import warne.client.features.modules.Module;
import warne.client.setting.Setting;
import warne.client.utility.Timer;
import warne.client.utility.player.InventoryUtility;
import warne.client.utility.player.PlayerUtility;
import warne.client.utility.player.SearchInvResult;

public final class ElytraTarget extends Module {

    /*   ROCKET AYARLARI   */
    private final Setting<Boolean> rocketBoost       = new Setting<>("RocketBoost", true);
    private final Setting<Boolean> instantFire       = new Setting<>("InstantFire", false,
            v -> rocketBoost.getValue());
    private final Setting<Integer> rocketDelay       = new Setting<>("RocketDelay", 5, 0, 300,
            v -> rocketBoost.getValue() && !instantFire.getValue());
    private final Setting<Integer> rocketBurst       = new Setting<>("RocketBurst", 2, 1, 10,
            v -> rocketBoost.getValue());
    private final Setting<Boolean> silentRockets      = new Setting<>("SilentRocketUsage", true,
            v -> rocketBoost.getValue());
    private final Setting<Boolean> autoSwitchRocket  = new Setting<>("AutoSwitchRocket", true,
            v -> rocketBoost.getValue());
    private final Setting<Boolean> alwaysBoost       = new Setting<>("AlwaysBoost", false,
            v -> rocketBoost.getValue());

    /*   HEDEF AYARLARI   */
    private final Setting<Float>   targetRange       = new Setting<>("TargetRange", 64f, 5f, 128f);
    private final Setting<Boolean> onlyWhenFlying    = new Setting<>("OnlyWhenFlying", true);
    private final Setting<Boolean> followTarget      = new Setting<>("FollowTarget", true);
    private final Setting<Float>   followSpeed       = new Setting<>("FollowSpeed", 0.8f, 0.1f, 3.0f,
            v -> followTarget.getValue());
    private final Setting<Float>   orbitRadius       = new Setting<>("OrbitRadius", 4.0f, 1.0f, 15.0f,
            v -> followTarget.getValue());
    private final Setting<Boolean> interceptTarget   = new Setting<>("InterceptTarget", true,
            v -> followTarget.getValue());

    /*   KRİTİK VURUŞ   */
    private final Setting<Boolean> autoCrit          = new Setting<>("AutoCrit", true);
    private final Setting<CritMode> critMode         = new Setting<>("CritMode", CritMode.Packet,
            v -> autoCrit.getValue());

    /*   KILIC AYARLARI   */
    private final Setting<Boolean> autoSharpestSword = new Setting<>("AutoSwitchToSharpestSword", true);

    private final Timer rocketTimer = new Timer();
    private final Timer critTimer   = new Timer();
    private double orbitAngle = 0;

    public ElytraTarget() {
        super("ElytraTarget", Category.COMBAT);
    }

    @EventHandler
    public void onPostSync(EventPostSync e) {
        if (mc.player == null || mc.world == null) return;

        Entity target = Aura.target;
        boolean hasValidTarget = target != null
                && PlayerUtility.squaredDistanceFromEyes(target.getPos())
                   < (targetRange.getValue() * targetRange.getValue());

        // Elytra ile uçuyor muyuz?
        if (onlyWhenFlying.getValue() && !mc.player.isFallFlying()) return;

        // Uçuş sırasında hedefe doğru aktif yönlendirme
        if (followTarget.getValue() && hasValidTarget) {
            followAndOrbit(target);
        }

        // Kılıç seçimi: Aura hedef vuruyorsa en keskin kılıcı sessizce seç
        if (autoSharpestSword.getValue() && hasValidTarget) {
            SearchInvResult sword = InventoryUtility.getHighestSharpnessSwordHotBar();
            if (sword.found() && mc.player.getInventory().selectedSlot != sword.slot()) {
                sendPacket(new UpdateSelectedSlotC2SPacket(sword.slot()));
            }
        }

        // Kritik vuruş paketi (her saldırıdan önce)
        // NOT: elytra ile uçarken (isFallFlying) vanilla zaten kritik vermiyor,
        // üstelik bu paketler elytra fizik doğrulamasıyla çelişip sunucunun
        // pozisyonu geri düzeltmesine (takılma + düşme) sebep oluyordu.
        if (autoCrit.getValue() && hasValidTarget && !mc.player.isFallFlying() && critTimer.passedMs(200)) {
            doCritPacket();
            critTimer.reset();
        }

        // Fişek boost
        if (!rocketBoost.getValue()) return;
        boolean shouldBoost = alwaysBoost.getValue() || hasValidTarget;
        if (!shouldBoost) return;

        // InstantFire açıksa gecikmeyi tamamen atla (her tick ateşle)
        if (!instantFire.getValue() && !rocketTimer.passedMs(rocketDelay.getValue())) return;

        // Burst: ayarlanan sayıda fişek at
        for (int i = 0; i < rocketBurst.getValue(); i++) {
            fireRocket();
        }
        rocketTimer.reset();
    }

    /**
     * Hedefin etrafında yörüngede döner ve kaçıyorsa önüne geçmeye çalışır.
     */
    private void followAndOrbit(Entity target) {
        Vec3d targetPos   = target.getPos();
        Vec3d targetMotion = target.getVelocity();
        float radius       = orbitRadius.getValue();
        float speed        = followSpeed.getValue();

        // Hedef elytra ile uçuyorsa önüne geçmeye çalış (intercept)
        Vec3d predictedPos = targetPos;
        if (interceptTarget.getValue() && target instanceof net.minecraft.entity.LivingEntity le && le.isFallFlying()) {
            double dist = Math.sqrt(PlayerUtility.squaredDistanceFromEyes(targetPos));
            double ticks = dist / (speed * 2.0);
            predictedPos = targetPos.add(targetMotion.multiply(ticks));
        }

        // Yörünge açısını sürekli artır (etrafında dön)
        orbitAngle += 0.04 * speed;

        double orbitX = predictedPos.x + Math.cos(orbitAngle) * radius;
        double orbitZ = predictedPos.z + Math.sin(orbitAngle) * radius;
        double orbitY = predictedPos.y + 2.0; // biraz üstten

        // Hedefe bakış açılarını hesapla
        double dx = orbitX - mc.player.getX();
        double dy = orbitY - (mc.player.getY() + mc.player.getEyeHeight(mc.player.getPose()));
        double dz = orbitZ - mc.player.getZ();
        double horizontalDist = Math.sqrt(dx * dx + dz * dz);

        float yaw   = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90f;
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, horizontalDist));

        // Yavaş yavaş bak (smooth)
        float currentYaw   = mc.player.getYaw();
        float currentPitch = mc.player.getPitch();
        float newYaw   = currentYaw   + MathHelper.wrapDegrees(yaw   - currentYaw)   * speed * 0.3f;
        float newPitch = currentPitch + MathHelper.wrapDegrees(pitch - currentPitch) * speed * 0.3f;

        mc.player.setYaw(newYaw);
        mc.player.setPitch(newPitch);
    }

    /**
     * Kritik vuruş paketleri gönder (Criticals modülü gibi).
     */
    private void doCritPacket() {
        if (mc.player.isInLava() || mc.player.isSubmergedInWater()) return;
        switch (critMode.getValue()) {
            case Packet -> {
                sendPacket(new PlayerMoveC2SPacket.PositionAndOnGround(
                        mc.player.getX(), mc.player.getY() + 0.000000271875, mc.player.getZ(), false));
                sendPacket(new PlayerMoveC2SPacket.PositionAndOnGround(
                        mc.player.getX(), mc.player.getY(), mc.player.getZ(), false));
            }
            case Strict -> {
                sendPacket(new PlayerMoveC2SPacket.PositionAndOnGround(
                        mc.player.getX(), mc.player.getY() + 0.062600301692775, mc.player.getZ(), false));
                sendPacket(new PlayerMoveC2SPacket.PositionAndOnGround(
                        mc.player.getX(), mc.player.getY() + 0.07260029960661, mc.player.getZ(), false));
                sendPacket(new PlayerMoveC2SPacket.PositionAndOnGround(
                        mc.player.getX(), mc.player.getY(), mc.player.getZ(), false));
            }
        }
    }

    private void fireRocket() {
        SearchInvResult rocketHotbar = InventoryUtility.findItemInHotBar(Items.FIREWORK_ROCKET);
        int rocketSlot = rocketHotbar.slot();

        if (rocketSlot == -1) {
            if (!autoSwitchRocket.getValue()) return;
            SearchInvResult rocketAnywhere = InventoryUtility.findItemInInventory(Items.FIREWORK_ROCKET);
            if (!rocketAnywhere.found()) return;
            // Envanterden alınamaz (hotbar dışı güvenli değil), çık
            return;
        }

        int prevSlot    = mc.player.getInventory().selectedSlot;
        boolean needsSwap = prevSlot != rocketSlot;

        if (silentRockets.getValue()) {
            if (needsSwap) sendPacket(new UpdateSelectedSlotC2SPacket(rocketSlot));
            sendSequencedPacket(id -> new PlayerInteractItemC2SPacket(
                    Hand.MAIN_HAND, id, mc.player.getYaw(), mc.player.getPitch()));
            if (needsSwap) sendPacket(new UpdateSelectedSlotC2SPacket(prevSlot));
        } else {
            if (needsSwap) InventoryUtility.switchTo(rocketSlot);
            sendSequencedPacket(id -> new PlayerInteractItemC2SPacket(
                    Hand.MAIN_HAND, id, mc.player.getYaw(), mc.player.getPitch()));
        }
    }

    public enum CritMode {
        Packet, Strict
    }
}
