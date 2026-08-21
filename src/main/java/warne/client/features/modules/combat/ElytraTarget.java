package warne.client.features.modules.combat;

import meteordevelopment.orbit.EventHandler;
import net.minecraft.entity.Entity;
import net.minecraft.item.Items;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.c2s.play.HandSwingC2SPacket;
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

    /* ── ENUM ────────────────────────────────────────────────────────────── */

    public enum BypassMode {
        Off,
        GrimAC,   // GCD hizalama + gürültü
        Vulcan,   // Rotasyonu ≤N °/tick ile sınırla, zemin tick spoof
        Matrix,   // Saldırı stagger + pozisyon öteleme gürültüsü
        Intave    // Nadir paket + cinematic yumuşatma
    }

    public enum RotationAlgorithm {
        Linear,     // Saf lerp
        Bezier,     // İkinci derece Bezier eğrisi — doğal kavis
        Cinematic   // Çok yavaş EMA — Intave / uzun mesafe için
    }

    public enum CritMode { Packet, Strict }

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

    /* ── FAKE LAG ────────────────────────────────────────────────────────── */
    private final Setting<Boolean> fakeLag     = new Setting<>("FakeLag", false);
    private final Setting<Integer> lagTicks    = new Setting<>("LagTicks",    8, 2, 20,
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

    /* ── BYPASS TEMEL ───────────────────────────────────────────────────── */
    private final Setting<BypassMode>        bypassMode    = new Setting<>("BypassMode", BypassMode.GrimAC);
    private final Setting<RotationAlgorithm> rotAlgo       = new Setting<>("RotationAlgorithm", RotationAlgorithm.Bezier,
            v -> bypassMode.getValue() != BypassMode.Off);
    private final Setting<Boolean>           rotationNoise = new Setting<>("RotationNoise", true,
            v -> bypassMode.getValue() != BypassMode.Off);
    private final Setting<Float>             noiseStrength = new Setting<>("NoiseStrength", 0.12f, 0.01f, 0.8f,
            v -> bypassMode.getValue() != BypassMode.Off && rotationNoise.getValue());
    private final Setting<Boolean>           sendFullPacket = new Setting<>("SendRotationPacket", true,
            v -> bypassMode.getValue() != BypassMode.Off);

    /* ── BYPASS GELİŞMİŞ ────────────────────────────────────────────────── */

    // Bezier
    private final Setting<Float> bezierOffset = new Setting<>(
            "BezierControlOffset", 0.35f, 0.0f, 1.0f,
            v -> bypassMode.getValue() != BypassMode.Off
                    && rotAlgo.getValue() == RotationAlgorithm.Bezier);

    // Cinematic EMA katsayısı
    private final Setting<Float> cinematicAlpha = new Setting<>(
            "CinematicAlpha", 0.06f, 0.01f, 0.20f,
            v -> bypassMode.getValue() != BypassMode.Off
                    && rotAlgo.getValue() == RotationAlgorithm.Cinematic);

    // Vulcan rotasyon hız sınırı (°/tick)
    private final Setting<Float> vulcanMaxRotSpeed = new Setting<>("VulcanMaxRotSpeed", 3.0f, 0.5f, 15.0f,
            v -> bypassMode.getValue() == BypassMode.Vulcan);

    // Matrix saldırı stagger (tick)
    private final Setting<Integer> attackStagger = new Setting<>("AttackStaggerTicks", 3, 1, 8,
            v -> bypassMode.getValue() == BypassMode.Matrix);

    // Anti-flag cooldown
    private final Setting<Boolean> antiFlag      = new Setting<>("AntiFlag", true,
            v -> bypassMode.getValue() != BypassMode.Off);
    private final Setting<Integer> flagCooldown  = new Setting<>("FlagCooldownTicks", 40, 10, 120,
            v -> bypassMode.getValue() != BypassMode.Off && antiFlag.getValue());

    // Swing spoof
    private final Setting<Boolean> swingSpoof    = new Setting<>("SwingSpoof", true,
            v -> bypassMode.getValue() != BypassMode.Off);
    private final Setting<Integer> swingInterval = new Setting<>("SwingInterval", 14, 4, 60,
            v -> bypassMode.getValue() != BypassMode.Off && swingSpoof.getValue());

    // Timing jitter
    private final Setting<Boolean> timingJitter = new Setting<>("TimingJitter", true,
            v -> bypassMode.getValue() != BypassMode.Off);
    private final Setting<Integer> jitterRange  = new Setting<>("JitterRangeMs", 18, 2, 60,
            v -> bypassMode.getValue() != BypassMode.Off && timingJitter.getValue());

    // Ground tick spoof (Vulcan crit bypass)
    private final Setting<Boolean> groundTickSpoof = new Setting<>("GroundTickSpoof", true,
            v -> autoCrit.getValue() && bypassMode.getValue() != BypassMode.Off);

    // Packet coalesce
    private final Setting<Boolean> packetCoalesce = new Setting<>("PacketCoalesce", true,
            v -> bypassMode.getValue() != BypassMode.Off);

    // Velocity cap
    private final Setting<Boolean> velocityCap  = new Setting<>("VelocityCap", true,
            v -> bypassMode.getValue() != BypassMode.Off);
    private final Setting<Float>   maxVelDelta  = new Setting<>("MaxVelocityDelta", 0.08f, 0.01f, 0.30f,
            v -> bypassMode.getValue() != BypassMode.Off && velocityCap.getValue());

    /* ── STATE ──────────────────────────────────────────────────────────── */
    private final Timer rocketTimer   = new Timer();
    private final Timer critTimer     = new Timer();
    private final Timer lagCycleTimer = new Timer();

    private final Deque<Packet<?>> lagQueue = new ArrayDeque<>();

    // Orbit
    private double  orbitAngle  = 0;
    private float   smoothX     = 0, smoothY = 0, smoothZ = 0;
    private boolean orbitReady  = false;

    // Fake lag state
    private boolean lagPhaseOn   = false;
    private int     lagTickAccum = 0;

    // Rotation delta cache
    private float lastSentYaw   = Float.NaN;
    private float lastSentPitch = Float.NaN;

    // First tick rocket
    private boolean firstTick = false;

    // Anti-flag
    private boolean flagged           = false;
    private int     flagCooldownAccum = 0;

    // Swing spoof
    private int swingTickAccum = 0;

    // Bezier control point
    private float bezCtrlYaw   = Float.NaN;
    private float bezCtrlPitch = Float.NaN;

    // Cinematic smoothing
    private float cinYaw   = Float.NaN;
    private float cinPitch = Float.NaN;

    // Packet coalesce
    private PlayerMoveC2SPacket.Full pendingFullPacket = null;

    // Velocity cap
    private Vec3d prevVelocity = Vec3d.ZERO;

    // Matrix stagger
    private int matrixAttackTick = 0;

    public ElytraTarget() {
        super("ElytraTarget", Category.COMBAT);
    }

    /* ═══════════════════════════════════════════════════════════════════════
       LIFECYCLE
    ═══════════════════════════════════════════════════════════════════════ */

    @Override
    public void onEnable() {
        orbitReady        = false;
        orbitAngle        = 0;
        firstTick         = true;
        lagPhaseOn        = false;
        lagTickAccum      = 0;
        lastSentYaw       = Float.NaN;
        lastSentPitch     = Float.NaN;
        flagged           = false;
        flagCooldownAccum = 0;
        swingTickAccum    = 0;
        bezCtrlYaw        = Float.NaN;
        bezCtrlPitch      = Float.NaN;
        cinYaw            = Float.NaN;
        cinPitch          = Float.NaN;
        pendingFullPacket = null;
        prevVelocity      = Vec3d.ZERO;
        matrixAttackTick  = 0;
        lagQueue.clear();
        rocketTimer.reset();
        lagCycleTimer.reset();
    }

    @Override
    public void onDisable() {
        orbitReady        = false;
        firstTick         = false;
        lagPhaseOn        = false;
        lagTickAccum      = 0;
        flagged           = false;
        pendingFullPacket = null;
        flushLagQueue();
    }

    /* ═══════════════════════════════════════════════════════════════════════
       ANA LOOP
    ═══════════════════════════════════════════════════════════════════════ */

    @EventHandler
    public void onPostSync(EventPostSync e) {
        if (mc.player == null || mc.world == null) return;

        /* ── anti-flag tick ──────────────────────────────────────────── */
        tickAntiFlag();

        /* ── swing spoof ─────────────────────────────────────────────── */
        tickSwingSpoof();

        /* ── velocity cap ────────────────────────────────────────────── */
        if (velocityCap.getValue() && bypassMode.getValue() != BypassMode.Off) {
            Vec3d vel   = mc.player.getVelocity();
            Vec3d delta = vel.subtract(prevVelocity);
            double dLen = delta.length();
            double cap  = maxVelDelta.getValue();
            if (dLen > cap) {
                mc.player.setVelocity(prevVelocity.add(delta.normalize().multiply(cap)));
            }
            prevVelocity = mc.player.getVelocity();
        }

        /* ── Matrix stagger sayacı ───────────────────────────────────── */
        if (bypassMode.getValue() == BypassMode.Matrix) matrixAttackTick++;

        /* ── fake lag tick ───────────────────────────────────────────── */
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

        /* ── paket birleştirme flush ─────────────────────────────────── */
        flushPendingFullPacket();

        /* ── kılıç seçimi ──────────────────────────────────────────────── */
        if (autoSharpestSword.getValue() && validTarget) {
            SearchInvResult sword = InventoryUtility.getHighestSharpnessSwordHotBar();
            if (sword.found() && mc.player.getInventory().selectedSlot != sword.slot())
                queueOrSend(new UpdateSelectedSlotC2SPacket(sword.slot()));
        }

        /* ── kritik vuruş ──────────────────────────────────────────────── */
        if (autoCrit.getValue() && validTarget && !isFlagged()) {
            boolean matrixReady = bypassMode.getValue() != BypassMode.Matrix
                    || matrixAttackTick >= attackStagger.getValue();
            if (critTimer.passedMs(applyJitter(200)) && matrixReady) {
                doCritPacket();
                critTimer.reset();
                if (bypassMode.getValue() == BypassMode.Matrix) matrixAttackTick = 0;
            }
        }

        /* ── ROCKET ────────────────────────────────────────────────────── */
        if (!rocketBoost.getValue()) return;

        boolean shouldBoost = flying && (
                alwaysBoost.getValue()
                || validTarget
                || mc.player.getVelocity().y < -0.10
                || mc.player.getVelocity().length() < 0.40);

        if (!shouldBoost) return;

        /* flag aktifse roket tetikleme */
        if (isFlagged()) return;

        boolean isFirst = firstTick;
        firstTick = false;

        if (!isFirst) {
            long delay = instantFire.getValue()
                    ? applyJitter(150L)
                    : applyJitter((long)(int) rocketDelay.getValue());
            if (!rocketTimer.passedMs(delay)) return;
        }

        int burst = lagPhaseOn ? 1 : rocketBurst.getValue();
        for (int i = 0; i < burst; i++) fireRocket();
        rocketTimer.reset();
    }

    /* ═══════════════════════════════════════════════════════════════════════
       ANTI-FLAG
    ═══════════════════════════════════════════════════════════════════════ */

    private void tickAntiFlag() {
        if (!antiFlag.getValue() || bypassMode.getValue() == BypassMode.Off) {
            flagged = false;
            return;
        }

        Vec3d vel = mc.player.getVelocity();
        boolean rubberBand =
                prevVelocity.length() > 0.15
                && vel.length() < 0.05
                && !mc.player.isOnGround();

        if (rubberBand && !flagged) {
            flagged           = true;
            flagCooldownAccum = 0;
        }

        if (flagged) {
            flagCooldownAccum++;
            if (flagCooldownAccum >= flagCooldown.getValue()) {
                flagged           = false;
                flagCooldownAccum = 0;
            }
        }
    }

    private boolean isFlagged() {
        return antiFlag.getValue()
                && bypassMode.getValue() != BypassMode.Off
                && flagged;
    }

    /* ═══════════════════════════════════════════════════════════════════════
       SWING SPOOF
    ═══════════════════════════════════════════════════════════════════════ */

    private void tickSwingSpoof() {
        if (!swingSpoof.getValue() || bypassMode.getValue() == BypassMode.Off) return;
        swingTickAccum++;
        if (swingTickAccum >= swingInterval.getValue()) {
            sendPacket(new HandSwingC2SPacket(Hand.MAIN_HAND));
            swingTickAccum = 0;
        }
    }

    /* ═══════════════════════════════════════════════════════════════════════
       TIMING JITTER
    ═══════════════════════════════════════════════════════════════════════ */

    private long applyJitter(long baseMs) {
        if (!timingJitter.getValue() || bypassMode.getValue() == BypassMode.Off)
            return baseMs;
        int range = jitterRange.getValue();
        long delta = (long)(ThreadLocalRandom.current().nextInt(range * 2 + 1) - range);
        return Math.max(50L, baseMs + delta);
    }

    /* ═══════════════════════════════════════════════════════════════════════
       PACKET COALESCE
    ═══════════════════════════════════════════════════════════════════════ */

    private void coalesceOrSend(PlayerMoveC2SPacket.Full pkt) {
        if (packetCoalesce.getValue() && bypassMode.getValue() != BypassMode.Off) {
            pendingFullPacket = pkt;
        } else {
            queueOrSend(pkt);
        }
    }

    private void flushPendingFullPacket() {
        if (pendingFullPacket != null) {
            queueOrSend(pendingFullPacket);
            pendingFullPacket = null;
        }
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

        d
