package warne.client.features.modules.combat;

import meteordevelopment.orbit.EventHandler;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.PlayerInteractItemC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.Hand;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import warne.client.core.Managers;
import warne.client.events.impl.EventPostSync;
import warne.client.features.modules.Module;
import warne.client.setting.Setting;
import warne.client.utility.Timer;
import warne.client.utility.player.InventoryUtility;
import warne.client.utility.player.SearchInvResult;

/**
 * ElytraTarget — Warne Client / Combat
 *
 * Bytecode sources:
 *   ElytraTargetModule.class
 *   ElytraRotationProcessor.class      (BASE_YAW_SPEED, BASE_PITCH_SPEED, IDEAL_DISTANCE,
 *                                        smoothBoost, backTargetMultiplier, autoDistance,
 *                                        sharpRotations, rotateAt)
 *   TargetMovementPrediction.class     (prediction, glidingOnly, multiplier)
 *   TargetMovementPrediction$PredictMode.class  (SIMPLE, VELOCITY lambdas)
 *   TargetPosition.class               (EYES -> getEyePos, CENTER -> pos + height/2)
 */
public final class ElytraTarget extends Module {

    public static ElytraTarget INSTANCE;

    /* ── CONSTANTS (ElytraRotationProcessor) ────────────────────────────── */
    private static final float BASE_YAW_SPEED   = 3.5f;
    private static final float BASE_PITCH_SPEED = 2.8f;
    private static final float IDEAL_DISTANCE   = 4.0f;

    /* ── ENUMS ───────────────────────────────────────────────────────────── */

    /** TargetPosition: EYES | CENTER */
    public enum TargetPos { Eyes, Center }

    /**
     * PredictMode: SIMPLE | VELOCITY
     * Simple  -> static base position
     * Velocity -> base + velocity * ticks * multiplier
     */
    public enum PredictMode { Simple, Velocity }

    /* ════════════════════════════════════════════════════════════════════════
       SETTINGS
    ════════════════════════════════════════════════════════════════════════ */

    /* ── Target ─────────────────────────────────────────────────────────── */
    private final Setting<Float>   targetRange = new Setting<>("TargetRange", 60.0f, 5.0f, 150.0f);
    private final Setting<Boolean> onlyFlying  = new Setting<>("OnlyFlying", true);

    /* ── Rotation (ElytraRotationProcessor) ─────────────────────────────── */
    private final Setting<Boolean>   customRotations = new Setting<>("CustomRotations", true);
    private final Setting<Boolean>   sharpRotations  = new Setting<>("Sharp", false,
            v -> customRotations.getValue());
    private final Setting<Boolean>   autoDistance    = new Setting<>("AutoDistance", true,
            v -> customRotations.getValue());
    private final Setting<TargetPos> rotateAt        = new Setting<>("RotateAt", TargetPos.Center,
            v -> customRotations.getValue());
    private final Setting<Boolean>   grimBypass      = new Setting<>("GrimBypass", true,
            v -> customRotations.getValue());

    /* ── Prediction (TargetMovementPrediction) ───────────────────────────── */
    private final Setting<Boolean>     prediction       = new Setting<>("Prediction", true);
    private final Setting<PredictMode> predictMode      = new Setting<>("PredictMode", PredictMode.Velocity,
            v -> prediction.getValue());
    private final Setting<Boolean>     glidingOnly      = new Setting<>("GlidingOnly", true,
            v -> prediction.getValue());
    private final Setting<Float>       predictMultiplier = new Setting<>("PredictMultiplier",
            2.7f, 1.0f, 5.0f, v -> prediction.getValue());

    /* ── Rocket ──────────────────────────────────────────────────────────── */
    private final Setting<Boolean> rocketBoost     = new Setting<>("RocketBoost", true);
    private final Setting<Integer> rocketDelay     = new Setting<>("RocketDelay", 200, 50, 2000,
            v -> rocketBoost.getValue());
    private final Setting<Float>   rocketSpeed     = new Setting<>("RocketSpeed", 1.0f, 0.1f, 1.0f,
            v -> rocketBoost.getValue());
    private final Setting<Integer> rocketBurst     = new Setting<>("RocketBurst", 1, 1, 3,
            v -> rocketBoost.getValue());
    private final Setting<Boolean> swordRocketCycle = new Setting<>("SwordRocketCycle", true,
            v -> rocketBoost.getValue());
    private final Setting<Boolean> autoSword       = new Setting<>("AutoSword", true);

