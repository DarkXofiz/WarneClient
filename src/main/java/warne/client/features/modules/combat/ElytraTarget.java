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

public final class ElytraTarget extends Module {

    /* ── ENUM'LAR (hepsi üstte, düzenli) ───────────────────────────────── */
    public enum CritMode   { Packet, Strict }
    public enum BypassMode { Off, GrimAC }
    public enum SpeedMode  { Soft, Hard }

    /* ── ROCKET ─────────────────────────────────────────────────────────── */
    private final Setting<Boolean> rocketBoost = new Setting<>("RocketBoost", true);

    // RocketDelay: fişekler arası bekleme süresi (ms). Düşük = hızlı, yüksek = yavaş.
    private final Setting<Integer> rocketDelay = new Setting<>("RocketDelay", 150, 50, 2000,
            v -> rocketBoost.getValue());

    // RocketBurst: her ateşlemede kaç fişek basılacağı.
    private final Setting<Integer> rocketBurst = new Setting<>("RocketBurst", 1, 1, 5,
            v -> rocketBoost.getValue());

    private final Setting<Boolean> alwaysBoost = new Setting<>("AlwaysBoost", true,
            v -> rocketBoost.getValue());

    /*
     * MaxSpeed: oyuncunun blok/saniye cinsinden maksimum hızı.
     * Bu hızın üzerindeyken roket ATEŞLENMEZ → hız kontrolü sağlanır.
     * Önerilen: 2.0 (yavaş) – 3.5 (orta) – 6.0 (hızlı)
     */
    private final Setting<Float> maxSpeed = new Setting<>("MaxSpeed", 3.5f, 0.5f, 20.0f,
            v -> rocketBoost.getValue());

    /*
     * SpeedMode:
     *   Soft → maxSpeed aşıldığında roket ateşlenmez, rocketTimer koşmaya devam eder.
     *          Hız düşünce hemen ateşler → daha reaktif ama hafif ivmelenme olabilir.
     *   Hard → maxSpeed aşıldığında rocketTimer da sıfırlanır.
     *          Hız düşünce tam bir rocketDelay bekler → stabil, ani ivmelenme yok.
     */
    private final Setting<SpeedMode> speedMode = new Setting<>("SpeedMode", SpeedMode.Hard,
            v -> rocketBoost.getValue());

    /*
     * SwordRocketCycle: her roket ateşlemesinden ÖNCE sunucuya kılıç slotunu
     * bildirir, ardından fişeği silent ateşler ve client slotuna döner.
     * Client selectedSlot'a ASLA yazılmaz → oyuncu istediği slota geçebilir.
     */
    private final Setting<Boolean> swordRocketCycle = new Setting<>("SwordRocketCycle", true);

    /* ── FAKE LAG ────────────────────────────────────────────────────────── */
    private final Setting<Boolean> fakeLag     = new Setting<>("FakeLag", false);
    private final Setting<Integer> lagTicks    = new Setting<>("LagTicks", 8, 2, 20,
            v -> fakeLag.getValue());
    private final Setting<Integer> lagInterval = new Setting<>("LagInterval", 3, 1, 10,
            v -> fakeLag.getValue());

