package warne.client.features.modules.combat;

import meteordevelopment.orbit.EventHandler;
import net.minecraft.entity.Entity;
import net.minecraft.item.Items;
import net.minecraft.network.packet.Packet;
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

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ThreadLocalRandom;

public final class ElytraTarget extends Module {

    public enum CritMode { Packet, Strict }
    public enum BypassMode { Off, GrimAC }

    /* ── ROCKET ─────────────────────────────────────────────────────────── */
    private final Setting<Boolean> rocketBoost      = new Setting<>("RocketBoost", true);
    private final Setting<Boolean> instantFire      = new Setting<>("InstantFire", true,
            v -> rocketBoost.getValue());
    private final Setting<Integer> rocketDelay      = new Setting<>("RocketDelay", 5, 0, 300,
            v -> rocketBoost.getValue() && !instantFire.getValue());
    private final Setting<Integer> rocketBurst      = new Setting<>("RocketBurst", 1, 1, 5,
            v -> rocketBoost.getValue());
    private final Setting<Boolean> silentRockets    = new Setting<>("SilentRocketUsage", true,
            v -> rocketBoost.getValue());
    private final Setting<Boolean> autoSwitchRocket = new Setting<>("AutoSwitchRocket", true,
            v -> rocketBoost.getValue());
    private final Setting<Boolean> alwaysBoost      = new Setting<>("AlwaysBoost", true,
            v -> rocketBoost.getValue());

    /* ── KILIČ + FİŞEK DÖNÜŞÜM ─────────────────────────────────────────── */
    /*
     * SwordRocketCycle: uçarken kılıç slotunda dur, her cycleInterval tick'te
     * bir fişeği silent ateşle (slot değişimi pakette kalır, görsel slotta değil).
     * Fişek ateşlendikten hemen sonra kılıç slotuna dönülür.
     * Bu şekilde Aura her zaman kılıçla vururken roket de boostı sağlar.
     */
    private final Setting<Boolean> swordRocketCycle    = new Setting<>("SwordRocketCycle", true);
    private final Setting<Integer> cycleInterval       = new Setting<>("CycleIntervalTicks", 20, 5, 100,
            v -> swordRocketCycle.getValue());

    /* ── FAKE LAG ────────────────────────────────────────────────────────── */
    private final Setting<Boolean> fakeLag     = new Setting<>("FakeLag", false);
    private final Setting<Integer> lagTicks    = new Setting<>("LagTicks", 8, 2, 20,
            v -> fakeLag.getValue());
    private final Setting<Integer> lagInterval = new Setting<>("LagInterval", 3, 1, 10,
            v -> fakeLag.getValue());

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
    private final Setting<BypassMode> bypassMode   = new Setting<>("BypassMode", BypassMode.GrimAC);
    private final Setting<Boolean>  rotationNoise  = new Setting<>("RotationNoise", true,
            v -> bypassMode.getValue() != BypassMode.Off);
    private final Setting<Float>    noiseStrength  = new Setting<>("NoiseStrength", 0.12f, 0.01f, 0.8f,
            v -> bypassMode.getValue() != BypassMode.Off && rotationNoise.getValue());
    private final Setting<Boolean>  sendFullPacket = new Setting<>("SendRotationPacket", true,
            v -> bypassMode.getValue() != BypassMode.Off);

    /* ── STATE ──────────────────────────────────────────────────────────── */
    private final Timer rocketTimer   = new Timer();
    private final Timer critTimer     = new Timer();
    private final Timer lagCycleTimer = new Timer();

    private final Deque<Packet<?>> lagQueue = new ArrayDeque<>();

    private double  orbitAngle  = 0;
    private float   smoothX     = 0, smoothY = 0, smoothZ = 0;
    private boolean orbitReady  = false;

    private boolean lagPhaseOn   = false;
    private int     lagTickAccum = 0;

    private float lastSentYaw   = Float.NaN;
    private float lastSentPitch = Float.NaN;

    private boolean firstTick = false;