    /* ── FakeLag ─────────────────────────────────────────────────────────── */
    private final Setting<Boolean> fakeLag     = new Setting<>("FakeLag", false);
    private final Setting<Integer> lagTicks    = new Setting<>("LagTicks", 8, 2, 20,
            v -> fakeLag.getValue());
    private final Setting<Integer> lagInterval = new Setting<>("LagInterval", 3, 1, 10,
            v -> fakeLag.getValue());

    /* ════════════════════════════════════════════════════════════════════════
       STATE
    ════════════════════════════════════════════════════════════════════════ */

    private final Timer rocketTimer   = new Timer();
    private final Timer lagCycleTimer = new Timer();

    private final java.util.Deque<net.minecraft.network.packet.Packet<?>> lagQueue
            = new java.util.ArrayDeque<>();

    private float   lastSentYaw   = Float.NaN;
    private float   lastSentPitch = Float.NaN;
    private boolean lagPhaseOn    = false;
    private int     lagTickAccum  = 0;
    private int     cycleStep     = 0;
    private boolean firstTick     = false;
    private double  smoothBoost   = 0.0;

    /* ════════════════════════════════════════════════════════════════════════
       LIFECYCLE
    ════════════════════════════════════════════════════════════════════════ */

    public ElytraTarget() {
        super("ElytraTarget", Category.COMBAT);
        INSTANCE = this;
    }

    @Override
    public void onEnable() {
        lastSentYaw   = Float.NaN;
        lastSentPitch = Float.NaN;
        lagPhaseOn    = false;
        lagTickAccum  = 0;
        cycleStep     = 0;
        firstTick     = true;
        smoothBoost   = 0.0;
        lagQueue.clear();
        rocketTimer.reset();
        lagCycleTimer.reset();
    }

    @Override
    public void onDisable() {
        lagPhaseOn   = false;
        lagTickAccum = 0;
        cycleStep    = 0;
        firstTick    = false;
        smoothBoost  = 0.0;
        flushLagQueue();
    }

    /* ════════════════════════════════════════════════════════════════════════
       MAIN LOOP
    ════════════════════════════════════════════════════════════════════════ */

    @EventHandler
    public void onPostSync(EventPostSync e) {
        if (mc.player == null || mc.world == null) return;

        tickFakeLag();

        LivingEntity target = findTarget();
        boolean flying = mc.player.isFallFlying();

        /* ── Rotation ──────────────────────────────────────────────────── */
        if (customRotations.getValue() && target != null && flying) {
            Vec3d aimPos = getTargetPos(target);
            processRotation(target, aimPos);
        } else {
            lastSentYaw   = Float.NaN;
            lastSentPitch = Float.NaN;
        }

        /* ── Sword select (cycle off) ───────────────────────────────────── */
        if (!swordRocketCycle.getValue() && autoSword.getValue() && target != null) {
            SearchInvResult sword = InventoryUtility.getHighestSharpnessSwordHotBar();
            if (sword.found() && mc.player.getInventory().selectedSlot != sword.slot())
                sendPacket(new UpdateSelectedSlotC2SPacket(sword.slot()));
        }

        /* ── Rocket ─────────────────────────────────────────────────────── */
        if (!rocketBoost.getValue() || !flying) return;

        boolean isFirst = firstTick;
        firstTick = false;

        if (!isFirst) {
            float speed          = Math.max(rocketSpeed.getValue(), 0.01f);
            long  effectiveDelay = (long)((int) rocketDelay.getValue() / speed);
            if (!rocketTimer.passedMs(effectiveDelay)) return;
        }

        if (fireRocket()) rocketTimer.reset();
    }

    /* ════════════════════════════════════════════════════════════════════════
       TARGET SEARCH
    ════════════════════════════════════════════════════════════════════════ */

    private LivingEntity findTarget() {
        LivingEntity best       = null;
        double       bestDistSq = (double) targetRange.getValue() * targetRange.getValue();

        for (PlayerEntity player : mc.world.getPlayers()) {
            if (player == mc.player) continue;
            if (!player.isAlive()) continue;
            if (player.isSpectator()) continue;
            if (player.getHealth() <= 0f) continue;
            if (Managers.FRIEND.isFriend(player)) continue;
            if (onlyFlying.getValue() && !player.isFallFlying()) continue;

            double distSq = player.squaredDistanceTo(mc.player);
            if (distSq < bestDistSq) {
                best       = player;
                bestDistSq = distSq;
            }
        }
        return best;
    }

    /* ════════════════════════════════════════════════════════════════════════
       TARGET POSITION (TargetPosition enum)
         EYES   -> entity.getEyePos()               [method_19538]
         CENTER -> entity.getPos() + (0, h/2, 0)    [method_33571 + method_17682]
    ════════════════════════════════════════════════════════════════════════ */