    /* ── HEDEF ──────────────────────────────────────────────────────────── */
    private final Setting<Float>   targetRange    = new Setting<>("TargetRange", 64f, 5f, 128f);
    private final Setting<Boolean> onlyWhenFlying = new Setting<>("OnlyWhenFlying", true);
    private final Setting<Boolean> followTarget   = new Setting<>("FollowTarget", true);
    private final Setting<Float>   followSpeed    = new Setting<>("FollowSpeed", 0.8f, 0.1f, 3.0f,
            v -> followTarget.getValue());
    private final Setting<Float>   orbitRadius    = new Setting<>("OrbitRadius", 4.0f, 1.0f, 15.0f,
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
    private final Setting<BypassMode> bypassMode = new Setting<>("BypassMode", BypassMode.GrimAC);

    /* ── STATE ──────────────────────────────────────────────────────────── */
    private final Timer rocketTimer   = new Timer();
    private final Timer critTimer     = new Timer();
    private final Timer lagCycleTimer = new Timer();

    private final Deque<Packet<?>> lagQueue = new ArrayDeque<>();

    private double  orbitAngle = 0;
    private float   smoothX    = 0, smoothY = 0, smoothZ = 0;
    private boolean orbitReady = false;

    private boolean lagPhaseOn   = false;
    private int     lagTickAccum = 0;

    private float   lastSentYaw   = Float.NaN;
    private float   lastSentPitch = Float.NaN;

    private boolean firstTick = false;

    /*
     * SwordRocketCycle adım sayacı.
     * 0,2,4 → kılıç adımı | 1,3,5 → fişek adımı
     * Her adım bir fireRocket() çağrısında işlenir.
     */
    private int cycleStep = 0;

    public ElytraTarget() {
        super("ElytraTarget", Category.COMBAT);
    }

    /* ═══════════════════════════════════════════════════════════════════════
       LIFECYCLE
    ═══════════════════════════════════════════════════════════════════════ */

    @Override
    public void onEnable() {
        orbitReady    = false;
        orbitAngle    = 0;
        firstTick     = true;
        lagPhaseOn    = false;
        lagTickAccum  = 0;
        lastSentYaw   = Float.NaN;
        lastSentPitch = Float.NaN;
        lagQueue.clear();
        rocketTimer.reset();
        lagCycleTimer.reset();
        cycleStep = 0;
    }

    @Override
    public void onDisable() {
        orbitReady   = false;
        firstTick    = false;
        lagPhaseOn   = false;
        lagTickAccum = 0;
        cycleStep    = 0;
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
            orbitReady    = false;
            lastSentYaw   = Float.NaN;
            lastSentPitch = Float.NaN;
            return;
        }

        /* ── rotation + orbit ──────────────────────────────────────────── */
        if (followTarget.getValue() && validTarget) {
            followAndOrbit(target);
        } else {
            orbitReady    = false;
            lastSentYaw   = Float.NaN;
            lastSentPitch = Float.NaN;
        }

        /* ── kılıç seçimi (cycle kapalıysa) ───────────────────────────── */
        if (!swordRocketCycle.getValue() && autoSharpestSword.getValue() && validTarget) {
            SearchInvResult sword = InventoryUtility.getHighestSharpnessSwordHotBar();
            if (sword.found() && mc.player.getInventory().selectedSlot != sword.slot())
                sendPacket(new UpdateSelectedSlotC2SPacket(sword.slot()));
        }

        /* ── kritik vuruş ──────────────────────────────────────────────── */
        if (autoCrit.getValue() && validTarget && !flying && critTimer.passedMs(200)) {
            doCritPacket();
            critTimer.reset();
        }

        /* ── ROCKET ────────────────────────────────────────────────────── */
        if (!rocketBoost.getValue()) return;

        boolean shouldBoost = flying && (
                alwaysBoost.getValue()
                || validTarget
                || mc.player.getVelocity().y < -0.10
                || mc.player.getVelocity().length() < 0.40);

        if (!shouldBoost) return;

        /* ── HIZ KONTROLÜ ──────────────────────────────────────────────── */
        double currentSpeed = mc.player.getVelocity().length();
        /*
         * DÜŞÜŞ FIX 3 — Aktif düşüş varsa maxSpeed bypass et.
         * Senaryo: maxSpeed aşıldı → roket kesildi → hız düşüyor → velocity.y
         * negatife gidiyor → düşüş ivmeleniyor. Bu noktada hız limitine bakmaksızın
         * roket atmalıyız, yoksa yere çarparız.
         */
        boolean activeFall = flying && mc.player.getVelocity().y < -0.3;
        if (currentSpeed > maxSpeed.getValue() && !activeFall) {
            // Hard: hız aşımında timer sıfırla → hız düşünce tam bekleme yapılır.
            // Soft: timer koşmaya devam eder → hız düşer düşmez ateşler.
            if (speedMode.getValue() == SpeedMode.Hard) rocketTimer.reset();
            return;
        }

        boolean isFirst = firstTick;
        firstTick = false;

        if (!isFirst) {
            if (!rocketTimer.passedMs((long)(int) rocketDelay.getValue())) return;
        }

        if (fireRocket()) rocketTimer.reset();
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
        if (fakeLag.getValue() && lagPhaseOn) lagQueue.addLast(packet);
        else sendPacket(packet);
    }

