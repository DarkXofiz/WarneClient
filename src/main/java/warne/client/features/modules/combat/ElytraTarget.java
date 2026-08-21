package warne.client.features.modules.combat;

import meteordevelopment.orbit.EventHandler;
import net.minecraft.entity.Entity;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.PlayerInteractItemC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;
import net.minecraft.screen.slot.SlotActionType;
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

import java.util.concurrent.ThreadLocalRandom;

public final class ElytraTarget extends Module {

    /* ── ROCKET ─────────────────────────────────────────────────────────── */
    private final Setting<Boolean> rocketBoost      = new Setting<>("RocketBoost", true);
    private final Setting<Boolean> instantFire      = new Setting<>("InstantFire", false,
            v -> rocketBoost.getValue());
    private final Setting<Integer> rocketDelay      = new Setting<>("RocketDelay", 5, 0, 300,
            v -> rocketBoost.getValue() && !instantFire.getValue());
    private final Setting<Integer> rocketBurst      = new Setting<>("RocketBurst", 2, 1, 10,
            v -> rocketBoost.getValue());
    private final Setting<Boolean> silentRockets    = new Setting<>("SilentRocketUsage", true,
            v -> rocketBoost.getValue());
    private final Setting<Boolean> autoSwitchRocket = new Setting<>("AutoSwitchRocket", true,
            v -> rocketBoost.getValue());
    private final Setting<Boolean> alwaysBoost      = new Setting<>("AlwaysBoost", false,
            v -> rocketBoost.getValue());

    /* ── HEDEF ──────────────────────────────────────────────────────────── */
    private final Setting<Float>   targetRange     = new Setting<>("TargetRange", 64f, 5f, 128f);
    private final Setting<Boolean> onlyWhenFlying  = new Setting<>("OnlyWhenFlying", true);
    private final Setting<Boolean> followTarget    = new Setting<>("FollowTarget", true);
    private final Setting<Float>   followSpeed     = new Setting<>("FollowSpeed", 0.8f, 0.1f, 3.0f,
            v -> followTarget.getValue());
    private final Setting<Float>   orbitRadius     = new Setting<>("OrbitRadius", 4.0f, 1.0f, 15.0f,
            v -> followTarget.getValue());
    private final Setting<Boolean> interceptTarget = new Setting<>("InterceptTarget", true,
            v -> followTarget.getValue());

    /* ── KRİTİK VURUŞ ───────────────────────────────────────────────────── */
    private final Setting<Boolean>  autoCrit = new Setting<>("AutoCrit", true);
    private final Setting<CritMode> critMode = new Setting<>("CritMode", CritMode.Packet,
            v -> autoCrit.getValue());

    /* ── KILIC ──────────────────────────────────────────────────────────── */
    private final Setting<Boolean> autoSharpestSword = new Setting<>("AutoSwitchToSharpestSword", true);

    /* ── BYPASS ─────────────────────────────────────────────────────────── */
    private final Setting<BypassMode> bypassMode      = new Setting<>("BypassMode", BypassMode.GrimAC);
    private final Setting<Boolean>    rotationNoise   = new Setting<>("RotationNoise", true,
            v -> bypassMode.getValue() != BypassMode.Off);
    private final Setting<Float>      noiseStrength   = new Setting<>("NoiseStrength", 0.12f, 0.01f, 0.8f,
            v -> bypassMode.getValue() != BypassMode.Off && rotationNoise.getValue());
    private final Setting<Boolean>    sendFullPacket  = new Setting<>("SendRotationPacket", true,
            v -> bypassMode.getValue() != BypassMode.Off);

    /* ── STATE ──────────────────────────────────────────────────────────── */
    private final Timer rocketTimer = new Timer();
    private final Timer critTimer   = new Timer();

    private double  orbitAngle = 0;
    private float   smoothX    = 0, smoothY = 0, smoothZ = 0;
    private boolean orbitReady = false;

    public ElytraTarget() {
        super("ElytraTarget", Category.COMBAT);
    }

    @Override
    public void onEnable() {
        orbitReady = false;
        orbitAngle = 0;
    }

    @Override
    public void onDisable() {
        orbitReady = false;
    }

    /* ═══════════════════════════════════════════════════════════════════════
       ANA LOOP
    ═══════════════════════════════════════════════════════════════════════ */