    private Vec3d getTargetPos(LivingEntity target) {
        Vec3d base = rotateAt.getValue() == TargetPos.Eyes
                ? target.getEyePos()
                : target.getPos().add(0, target.getHeight() / 2.0, 0);
        return applyPrediction(target, base);
    }

    /* ════════════════════════════════════════════════════════════════════════
       PREDICTION (TargetMovementPrediction + PredictMode lambdas)
         Simple   -> lambda$static$0: base pos (no velocity)
         Velocity -> lambda$static$1: pos.add(velocity.multiply(ticks * mult))
                     [method_18798 = getVelocity, method_1021 = multiply]
    ════════════════════════════════════════════════════════════════════════ */

    private Vec3d applyPrediction(LivingEntity target, Vec3d base) {
        if (!prediction.getValue()) return base;
        if (glidingOnly.getValue() && !target.isFallFlying()) return base;
        if (predictMode.getValue() == PredictMode.Simple) return base;

        Vec3d velocity = target.getVelocity();
        double dist  = Math.sqrt(mc.player.squaredDistanceTo(target));
        double speed = Math.max(velocity.length(), 0.05);
        double ticks = Math.min(dist / speed, 20.0);
        return base.add(velocity.multiply(ticks * predictMultiplier.getValue()));
    }

    /* ════════════════════════════════════════════════════════════════════════
       ROTATION (ElytraRotationProcessor.processRotation)
         - rotationDeltaTo   -> yaw/pitch delta
         - isTargetBehind    -> |deltaYaw| > 90
         - backTargetMultiplier -> 1.8 if behind
         - smoothBoost       -> lerp 0.15
         - autoDistance      -> clamp(dist / IDEAL_DISTANCE, 0.5, 3.0)
         - sharpRotations    -> direct target angle
         - microAdjustment   -> BASE_YAW_SPEED / BASE_PITCH_SPEED clamp
         - GrimAC GCD bypass
    ════════════════════════════════════════════════════════════════════════ */

    private void processRotation(LivingEntity target, Vec3d aimPos) {
        double dx = aimPos.x - mc.player.getX();
        double dy = aimPos.y - (mc.player.getY() + mc.player.getEyeHeight(mc.player.getPose()));
        double dz = aimPos.z - mc.player.getZ();
        double hDist = Math.sqrt(dx * dx + dz * dz);
        if (hDist < 1e-4) return;

        float tYaw   = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90f;
        float tPitch = MathHelper.clamp(
                (float) -Math.toDegrees(Math.atan2(dy, hDist)), -90f, 90f);

        float cYaw   = mc.player.getYaw();
        float cPitch = mc.player.getPitch();

        float deltaYaw   = MathHelper.wrapDegrees(tYaw   - cYaw);
        float deltaPitch = MathHelper.wrapDegrees(tPitch - cPitch);

        /* isTargetBehind */
        boolean isTargetBehind      = Math.abs(deltaYaw) > 90f;
        double  backTargetMultiplier = isTargetBehind ? 1.8 : 1.0;

        /* smoothBoost */
        double targetBoost = isTargetBehind ? 1.0 : 0.0;
        smoothBoost += (targetBoost - smoothBoost) * 0.15;

        /* autoDistance */
        double dist = Math.sqrt(mc.player.squaredDistanceTo(target));
        double distFactor = autoDistance.getValue()
                ? MathHelper.clamp((float)(dist / IDEAL_DISTANCE), 0.5f, 3.0f)
                : 1.0;

        float yawSpeed   = BASE_YAW_SPEED   * (float)(distFactor * backTargetMultiplier * (1.0 + smoothBoost * 0.5));
        float pitchSpeed = BASE_PITCH_SPEED  * (float) distFactor;

        float finalYaw, finalPitch;
        if (sharpRotations.getValue()) {
            finalYaw   = tYaw;
            finalPitch = tPitch;
        } else {
            float moveYaw   = MathHelper.clamp(deltaYaw,   -yawSpeed,   yawSpeed);
            float movePitch = MathHelper.clamp(deltaPitch, -pitchSpeed, pitchSpeed);
            finalYaw   = cYaw   + moveYaw;
            finalPitch = MathHelper.clamp(cPitch + movePitch, -90f, 90f);
        }

        /* GrimAC GCD */
        if (grimBypass.getValue()) {
            double sens = mc.options.getMouseSensitivity().getValue() * 0.6 + 0.2;
            double gcd  = Math.pow(sens, 3.0) * 1.2;
            finalYaw   = (float)(finalYaw   - (finalYaw   - cYaw)   % gcd);
            finalPitch = (float)(finalPitch - (finalPitch - cPitch) % gcd);
        }

        mc.player.setYaw(finalYaw);
        mc.player.setPitch(finalPitch);

        float baseYaw   = Float.isNaN(lastSentYaw)   ? cYaw   : lastSentYaw;
        float basePitch = Float.isNaN(lastSentPitch) ? cPitch : lastSentPitch;

        if (Math.abs(MathHelper.wrapDegrees(finalYaw   - baseYaw))   > 0.5f
         || Math.abs(MathHelper.wrapDegrees(finalPitch - basePitch)) > 0.3f) {
            queueOrSend(new PlayerMoveC2SPacket.LookAndOnGround(
                    finalYaw, finalPitch, mc.player.isOnGround()));
            lastSentYaw   = finalYaw;
            lastSentPitch = finalPitch;
        }
    }