    /* SwordRocketCycle state */
    private int  cycleTickAccum = 0;   // her tick artar
    private int  swordSlotCache = -1;  // kılıcın slotu, cycle sırasında hatırlanır

    public ElytraTarget() {
        super("ElytraTarget", Category.COMBAT);
    }

    /* ═══════════════════════════════════════════════════════════════════════
       LIFECYCLE
    ═══════════════════════════════════════════════════════════════════════ */

    @Override
    public void onEnable() {
        orbitReady      = false;
        orbitAngle      = 0;
        firstTick       = true;
        lagPhaseOn      = false;
        lagTickAccum    = 0;
        lastSentYaw     = Float.NaN;
        lastSentPitch   = Float.NaN;
        cycleTickAccum  = 0;
        swordSlotCache  = -1;
        lagQueue.clear();
        rocketTimer.reset();
        lagCycleTimer.reset();
    }

    @Override
    public void onDisable() {
        orbitReady   = false;
        firstTick    = false;
        lagPhaseOn   = false;
        lagTickAccum = 0;
        flushLagQueue();
    }

    /* ═══════════════════════════════════════════════════════════════════════
       ANA LOOP
    ═══════════════════════════════════════════════════════════════════════ */

    @EventHandler
    public void onPostSync(EventPostSync e) {
        if (mc.player == null || mc.world == null) return;

        tickFakeLag();

        Entity  target      = Aura.target;
        boolean validTarget = target != null
                && PlayerUtility.squaredDistanceFromEyes(target.getPos())
                   < targetRange.getValue() * (double) targetRange.getValue();

        boolean flying = mc.player.isFallFlying();

        if (onlyWhenFlying.getValue() && !flying) {
            orbitReady = false;
            return;
        }

        /* ── rotation + orbit ──────────────────────────────────────────── */
        if (followTarget.getValue() && validTarget) {
            followAndOrbit(target);
        } else {
            orbitReady = false;
        }

        /* ── kılıç-fişek dönüşüm ──────────────────────────────────────── */
        if (swordRocketCycle.getValue() && flying) {
            tickSwordRocketCycle();
        }

        /* ── kılıç seçimi (cycle kapalıysa normal çalış) ───────────────── */
        if (!swordRocketCycle.getValue() && autoSharpestSword.getValue() && validTarget) {
            SearchInvResult sword = InventoryUtility.getHighestSharpnessSwordHotBar();
            if (sword.found() && mc.player.getInventory().selectedSlot != sword.slot())
                queueOrSend(new UpdateSelectedSlotC2SPacket(sword.slot()));
        }

        /* ── kritik vuruş ──────────────────────────────────────────────── */
        if (autoCrit.getValue() && validTarget && critTimer.passedMs(200)) {
            doCritPacket();
            critTimer.reset();
        }

        /* ── ROCKET (cycle kapalıysa normal roket mantığı) ─────────────── */
        if (!swordRocketCycle.getValue()) {
            tickRocket(flying, validTarget);
        }
    }

    /* ═══════════════════════════════════════════════════════════════════════
       KILIČ-FİŞEK DÖNGÜSÜ
       Her cycleInterval tick'te bir fişek silent ateşler ve kılıca döner.
       Görsel slot değişmez; sunucuya gönderilen paketlerde geçici swap yapılır.
    ═══════════════════════════════════════════════════════════════════════ */

