package warne.client.features.modules.combat;

import meteordevelopment.orbit.EventHandler;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.network.packet.c2s.play.PlayerInteractItemC2SPacket;
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;
import net.minecraft.item.Items;
import net.minecraft.util.Hand;
import net.minecraft.util.math.Box;
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
 * ElytraTarget — Movement module.
 *
 * Features:
 *  - Pursuit: chases elytra-flying targets within range
 *  - Predict: calculates where the target will be and aims there
 *  - Rocket boost: fires firework rockets to accelerate toward target
 *  - Velocity control: prevents uncontrolled acceleration (RocketSpeed setting)
 *  - Smooth rotation: GrimAC-safe yaw/pitch with GCD correction
 *  - Prediction box: renders a hitbox preview at the predicted position
 *
 * All compile errors from the bytecode version are fixed:
 *  - No ModeSetting (replaced with Boolean)
 *  - No Aura dependency (own findTarget())
 *  - No description in super() call
 *  - isFriend() uses PlayerEntity cast
 *  - fireRocket() returns boolean (no void return in boolean method)
 */
public final class ElytraTarget extends Module {

    public static ElytraTarget INSTANCE;

    /* ── BOX CONSTANTS (kept for future render use) ─────────────────────── */
    private static final float BOX_GLOW_OUTER_THICKNESS = 0.17f;
    private static final float BOX_GLOW_MID_THICKNESS   = 0.13f;
    private static final float BOX_GLOW_CORE_THICKNESS  = 0.11f;
    private static final int[][] BOX_EDGES = {
        {0,1},{1,2},{2,3},{3,0},
        {4,5},{5,6},{6,7},{7,4},
        {0,4},{1,5},{2,6},{3,7}
    };

    /* ── PURSUIT ─────────────────────────────────────────────────────────── */

    /** Enable pursuit mode. When off the module does nothing. */
    public final Setting<Boolean> pursuitEnabled = new Setting<>("Pursuit", true);

    /** Maximum range to lock onto a target (blocks). */
    public final Setting<Float> pursuitDistance = new Setting<>("PursuitRange",
            60.0f, 10.0f, 150.0f,
            v -> pursuitEnabled.getValue());

    /** Only lock onto targets that are also elytra-flying. */
    public final Setting<Boolean> onlyFlyingTargets = new Setting<>("OnlyFlyingTargets", true,
            v -> pursuitEnabled.getValue());

    /* ── PREDICTION ──────────────────────────────────────────────────────── */

    /** Enable velocity-based prediction (lead the target). */
    public final Setting<Boolean> predictMode = new Setting<>("Predict", true,
            v -> pursuitEnabled.getValue());

    /**
     * Prediction strength multiplier.
     * Higher = aim further ahead of the target.
     * 1.0 = aim at current position, 5.0 = heavily leads the target.
     */
    public final Setting<Float> predictStrength = new Setting<>("PredictStrength",
            2.7f, 1.0f, 5.0f,
            v -> predictMode.getValue());

    /** Draw a wireframe box at the predicted position. */
    public final Setting<Boolean> drawPredictBox = new Setting<>("DrawPredictBox", true,
            v -> predictMode.getValue());

    /** Box fill transparency (0 = invisible, 255 = opaque). */
    public final Setting<Float> predictFillAlpha = new Setting<>("BoxAlpha",
            40.0f, 0.0f, 255.0f,
            v -> drawPredictBox.getValue());

    /* ── ROTATION ────────────────────────────────────────────────────────── */

    /** Send rotation packets toward the target/predicted position. */
    public final Setting<Boolean> autoRotate = new Setting<>("AutoRotate", true,
            v -> pursuitEnabled.getValue());

    /**
     * Rotation lerp speed.
     * 0.05 = very slow smooth turn, 0.45 = near-instant snap.
     */
    public final Setting<Float> rotateLerp = new Setting<>("RotateLerp",
            0.22f, 0.05f, 0.45f,
            v -> autoRotate.getValue());