    /* ════════════════════════════════════════════════════════════════════════
       ROCKET
    ════════════════════════════════════════════════════════════════════════ */

    private boolean fireRocket() {
        SearchInvResult rocketHotbar = InventoryUtility.findItemInHotBar(Items.FIREWORK_ROCKET);
        int rocketSlot = rocketHotbar.slot();

        if (rocketSlot == -1) {
            SearchInvResult anywhere = InventoryUtility.findItemInInventory(Items.FIREWORK_ROCKET);
            if (!anywhere.found() || anywhere.isInHotBar()) return false;
            int emptySlot = -1;
            for (int i = 0; i < 9; i++) {
                if (mc.player.getInventory().getStack(i).isEmpty()) { emptySlot = i; break; }
            }
            if (emptySlot == -1) return false;
            clickSlot(anywhere.slot(), emptySlot, SlotActionType.SWAP);
            rocketSlot = emptySlot;
        }

        int clientSlot = mc.player.getInventory().selectedSlot;

        if (swordRocketCycle.getValue() && autoSword.getValue()) {
            SearchInvResult sword = InventoryUtility.getHighestSharpnessSwordHotBar();

            if (cycleStep % 2 == 0) {
                /* Sword step */
                if (sword.found() && sword.slot() != clientSlot) {
                    sendPacket(new UpdateSelectedSlotC2SPacket(sword.slot()));
                    sendPacket(new UpdateSelectedSlotC2SPacket(clientSlot));
                }
                cycleStep++;
                return false;
            } else {
                /* Rocket step */
                sendPacket(new UpdateSelectedSlotC2SPacket(rocketSlot));
                int burst = rocketBurst.getValue();
                for (int b = 0; b < burst; b++) {
                    sendSequencedPacket(id -> new PlayerInteractItemC2SPacket(
                            Hand.MAIN_HAND, id, mc.player.getYaw(), mc.player.getPitch()));
                }
                sendPacket(new UpdateSelectedSlotC2SPacket(clientSlot));
                cycleStep++;
                if (cycleStep > 5) cycleStep = 0;
                return true;
            }
        }

        /* Normal silent swap */
        if (clientSlot != rocketSlot)
            sendPacket(new UpdateSelectedSlotC2SPacket(rocketSlot));
        int burst = rocketBurst.getValue();
        for (int b = 0; b < burst; b++) {
            sendSequencedPacket(id -> new PlayerInteractItemC2SPacket(
                    Hand.MAIN_HAND, id, mc.player.getYaw(), mc.player.getPitch()));
        }
        if (clientSlot != rocketSlot)
            sendPacket(new UpdateSelectedSlotC2SPacket(clientSlot));
        return true;
    }

    /* ════════════════════════════════════════════════════════════════════════
       FAKE LAG
    ════════════════════════════════════════════════════════════════════════ */

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

    private void queueOrSend(net.minecraft.network.packet.Packet<?> packet) {
        if (fakeLag.getValue() && lagPhaseOn) lagQueue.addLast(packet);
        else sendPacket(packet);
    }

    private void flushLagQueue() {
        while (!lagQueue.isEmpty()) sendPacket(lagQueue.pollFirst());
    }

    /* ════════════════════════════════════════════════════════════════════════
       PUBLIC API
    ════════════════════════════════════════════════════════════════════════ */

    public boolean shouldTarget(LivingEntity entity) {
        if (!isEnabled()) return false;
        if (entity == null || !entity.isAlive()) return false;
        if (entity instanceof PlayerEntity p) {
            if (p.isSpectator()) return false;
            if (Managers.FRIEND.isFriend(p)) return false;
        }
        return entity.getHealth() > 0f;
    }
}