    private void tickSwordRocketCycle() {
        cycleTickAccum++;

        /* Her zaman kılıcı bul ve görsel slotu kılıçta tut */
        if (autoSharpestSword.getValue()) {
            SearchInvResult sword = InventoryUtility.getHighestSharpnessSwordHotBar();
            if (sword.found()) {
                swordSlotCache = sword.slot();
                /* Görsel slotu kılıca sabitle — paket + client */
                if (mc.player.getInventory().selectedSlot != sword.slot()) {
                    mc.player.getInventory().selectedSlot = sword.slot();
                    sendPacket(new UpdateSelectedSlotC2SPacket(sword.slot()));
                }
            }
        }

        /* cycleInterval tick dolduğunda fişek ateşle */
        if (cycleTickAccum < cycleInterval.getValue()) return;
        cycleTickAccum = 0;

        /* Fişek slotunu bul */
        SearchInvResult rocketResult = InventoryUtility.findItemInHotBar(Items.FIREWORK_ROCKET);
        int rocketSlot = rocketResult.slot();

        if (rocketSlot == -1) {
            if (!autoSwitchRocket.getValue()) return;
            /* Fişeği envanterden hotbar'a taşı */
            SearchInvResult rocketAnywhere = InventoryUtility.findItemInInventory(Items.FIREWORK_ROCKET);
            if (!rocketAnywhere.found() || rocketAnywhere.isInHotBar()) return;
            int emptySlot = -1;
            for (int i = 0; i < 9; i++) {
                if (mc.player.getInventory().getStack(i).isEmpty()) { emptySlot = i; break; }
            }
            if (emptySlot == -1) return;
            clickSlot(rocketAnywhere.slot(), emptySlot, SlotActionType.SWAP);
            rocketSlot = emptySlot;
        }

        int currentSlot = mc.player.getInventory().selectedSlot;

        /*
         * Silent swap: sunucuya fişek slotunu gönder, interact et, kılıca dön.
         * Client görsel slotu değişmez — Aura kılıçla vurmaya devam eder.
         */
        if (currentSlot != rocketSlot)
            sendPacket(new UpdateSelectedSlotC2SPacket(rocketSlot));

        sendSequencedPacket(id -> new PlayerInteractItemC2SPacket(
                Hand.MAIN_HAND, id, mc.player.getYaw(), mc.player.getPitch()));

        if (currentSlot != rocketSlot)
            sendPacket(new UpdateSelectedSlotC2SPacket(currentSlot));
    }

    /* ═══════════════════════════════════════════════════════════════════════
       ROCKET (cycle kapalıyken kullanılan normal yol)
    ═══════════════════════════════════════════════════════════════════════ */

    private void tickRocket(boolean flying, boolean validTarget) {
        if (!rocketBoost.getValue()) return;

        boolean shouldBoost = flying && (
                alwaysBoost.getValue()
                || validTarget
                || mc.player.getVelocity().y < -0.10
                || mc.player.getVelocity().length() < 0.40);

        if (!shouldBoost) return;

        boolean isFirst = firstTick;
        firstTick = false;

        if (!isFirst) {
            long delay = instantFire.getValue() ? 150L : (long)(int) rocketDelay.getValue();
            if (!rocketTimer.passedMs(delay)) return;
        }

        int burst = lagPhaseOn ? 1 : rocketBurst.getValue();
        for (int i = 0; i < burst; i++) fireRocket();
        rocketTimer.reset();
    }

    /* ═══════════════════════════════════════════════════════════════════════
       FAKE LAG
    ═══════════════════════════════════════════════════════════════════════ */

    private void tickFakeLag() {
        if (!fakeLag.getValue()) {
            if (!lagQueue.isEmpty()) flushLagQueue();
            lagPhaseOn   = false;
            lagTickAccum = 0;
            return;
        }

        if (!lagPhaseOn && lagCycleTimer.passedMs((long)(int) lagInterval.getValue() * 1000L)) {
            lagPhaseOn   = true;
            lagTickAccum = 0;
        }

        if (lagPhaseOn) {
            lagTickAccum++;
            if (lagTickAccum >= lagTicks.getValue()) {
                flushLagQueue();
                lagPhaseOn   = false;
                lagTickAccum = 0;
                lagCycleTimer.reset();
            }
        }
    }

    private void queueOrSend(Packet<?> packet) {
        if (fakeLag.getValue() && lagPhaseOn) {
            lagQueue.addLast(packet);
        } else {
            sendPacket(packet);
        }
    }

    private void flushLagQueue() {
        while (!lagQueue.isEmpty()) sendPacket(lagQueue.pollFirst());
    }

    /* ═══════════════════════════════════════════════════════════════════════
       ROTATION + ORBIT
    ═══════════════════════════════════════════════════════════════════════ */