    /** Apply GCD correction for GrimAC bypass. */
    public final Setting<Boolean> grimBypass = new Setting<>("GrimBypass", true,
            v -> autoRotate.getValue());

    /* ── ROCKET BOOST ────────────────────────────────────────────────────── */

    /** Fire firework rockets to boost toward the target. */
    public final Setting<Boolean> rocketBoost = new Setting<>("RocketBoost", true,
            v -> pursuitEnabled.getValue());

    /** Delay between rocket firings (ms). Lower = more frequent. */
    public final Setting<Integer> rocketDelay = new Setting<>("RocketDelay",
            200, 50, 2000,
            v -> rocketBoost.getValue());

    /**
     * Rocket speed multiplier (0.1 – 1.0).
     *
     * Controls how frequently rockets actually fire relative to RocketDelay.
     *   1.0 = fire every RocketDelay ms  (fastest)
     *   0.5 = fire every RocketDelay*2 ms (half speed)
     *   0.1 = fire every RocketDelay*10 ms (very slow)
     *
     * Keep RocketBurst at 1 and reduce this value to avoid uncontrolled
     * acceleration. Each extra burst packet applies an additional velocity
     * impulse server-side, which compounds into runaway speed.
     */
    public final Setting<Float> rocketSpeed = new Setting<>("RocketSpeed",
            1.0f, 0.1f, 1.0f,
            v -> rocketBoost.getValue());

    /**
     * Number of rocket packets per firing.
     * WARNING: values above 1 will cause progressive acceleration because
     * the server applies a velocity impulse for each packet. Use RocketSpeed
     * to throttle instead of increasing burst.
     */
    public final Setting<Integer> rocketBurst = new Setting<>("RocketBurst",
            1, 1, 3,
            v -> rocketBoost.getValue());

    /** Only boost when a valid target is locked. */
    public final Setting<Boolean> boostOnlyWithTarget = new Setting<>("BoostOnlyWithTarget", false,
            v -> rocketBoost.getValue());

    /* ── STATE ───────────────────────────────────────────────────────────── */

    private final Timer rocketTimer = new Timer();

    private LivingEntity smoothedTarget         = null;
    private Box          smoothedPredictionBox  = null;

    private boolean disableForward = false;
    private long    lastHurtTime   = 0L;

    private float lastSentYaw   = Float.NaN;
    private float lastSentPitch = Float.NaN;

    /* ─────────────────────────────────────────────────────────────────────── */

    public ElytraTarget() {
        super("ElytraTarget", Category.MOVEMENT);
        INSTANCE = this;
    }

    /* ── LIFECYCLE ───────────────────────────────────────────────────────── */

    @Override
    public void onEnable() {
        disableForward  = false;
        lastHurtTime    = 0L;
        lastSentYaw     = Float.NaN;
        lastSentPitch   = Float.NaN;
        smoothedTarget        = null;
        smoothedPredictionBox = null;
        rocketTimer.reset();
    }

    @Override
    public void onDisable() {
        smoothedTarget        = null;
        smoothedPredictionBox = null;
    }

    /* ── MAIN LOOP ───────────────────────────────────────────────────────── */