    @EventHandler
    public void onPostSync(EventPostSync e) {
        if (mc.player == null || mc.world == null) return;

        Entity  target      = Aura.target;
        boolean validTarget = target != null
                && PlayerUtility.squaredDistanceFromEyes(target.getPos())
                   < targetRange.getValue() * (double) targetRange.getValue();

        boolean flying = mc.player.isFallFlying();

        /* uçmuyorsa ve OnlyWhenFlying açıksa orbit state'ini temizle — takılma önlemi */
        if (onlyWhenFlying.getValue() && !flying) {
            orbitReady = false;
            return;
        }

        /* rotation + orbit */
        if (followTarget.getValue() && validTarget) {
            followAndOrbit(target);
        } else {
            /* hedef kayboldu: orbit sıfırla, bir sonraki hedefte geçiş pürüzsüz olsun */
            orbitReady = false;
        }

        /* kılıç seçimi */
        if (autoSharpestSword.getValue() && validTarget) {
            SearchInvResult sword = InventoryUtility.getHighestSharpnessSwordHotBar();
            if (sword.found() && mc.player.getInventory().selectedSlot != sword.slot())
                sendPacket(new UpdateSelectedSlotC2SPacket(sword.slot()));
        }

        /* kritik vuruş */
        if (autoCrit.getValue() && validTarget && critTimer.passedMs(200)) {
            doCritPacket();
            critTimer.reset();
        }

        /* fişek */
        if (!rocketBoost.getValue()) return;
        if (!alwaysBoost.getValue() && !validTarget) return;
        if (!instantFire.getValue() && !rocketTimer.passedMs(rocketDelay.getValue())) return;

        for (int i = 0; i < rocketBurst.getValue(); i++) fireRocket();
        rocketTimer.reset();
    }

    /* ═══════════════════════════════════════════════════════════════════════
       ROTATION + ORBIT — smooth EMA tabanlı, stutter yok
    ═══════════════════════════════════════════════════════════════════════ */

    private void followAndOrbit(Entity target) {
        Vec3d tPos    = target.getPos();
        Vec3d tMotion = target.getVelocity();
        float radius  = orbitRadius.getValue();
        float speed   = followSpeed.getValue();

        /* intercept: hedef de elytra ile uçuyorsa önüne geç */
        Vec3d predicted = tPos;
        if (interceptTarget.getValue()
                && target instanceof net.minecraft.entity.LivingEntity le
                && le.isFallFlying()) {
            double dist  = Math.sqrt(PlayerUtility.squaredDistanceFromEyes(tPos));
            double ticks = Math.min(dist / Math.max(speed * 2.0, 0.1), 20.0);
            predicted = tPos.add(tMotion.multiply(ticks));
        }

        orbitAngle += 0.04 * speed;

        /* ham orbit noktası */
        double rawX = predicted.x + Math.cos(orbitAngle) * radius;
        double rawZ = predicted.z + Math.sin(orbitAngle) * radius;
        double rawY = predicted.y + 2.0;

        /*
         * Exponential Moving Average — orbit merkezi sürekli sıçramak yerine
         * smooth kayar. k ne kadar küçükse o kadar yavaş/soft geçiş.
         */
        if (!orbitReady) {
            smoothX = (float) rawX;
            smoothY = (float) rawY;
            smoothZ = (float) rawZ;
            orbitReady = true;
        } else {
            float k = MathHelper.clamp(speed * 0.12f, 0.04f, 0.30f);
            smoothX += (rawX - smoothX) * k;
            smoothY += (rawY - smoothY) * k;
            smoothZ += (rawZ - smoothZ) * k;
        }

        /* yön vektörü */
        double dx    = smoothX - mc.player.getX();
        double dy    = smoothY - (mc.player.getY() + mc.player.getEyeHeight(mc.player.getPose()));
        double dz    = smoothZ - mc.player.getZ();
        double hDist = Math.sqrt(dx * dx + dz * dz);
        if (hDist < 1e-4) return; // çok yakın — işlem atla

        float tYaw   = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90f;
        float tPitch = MathHelper.clamp((float) -Math.toDegrees(Math.atan2(dy, hDist)), -90f, 90f);

        float cYaw   = mc.player.getYaw();
        float cPitch = mc.player.getPitch();

        /* GCD — mouse sensitivity ile tutarlı adım büyüklüğü */
        double sens  = mc.options.getMouseSensitivity().getValue() * 0.6 + 0.2;
        double gcd   = Math.pow(sens, 3.0) * 1.2;

        /* lerp hızı: speed arttıkça daha ani dönüş, ama çok sert olmasın */
        float lerpT    = MathHelper.clamp(speed * 0.22f, 0.05f, 0.45f);
        float rawYaw   = cYaw   + MathHelper.wrapDegrees(tYaw   - cYaw)   * lerpT;
        float rawPitch = cPitch + MathHelper.wrapDegrees(tPitch - cPitch) * lerpT;
        rawPitch = MathHelper.clamp(rawPitch, -90f, 90f);

        /* ── BYPASS: GCD align + human-like noise ─────────────────────── */
        float finalYaw, finalPitch;
        if (bypassMode.getValue() != BypassMode.Off) {
            /*
             * GrimAC / NCP / Polar hepsi "perfect" (tam tam tam) rotasyonu flag'ler.
             * GCD'ye hizalanmış rotation mouse hareketiyle aynı pattern'i üretir.
             */
            finalYaw   = (float)(rawYaw   - (rawYaw   - cYaw)   % gcd);
            finalPitch = (float)(rawPitch - (rawPitch - cPitch) % gcd);

            if (rotationNoise.getValue()) {
                float n  = noiseStrength.getValue();
                float yn = (float)(Math.round(
                        (ThreadLocalRandom.current().nextFloat() * n * 2f - n) / gcd) * gcd);
                float pn = (float)(Math.round(
                        (ThreadLocalRandom.current().nextFloat() * n - n * 0.5f) / gcd) * gcd);
                finalYaw   += yn;
                finalPitch  = MathHelper.clamp(finalPitch + pn, -90f, 90f);
            }
        } else {
            finalYaw   = rawYaw;
            finalPitch = rawPitch;
        }

        /* client-side rotation (ESP, clientLook vb. için) */
        mc.player.setYaw(finalYaw);
        mc.player.setPitch(finalPitch);

        /*
         * BYPASS — server'a Full movement paketi gönder.
         * Sıradan elytra uçuşunda sadece position/onGround gönderilir.
         * Burada rotation'ı da içeren Full paketi göndererek sunucunun
         * rotation track'ini biz kontrol ediyoruz — anti-cheat rotation
         * tutarsızlığını göremez.
         */
        if (bypassMode.getValue() != BypassMode.Off && sendFullPacket.getValue()) {
            sendPacket(new PlayerMoveC2SPacket.Full(
                    mc.player.getX(), mc.player.getY(), mc.player.getZ(),
                    finalYaw, finalPitch,
                    mc.player.isOnGround()));
        }
    }