    private void followAndOrbit(Entity target) {
        Vec3d tPos    = target.getPos();
        Vec3d tMotion = target.getVelocity();
        float radius  = orbitRadius.getValue();
        float speed   = followSpeed.getValue();

        Vec3d predicted = tPos;
        if (interceptTarget.getValue()
                && target instanceof net.minecraft.entity.LivingEntity le
                && le.isFallFlying()) {
            double dist  = Math.sqrt(PlayerUtility.squaredDistanceFromEyes(tPos));
            double ticks = Math.min(dist / Math.max(speed * 2.0, 0.1), 20.0);
            predicted = tPos.add(tMotion.multiply(ticks));
        }

        orbitAngle += 0.04 * speed;

        double rawX = predicted.x + Math.cos(orbitAngle) * radius;
        double rawZ = predicted.z + Math.sin(orbitAngle) * radius;
        double rawY = predicted.y + 2.0;

        if (!orbitReady) {
            smoothX    = (float) rawX;
            smoothY    = (float) rawY;
            smoothZ    = (float) rawZ;
            orbitReady = true;
        } else {
            float k = MathHelper.clamp(speed * 0.12f, 0.04f, 0.30f);
            smoothX += (rawX - smoothX) * k;
            smoothY += (rawY - smoothY) * k;
            smoothZ += (rawZ - smoothZ) * k;
        }

        double dx    = smoothX - mc.player.getX();
        double dy    = smoothY - (mc.player.getY() + mc.player.getEyeHeight(mc.player.getPose()));
        double dz    = smoothZ - mc.player.getZ();
        double hDist = Math.sqrt(dx * dx + dz * dz);
        if (hDist < 1e-4) return;

        float tYaw   = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90f;
        float tPitch = MathHelper.clamp((float) -Math.toDegrees(Math.atan2(dy, hDist)), -90f, 90f);

        float cYaw   = mc.player.getYaw();
        float cPitch = mc.player.getPitch();

        double sens = mc.options.getMouseSensitivity().getValue() * 0.6 + 0.2;
        double gcd  = Math.pow(sens, 3.0) * 1.2;

        float lerpT    = MathHelper.clamp(speed * 0.22f, 0.05f, 0.45f);
        float rawYaw   = cYaw   + MathHelper.wrapDegrees(tYaw   - cYaw)   * lerpT;
        float rawPitch = cPitch + MathHelper.wrapDegrees(tPitch - cPitch) * lerpT;
        rawPitch = MathHelper.clamp(rawPitch, -90f, 90f);

        float finalYaw, finalPitch;
        if (bypassMode.getValue() != BypassMode.Off) {
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

        mc.player.setYaw(finalYaw);
        mc.player.setPitch(finalPitch);

        if (bypassMode.getValue() != BypassMode.Off && sendFullPacket.getValue()) {
            float baseYaw   = Float.isNaN(lastSentYaw)   ? cYaw   : lastSentYaw;
            float basePitch = Float.isNaN(lastSentPitch) ? cPitch : lastSentPitch;

            float dYaw   = Math.abs(MathHelper.wrapDegrees(finalYaw   - baseYaw));
            float dPitch = Math.abs(MathHelper.wrapDegrees(finalPitch - basePitch));

            if (dYaw > 0.5f || dPitch > 0.3f) {
                queueOrSend(new PlayerMoveC2SPacket.Full(
                        mc.player.getX(), mc.player.getY(), mc.player.getZ(),
                        finalYaw, finalPitch,
                        mc.player.isOnGround()));
                lastSentYaw   = finalYaw;
                lastSentPitch = finalPitch;
            }
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
            if (swap) {
                mc.player.getInventory().selectedSlot = rocketSlot;
                sendPacket(new UpdateSelectedSlotC2SPacket(rocketSlot));
            }
            sendSequencedPacket(id -> new PlayerInteractItemC2SPacket(
                    Hand.MAIN_HAND, id, mc.player.getYaw(), mc.player.getPitch()));
            if (swap) {
                mc.player.getInventory().selectedSlot = prevSlot;
                sendPacket(new UpdateSelectedSlotC2SPacket(prevSlot));
            }
        }
    }
}