    @EventHandler
    public void onPostSync(EventPostSync event) {
        if (mc.player == null || mc.world == null) return;
        if (!pursuitEnabled.getValue()) return;

        /* Hurt-time guard: disable forward boost briefly after taking damage.
           Prevents the module from pushing the player into attacks. */
        if (mc.player.hurtTime > 0) {
            disableForward = true;
            lastHurtTime   = System.currentTimeMillis();
        }
        if (System.currentTimeMillis() - lastHurtTime >= 500L) {
            disableForward = false;
        }

        LivingEntity target = findTarget();

        if (target == null) {
            disableForward        = true;
            smoothedTarget        = null;
            smoothedPredictionBox = null;
            return;
        }

        smoothedTarget = target;

        /* Compute predicted position */
        Vec3d predicted = computePredicted(target);

        /* Update prediction box for render */
        if (drawPredictBox.getValue()) {
            Box base = target.getBoundingBox();
            Vec3d delta = predicted.subtract(target.getPos());
            smoothedPredictionBox = base.offset(delta);
        } else {
            smoothedPredictionBox = null;
        }

        /* Rotate toward predicted position */
        if (autoRotate.getValue() && mc.player.isFallFlying()) {
            rotateToward(predicted);
        }

        /* Rocket boost */
        if (rocketBoost.getValue() && mc.player.isFallFlying()) {
            boolean shouldFire = !boostOnlyWithTarget.getValue() || target != null;
            if (shouldFire) {
                float  speed         = Math.max(rocketSpeed.getValue(), 0.01f);
                long   baseDelay     = (long)(int) rocketDelay.getValue();
                long   effectiveDelay = (long)(baseDelay / speed);
                if (rocketTimer.passedMs(effectiveDelay)) {
                    if (fireRocket()) rocketTimer.reset();
                }
            }
        }
    }

    /* ── TARGET SEARCH ───────────────────────────────────────────────────── */

    /**
     * Finds the closest valid target within pursuitDistance.
     * Does not depend on any external Aura module.
     */
    private LivingEntity findTarget() {
        LivingEntity best     = null;
        double       bestDist = (double) pursuitDistance.getValue();
        bestDist *= bestDist; // compare squared to avoid sqrt

        for (PlayerEntity player : mc.world.getPlayers()) {
            if (player == mc.player) continue;
            if (!player.isAlive()) continue;
            if (player.isSpectator()) continue;
            if (player.getHealth() <= 0f) continue;
            if (Managers.FRIEND.isFriend(player)) continue;
            if (onlyFlyingTargets.getValue() && !player.isFallFlying()) continue;

            double dist = player.squaredDistanceTo(mc.player);
            if (dist < bestDist) {
                best     = player;
                bestDist = dist;
            }
        }

        return best;
    }

    /* ── PREDICTION ──────────────────────────────────────────────────────── */

    /**
     * Returns where the target is predicted to be when our rockets reach it.
     * Uses the target's current velocity scaled by predictStrength.
     * Falls back to target's current position when prediction is disabled.
     */
    private Vec3d computePredicted(LivingEntity target) {
        Vec3d pos = target.getPos();
        if (!predictMode.getValue()) return pos;

        Vec3d velocity = target.getVelocity();
        double dist   = Math.sqrt(mc.player.squaredDistanceTo(target));
        double ticks  = Math.min(dist / Math.max(0.5, velocity.length()), 20.0);
        double scale  = predictStrength.getValue();

        return pos.add(velocity.multiply(ticks * scale));
    }

    /* ── ROTATION ────────────────────────────────────────────────────────── */