    /* ═══════════════════════════════════════════════════════════════════════
       KRİTİK VURUŞ
    ═══════════════════════════════════════════════════════════════════════ */

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

    /* ═══════════════════════════════════════════════════════════════════════
       ROCKET
    ═══════════════════════════════════════════════════════════════════════ */

    private void fireRocket() {
        SearchInvResult rocketHotbar = InventoryUtility.findItemInHotBar(Items.FIREWORK_ROCKET);
        int rocketSlot = rocketHotbar.slot();

        if (rocketSlot == -1) {
            if (!autoSwitchRocket.getValue()) return;
            SearchInvResult rocketAnywhere = InventoryUtility.findItemInInventory(Items.FIREWORK_ROCKET);
            if (!rocketAnywhere.found() || rocketAnywhere.isInHotBar()) return;

            int emptyHotbarSlot = -1;
            for (int i = 0; i < 9; i++) {
                if (mc.player.getInventory().getStack(i).isEmpty()) {
                    emptyHotbarSlot = i;
                    break;
                }
            }
            if (emptyHotbarSlot == -1) return;

            clickSlot(rocketAnywhere.slot(), emptyHotbarSlot, SlotActionType.SWAP);
            rocketSlot = emptyHotbarSlot;
        }

        int     prevSlot = mc.player.getInventory().selectedSlot;
        boolean swap     = prevSlot != rocketSlot;

        if (silentRockets.getValue()) {
            if (swap) sendPacket(new UpdateSelectedSlotC2SPacket(rocketSlot));
            sendSequencedPacket(id -> new PlayerInteractItemC2SPacket(
                    Hand.MAIN_HAND, id, mc.player.getYaw(), mc.player.getPitch()));
            if (swap) sendPacket(new UpdateSelectedSlotC2SPacket(prevSlot));
        } else {
            if (swap) InventoryUtility.switchTo(rocketSlot);
            sendSequencedPacket(id -> new PlayerInteractItemC2SPacket(
                    Hand.MAIN_HAND, id, mc.player.getYaw(), mc.player.getPitch()));
        }
    }

    /* ═══════════════════════════════════════════════════════════════════════
       ENUM
    ═══════════════════════════════════════════════════════════════════════ */

    public enum CritMode   { Packet, Strict }

    public enum BypassMode {
        /** Bypass yok — vanilla davranış                        */ Off,
        /** GCD fix + noise + Full packet (GrimAC, Anticheat++)  */ GrimAC,
        /** GCD fix + timing offset (NoCheatPlus, Spartan)       */ NCP,
        /** GCD fix + geniş noise (Polar, Matrix)                */ Polar
    }
}