    private void flushLagQueue() {
        while (!lagQueue.isEmpty()) sendPacket(lagQueue.pollFirst());
    }

    /* ═══════════════════════════════════════════════════════════════════════
       ROTATION + ORBIT
       LookAndOnGround kullanılır — sadece rotasyon gönderir, pozisyon göndermez.
    ═══════════════════════════════════════════════════════════════════════ */

    private void followAndOrbit(Entity target) {
        Vec3d tPos    = target.getPos();
        Vec3d tMotion = target.getVelocity();
        float radius  = orbitRadius.getValue();
        float speed   = followSpeed.getValue();

        double distSq = PlayerUtility.squaredDistanceFromEyes(tPos);

        /*
         * DÜŞÜŞ FIX — safeThreshold: orbit yarıçapının 3 katı içindeyiz mi?
         * Bu eşiğin içinde özel Y ve pitch koruması devreye girer.
         */
        double safeThresholdSq = radius * radius * 9.0; // (3 * radius)^2
        double eyeY = mc.player.getY() + mc.player.getEyeHeight(mc.player.getPose());

        Vec3d predicted = tPos;
        if (interceptTarget.getValue()
                && target instanceof net.minecraft.entity.LivingEntity le
                && le.isFallFlying()) {
            double dist  = Math.sqrt(distSq);
            double ticks = Math.min(dist / Math.max(speed * 2.0, 0.1), 20.0);
            predicted = tPos.add(tMotion.multiply(ticks));
        }

        orbitAngle = (orbitAngle + 0.04 * speed) % (Math.PI * 2);

        double rawX = predicted.x + Math.cos(orbitAngle) * radius;
        double rawZ = predicted.z + Math.sin(orbitAngle) * radius;
        double rawY = predicted.y + 2.0;

        /*
         * DÜŞÜŞ FIX 1 — Yakın mesafede Y hedefi yükselt.
         * rawY oyuncunun göz seviyesinin altına düşebilir → pitch aşağı döner
         * → elytra dalar → düşüş. Çözüm: threshold içindeyken rawY'yi
         * göz seviyesinin en az 1 blok yukarısında tut.
         * Çok yakınken (closeness→1) ek olarak 3 blok daha yükseğe çıkar.
         */
        if (distSq < safeThresholdSq) {
            double closeness = 1.0 - Math.sqrt(distSq) / (radius * 3.0); // 0..1
            closeness = MathHelper.clamp((float) closeness, 0f, 1f);
            rawY = Math.max(rawY, eyeY + 1.0 + closeness * 3.0);
        }

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

        float tYaw = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90f;

        /*
         * DÜŞÜŞ FIX 2 — Yakın mesafede pitch aşağı limitini kıs.
         * Elytra oyuncunun baktığı yönde iter; aşağı bakınca dalar.
         * Threshold içindeyken maks aşağı açısı 20°→5° arasında kısıtlanır.
         */
        float maxDownPitch = 90f;
        if (distSq < safeThresholdSq) {
            double closeness = MathHelper.clamp(
                    (float)(1.0 - Math.sqrt(distSq) / (radius * 3.0)), 0f, 1f);
            maxDownPitch = (float)(20.0 - closeness * 15.0); // 5°–20° arasında
        }
        float tPitch = MathHelper.clamp((float) -Math.toDegrees(Math.atan2(dy, hDist)), -90f, maxDownPitch);

        float cYaw   = mc.player.getYaw();
        float cPitch = mc.player.getPitch();

        double sens = mc.options.getMouseSensitivity().getValue() * 0.6 + 0.2;
        double gcd  = Math.pow(sens, 3.0) * 1.2;

        float lerpT    = MathHelper.clamp(speed * 0.22f, 0.05f, 0.45f);
        float rawYaw   = cYaw   + MathHelper.wrapDegrees(tYaw   - cYaw)   * lerpT;
        float rawPitch = MathHelper.clamp(
                cPitch + MathHelper.wrapDegrees(tPitch - cPitch) * lerpT, -90f, 90f);

        float finalYaw, finalPitch;
        if (bypassMode.getValue() != BypassMode.Off) {
            finalYaw   = (float)(rawYaw   - (rawYaw   - cYaw)   % gcd);
            finalPitch = (float)(rawPitch - (rawPitch - cPitch) % gcd);
        } else {
            finalYaw   = rawYaw;
            finalPitch = rawPitch;
        }

        mc.player.setYaw(finalYaw);
        mc.player.setPitch(finalPitch);

        if (bypassMode.getValue() != BypassMode.Off) {
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
    }

    /* ═══════════════════════════════════════════════════════════════════════
       KRİTİK VURUŞ
       Elytra uçuşunda pozisyon paketi gönderilmez → server rubber-band yapar.
    ═══════════════════════════════════════════════════════════════════════ */

    private void doCritPacket() {
        if (mc.player.isInLava() || mc.player.isSubmergedInWater()) return;
        if (mc.player.isFallFlying()) return;
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
       swordRocketCycle=true → 6 adımlı döngü: kılıç→fişek→kılıç→fişek→kılıç→fişek
       swordRocketCycle=false → normal silent swap
       Her iki durumda da client selectedSlot'a ASLA yazılmaz.
    ═══════════════════════════════════════════════════════════════════════ */

    private boolean fireRocket() {
        /* Fişek slotunu bul / hotbar'a taşı */
        SearchInvResult rocketHotbar = InventoryUtility.findItemInHotBar(Items.FIREWORK_ROCKET);
        int rocketSlot = rocketHotbar.slot();

        if (rocketSlot == -1) {
            SearchInvResult anywhere = InventoryUtility.findItemInInventory(Items.FIREWORK_ROCKET);
            // BUG FIX: orijinal kodda "return;" vardı → boolean metotta derleme hatası.
            if (!anywhere.found() || anywhere.isInHotBar()) return false;
            int emptySlot = -1;
            for (int i = 0; i < 9; i++) {
                if (mc.player.getInventory().getStack(i).isEmpty()) { emptySlot = i; break; }
            }
            // BUG FIX: aynı hata burada da vardı.
            if (emptySlot == -1) return false;
            clickSlot(anywhere.slot(), emptySlot, SlotActionType.SWAP);
            rocketSlot = emptySlot;
        }

        /* Oyuncunun görsel (client) slotu — asla değiştirilmez */
        int clientSlot = mc.player.getInventory().selectedSlot;

        if (swordRocketCycle.getValue() && autoSharpestSword.getValue()) {
            /*
             * Sıralı döngü:
             *   cycleStep 0,2,4 → kılıç adımı: sunucuya kılıç slotunu bildir, geri dön
             *   cycleStep 1,3,5 → fişek adımı: kılıç → fişek(ler) → client slot
             */
            SearchInvResult sword = InventoryUtility.getHighestSharpnessSwordHotBar();

            if (cycleStep % 2 == 0) {
                // Kılıç adımı
                if (sword.found() && sword.slot() != clientSlot) {
                    sendPacket(new UpdateSelectedSlotC2SPacket(sword.slot()));
                    sendPacket(new UpdateSelectedSlotC2SPacket(clientSlot));
                }
                cycleStep++;
                return false;
            } else {
                // Fişek adımı
                if (sword.found() && sword.slot() != rocketSlot) {
                    sendPacket(new UpdateSelectedSlotC2SPacket(sword.slot()));
                }
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

        /* ── Normal silent swap ──────────────────────────────────────────── */
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
}
