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

    public enum CritMode { Paket, Katı }

    private final Setting<Boolean> fişekBoost = new Setting<>("FişekBoost", true);
    private final Setting<Integer> fişekGecikmesi = new Setting<>("FişekGecikmesi", 300, 50, 3000,
            v -> fişekBoost.getValue());
    private final Setting<Integer> fişekSalvası = new Setting<>("FişekSalvası", 2, 1, 10,
            v -> fişekBoost.getValue());
    private final Setting<Boolean> sessizFişek = new Setting<>("SessizFişek", true,
            v -> fişekBoost.getValue());
    private final Setting<Boolean> otomatikFişekGeç = new Setting<>("OtomatikFişekGeç", true,
            v -> fişekBoost.getValue());
    private final Setting<Float> maksimumHız = new Setting<>("MaksimumHız", 3.5f, 0.5f, 20.0f,
            v -> fişekBoost.getValue());

    private final Setting<Boolean> sadeceUçarken   = new Setting<>("SadeceUçarken", true);
    private final Setting<Boolean> hedefTakip      = new Setting<>("HedefTakip", true);
    private final Setting<Float>   takipHızı       = new Setting<>("TakipHızı", 0.8f, 0.1f, 3.0f,
            v -> hedefTakip.getValue());
    private final Setting<Float>   yörüngeYarıçapı = new Setting<>("YörüngeYarıçapı", 4.0f, 1.0f, 15.0f,
            v -> hedefTakip.getValue());
    private final Setting<Boolean> önünGeç         = new Setting<>("ÖnünGeç", true,
            v -> hedefTakip.getValue());
    private final Setting<Boolean> hedefKilidi     = new Setting<>("HedefKilidi", true);

    private final Setting<Boolean>  otoCrit  = new Setting<>("OtoCrit", true);
    private final Setting<CritMode> critModu = new Setting<>("CritModu", CritMode.Paket,
            v -> otoCrit.getValue());

    private final Setting<Boolean> otoKılıç = new Setting<>("OtoKılıç", true);

    private final Timer rocketTimer = new Timer();
    private final Timer critTimer   = new Timer();

    private double  orbitAngle    = 0;
    private double  smoothOrbitX  = 0;
    private double  smoothOrbitY  = 0;
    private double  smoothOrbitZ  = 0;
    private boolean orbitSmoothed = false;
    private int     slotToRestore = 0;

    private float lastSentYaw   = Float.NaN;
    private float lastSentPitch = Float.NaN;

    private Entity kilitliHedef = null;

    public ElytraTarget() {
        super("ElytraTarget", Category.COMBAT);
    }

    @Override
    public void onEnable() {
        orbitAngle    = 0;
        orbitSmoothed = false;
        lastSentYaw   = Float.NaN;
        lastSentPitch = Float.NaN;
        kilitliHedef  = null;
        rocketTimer.reset();
        critTimer.reset();
        if (mc.player != null) slotToRestore = mc.player.getInventory().selectedSlot;
    }

    @Override
    public void onDisable() {
        orbitSmoothed = false;
        lastSentYaw   = Float.NaN;
        lastSentPitch = Float.NaN;
        kilitliHedef  = null;
    }

    @EventHandler
    public void onPostSync(EventPostSync e) {
        if (mc.player == null || mc.world == null) return;

        slotToRestore = mc.player.getInventory().selectedSlot;

        float görüşMenzili = mc.options.getViewDistance().getValue() * 16f;

        Entity hedef = resolveHedef(görüşMenzili);
        boolean hedefVar = hedef != null;

        boolean uçuyor = mc.player.isFallFlying();

        if (uçuyor) {
            if (hedefTakip.getValue() && hedefVar) {
                followAndOrbit(hedef);
            } else {
                lastSentYaw   = Float.NaN;
                lastSentPitch = Float.NaN;
            }

            if (otoCrit.getValue() && hedefVar && critTimer.passedMs(200)) {
                doCritSwing();
                critTimer.reset();
            }
        } else {
            orbitSmoothed = false;

            if (otoCrit.getValue() && hedefVar && critTimer.passedMs(200)) {
                doCritPacket();
                critTimer.reset();
            }
        }

        if (otoKılıç.getValue() && hedefVar) {
            SearchInvResult kılıç = InventoryUtility.getHighestSharpnessSwordHotBar();
            if (kılıç.found() && slotToRestore != kılıç.slot()) {
                sendPacket(new UpdateSelectedSlotC2SPacket(kılıç.slot()));
                sendPacket(new UpdateSelectedSlotC2SPacket(slotToRestore));
            }
        }

        if (!fişekBoost.getValue()) return;
        if (sadeceUçarken.getValue() && !uçuyor) return;
        if (!hedefVar) return;

        double anHız = mc.player.getVelocity().length();
        if (anHız > maksimumHız.getValue()) {
            rocketTimer.reset();
            return;
        }

        if (!rocketTimer.passedMs(fişekGecikmesi.getValue())) return;

        int atıldı = 0;
        for (int i = 0; i < fişekSalvası.getValue(); i++) {
            if (fireRocket()) atıldı++;
        }
        if (atıldı > 0) rocketTimer.reset();
    }

    private Entity resolveHedef(float görüşMenzili) {
        if (!hedefKilidi.getValue()) {
            Entity t = Aura.target;
            if (t == null) return null;
            boolean menzilde = PlayerUtility.squaredDistanceFromEyes(t.getPos())
                    < (görüşMenzili * görüşMenzili);
            return menzilde ? t : null;
        }

        if (kilitliHedef != null) {
            boolean geçersiz = kilitliHedef.isRemoved()
                    || !kilitliHedef.isAlive()
                    || PlayerUtility.squaredDistanceFromEyes(kilitliHedef.getPos())
                       >= (görüşMenzili * görüşMenzili);
            if (geçersiz) kilitliHedef = null;
        }

        if (kilitliHedef == null && Aura.target != null) {
            Entity aday = Aura.target;
            boolean menzilde = PlayerUtility.squaredDistanceFromEyes(aday.getPos())
                    < (görüşMenzili * görüşMenzili);
            if (menzilde) kilitliHedef = aday;
        }

        return kilitliHedef;
    }

    private void followAndOrbit(Entity target) {
        Vec3d hedefPos    = target.getPos();
        Vec3d hedefMotion = target.getVelocity();
        float radius      = yörüngeYarıçapı.getValue();
        float speed       = takipHızı.getValue();

        double distSq = PlayerUtility.squaredDistanceFromEyes(hedefPos);
        double dist   = Math.sqrt(distSq);
        double eyeY   = mc.player.getY() + mc.player.getEyeHeight(mc.player.getPose());
        double safeThresholdSq = radius * radius * 9.0;

        boolean kaçışModu = dist < radius * 1.1;

        Vec3d predictedPos = hedefPos;
        if (!kaçışModu && önünGeç.getValue()
                && target instanceof net.minecraft.entity.LivingEntity le
                && le.isFallFlying()) {
            double ticks = Math.min(dist / Math.max(speed * 2.0, 0.1), 20.0);
            predictedPos = hedefPos.add(hedefMotion.multiply(ticks));
        }

        orbitAngle = (orbitAngle + 0.04 * speed) % (Math.PI * 2.0);

        double rawX, rawY, rawZ;

        if (kaçışModu) {
            double dx0  = mc.player.getX() - hedefPos.x;
            double dz0  = mc.player.getZ() - hedefPos.z;
            double hLen = Math.sqrt(dx0 * dx0 + dz0 * dz0);
            double nx   = hLen > 1e-4 ? dx0 / hLen : 1.0;
            double nz   = hLen > 1e-4 ? dz0 / hLen : 0.0;
            rawX = hedefPos.x + nx * radius * 2.0;
            rawZ = hedefPos.z + nz * radius * 2.0;
            rawY = hedefPos.y + 3.0;
        } else {
            rawX = predictedPos.x + Math.cos(orbitAngle) * radius;
            rawZ = predictedPos.z + Math.sin(orbitAngle) * radius;
            rawY = predictedPos.y + 2.0;

            if (distSq < safeThresholdSq) {
                double yakınlık = MathHelper.clamp(
                        (float)(1.0 - dist / (radius * 3.0)), 0f, 1f);
                rawY = Math.max(rawY, eyeY + 1.0 + yakınlık * 3.0);
            }
        }

        if (!orbitSmoothed) {
            smoothOrbitX  = rawX;
            smoothOrbitY  = rawY;
            smoothOrbitZ  = rawZ;
            orbitSmoothed = true;
        } else {
            double k = MathHelper.clamp(speed * 0.12f, 0.04f, 0.28f);
            if (kaçışModu) k = MathHelper.clamp(speed * 0.20f, 0.10f, 0.35f);
            smoothOrbitX += (rawX - smoothOrbitX) * k;
            smoothOrbitY += (rawY - smoothOrbitY) * k;
            smoothOrbitZ += (rawZ - smoothOrbitZ) * k;
        }

        double dx    = smoothOrbitX - mc.player.getX();
        double dy    = smoothOrbitY - eyeY;
        double dz    = smoothOrbitZ - mc.player.getZ();
        double hDist = Math.sqrt(dx * dx + dz * dz);

        // Avoid skipping the tick (which caused stutter/"takılma" since no
        // look packet was sent that frame) and avoid extreme dive pitch from
        // atan2 when the horizontal distance collapses near zero.
        double hDistSafe = Math.max(hDist, 0.35);

        float hedefYaw = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90f;

        float pitchMaxAşağı;
        if (kaçışModu) {
            pitchMaxAşağı = 40f;
        } else if (distSq < safeThresholdSq) {
            double yakınlık = MathHelper.clamp(
                    (float)(1.0 - dist / (radius * 3.0)), 0f, 1f);
            pitchMaxAşağı = (float)(20.0 - yakınlık * 30.0);
        } else {
            pitchMaxAşağı = 90f;
        }

        float hedefPitch = MathHelper.clamp(
                (float) -Math.toDegrees(Math.atan2(dy, hDistSafe)),
                -90f, pitchMaxAşağı);

        float mevcutYaw   = mc.player.getYaw();
        float mevcutPitch = mc.player.getPitch();
        float lerpT = kaçışModu
                ? MathHelper.clamp(speed * 0.45f, 0.15f, 0.55f)
                : speed * 0.3f;

        float finalYaw   = mevcutYaw   + MathHelper.wrapDegrees(hedefYaw   - mevcutYaw)   * lerpT;
        float finalPitch = MathHelper.clamp(
                mevcutPitch + MathHelper.wrapDegrees(hedefPitch - mevcutPitch) * lerpT,
                -90f, 90f);

        mc.player.setYaw(finalYaw);
        mc.player.setPitch(finalPitch);

        float baseYaw   = Float.isNaN(lastSentYaw)   ? mevcutYaw   : lastSentYaw;
        float basePitch = Float.isNaN(lastSentPitch) ? mevcutPitch : lastSentPitch;

        boolean yawDelta   = Math.abs(MathHelper.wrapDegrees(finalYaw   - baseYaw))   > 0.5f;
        boolean pitchDelta = Math.abs(MathHelper.wrapDegrees(finalPitch - basePitch)) > 0.3f;

        if (yawDelta || pitchDelta) {
            sendPacket(new PlayerMoveC2SPacket.LookAndOnGround(
                    finalYaw, finalPitch, mc.player.isOnGround()));
            lastSentYaw   = finalYaw;
            lastSentPitch = finalPitch;
        }
    }

    private void doCritPacket() {
        if (mc.player.isInLava() || mc.player.isSubmergedInWater()) return;
        boolean onGround = mc.player.isOnGround();
        switch (critModu.getValue()) {
            case Paket -> {
                sendPacket(new PlayerMoveC2SPacket.PositionAndOnGround(
                        mc.player.getX(), mc.player.getY() + 0.000000271875, mc.player.getZ(), onGround));
                sendPacket(new PlayerMoveC2SPacket.PositionAndOnGround(
                        mc.player.getX(), mc.player.getY(), mc.player.getZ(), onGround));
            }
            case Katı -> {
                sendPacket(new PlayerMoveC2SPacket.PositionAndOnGround(
                        mc.player.getX(), mc.player.getY() + 0.062600301692775, mc.player.getZ(), onGround));
                sendPacket(new PlayerMoveC2SPacket.PositionAndOnGround(
                        mc.player.getX(), mc.player.getY() + 0.07260029960661, mc.player.getZ(), onGround));
                sendPacket(new PlayerMoveC2SPacket.PositionAndOnGround(
                        mc.player.getX(), mc.player.getY(), mc.player.getZ(), onGround));
            }
        }
    }

    private void doCritSwing() {
        if (mc.player.isOnGround()) return;
        if (mc.player.isInLava() || mc.player.isSubmergedInWater()) return;
        if (mc.player.getVelocity().y >= 0) return;
    }

    private boolean fireRocket() {
        SearchInvResult rocketHotbar = InventoryUtility.findItemInHotBar(Items.FIREWORK_ROCKET);
        int rocketSlot = rocketHotbar.slot();

        if (rocketSlot == -1) {
            if (!otomatikFişekGeç.getValue()) return false;
            SearchInvResult rocketAnywhere = InventoryUtility.findItemInInventory(Items.FIREWORK_ROCKET);
            if (!rocketAnywhere.found() || rocketAnywhere.isInHotBar()) return false;

            int emptySlot = -1;
            for (int i = 0; i < 9; i++) {
                if (mc.player.getInventory().getStack(i).isEmpty()) { emptySlot = i; break; }
            }
            if (emptySlot == -1) return false;

            clickSlot(rocketAnywhere.slot(), emptySlot, net.minecraft.screen.slot.SlotActionType.SWAP);
            rocketSlot = emptySlot;
        }

        int     prevSlot = slotToRestore;
        boolean swap     = prevSlot != rocketSlot;

        if (sessizFişek.getValue()) {
            if (swap) sendPacket(new UpdateSelectedSlotC2SPacket(rocketSlot));
            sendSequencedPacket(id -> new PlayerInteractItemC2SPacket(
                    Hand.MAIN_HAND, id, mc.player.getYaw(), mc.player.getPitch()));
            if (swap) sendPacket(new UpdateSelectedSlotC2SPacket(prevSlot));
        } else {
            if (swap) InventoryUtility.switchTo(rocketSlot);
            sendSequencedPacket(id -> new PlayerInteractItemC2SPacket(
                    Hand.MAIN_HAND, id, mc.player.getYaw(), mc.player.getPitch()));
        }

        return true;
    }
}