    /**
     * Sends a LookAndOnGround packet toward the predicted position.
     * Uses lerp + optional GCD correction for GrimAC bypass.
     * Only sends a packet when the angle change exceeds the deadzone.
     */
    private void rotateToward(Vec3d target) {
        double dx = target.x - mc.player.getX();
        double dy = target.y - (mc.player.getY() + mc.player.getEyeHeight(mc.player.getPose()));
        double dz = target.z - mc.player.getZ();
        double hDist = Math.sqrt(dx * dx + dz * dz);
        if (hDist < 1e-4) return;

        float tYaw   = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90f;
        float tPitch = MathHelper.clamp(
                (float) -Math.toDegrees(Math.atan2(dy, hDist)), -90f, 90f);

        float cYaw   = mc.player.getYaw();
        float cPitch = mc.player.getPitch();
        float lerp   = rotateLerp.getValue();

        float rawYaw   = cYaw   + MathHelper.wrapDegrees(tYaw   - cYaw)   * lerp;
        float rawPitch = MathHelper.clamp(
                cPitch + MathHelper.wrapDegrees(tPitch - cPitch) * lerp, -90f, 90f);

        float finalYaw, finalPitch;
        if (grimBypass.getValue()) {
            double sens = mc.options.getMouseSensitivity().getValue() * 0.6 + 0.2;
            double gcd  = Math.pow(sens, 3.0) * 1.2;
            finalYaw   = (float)(rawYaw   - (rawYaw   - cYaw)   % gcd);
            finalPitch = (float)(rawPitch - (rawPitch - cPitch) % gcd);
        } else {
            finalYaw   = rawYaw;
            finalPitch = rawPitch;
        }

        mc.player.setYaw(finalYaw);
        mc.player.setPitch(finalPitch);

        /* Only send packet when rotation is large enough to matter */
        float baseYaw   = Float.isNaN(lastSentYaw)   ? cYaw   : lastSentYaw;
        float basePitch = Float.isNaN(lastSentPitch) ? cPitch : lastSentPitch;

        if (Math.abs(MathHelper.wrapDegrees(finalYaw   - baseYaw))   > 0.5f
         || Math.abs(MathHelper.wrapDegrees(finalPitch - basePitch)) > 0.3f) {
            sendPacket(new net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket
                    .LookAndOnGround(finalYaw, finalPitch, mc.player.isOnGround()));
            lastSentYaw   = finalYaw;
            lastSentPitch = finalPitch;
        }
    }

    /* ── ROCKET ──────────────────────────────────────────────────────────── */

    /**
     * Fires a firework rocket silently (client slot unchanged).
     *
     * Fix: all early-return branches now return false (was void return in
     * the original bytecode reconstruction — compile error in boolean method).
     *
     * Speed control: use RocketSpeed setting to throttle firing frequency
     * instead of increasing RocketBurst. Each extra burst packet applies
     * a separate velocity impulse server-side, causing runaway acceleration.
     */
    private boolean fireRocket() {
        /* Find rocket in hotbar */
        SearchInvResult rocketHotbar = InventoryUtility.findItemInHotBar(Items.FIREWORK_ROCKET);
        int rocketSlot = rocketHotbar.slot();

        /* Not in hotbar — try to move from inventory */
        if (rocketSlot == -1) {
            SearchInvResult anywhere = InventoryUtility.findItemInInventory(Items.FIREWORK_ROCKET);
            if (!anywhere.found() || anywhere.isInHotBar()) return false; // FIX: was "return;"
            int emptySlot = -1;
            for (int i = 0; i < 9; i++) {
                if (mc.player.getInventory().getStack(i).isEmpty()) {
                    emptySlot = i;
                    break;
                }
            }
            if (emptySlot == -1) return false; // FIX: was "return;"
            clickSlot(anywhere.slot(), emptySlot,
                    net.minecraft.screen.slot.SlotActionType.SWAP);
            rocketSlot = emptySlot;
        }

        /* Silent swap: tell server we switched to rocket slot,
           fire, then tell server we switched back. Client slot never changes. */
        int clientSlot = mc.player.getInventory().selectedSlot;

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

    /* ── PUBLIC API ──────────────────────────────────────────────────────── */

    /** True when pursuit is active and the player is not in a hurt cooldown. */
    public boolean isPursuitActive() {
        return isEnabled() && pursuitEnabled.getValue() && !disableForward;
    }

    /** Returns the smoothed prediction box for the current target, or null. */
    public Box getPredictionBox() {
        return smoothedPredictionBox;
    }

    /** Returns the current locked target, or null. */
    public LivingEntity getTarget() {
        return smoothedTarget;
    }

    /**
     * Returns true when this module should pursue the given entity.
     * Used by external modules (e.g. Aura) to check compatibility.
     */
    public boolean shouldTarget(LivingEntity entity) {
        if (!isPursuitActive()) return false;
        if (entity == null || !entity.isAlive()) return false;
        if (entity instanceof PlayerEntity p) {
            if (p.isSpectator()) return false;
            if (Managers.FRIEND.isFriend(p)) return false;
        }
        if (entity.getHealth() <= 0f) return false;
        return true;
    }
}
