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

    public enum CritMode  { Packet, Strict }
    public enum BypassMode { Off, GrimAC }

    /* ── ROCKET ─────────────────────────────────────────────────────────── */
    private final Setting<Boolean> rocketBoost      = new Setting<>("RocketBoost", true);
    private final Setting<Boolean> instantFire      = new Setting<>("InstantFire", true,
            v -> rocketBoost.getValue());
    private final Setting<Integer> rocketDelay      = new Setting<>("RocketDelay", 5, 0, 300,
            v -> rocketBoost.getValue() && !instantFire.getValue());
    private final Setting<Integer> rocketBurst      = new Setting<>("RocketBurst", 1, 1, 5,
            v -> rocketBoost.getValue());
    // RocketPressCount: tek ateşlemede kac kez PlayerInteractItemC2SPacket gönderilir.
    // 1 = normal hız, 2-5 = artan ivme.
    private final Setting<Integer> rocketPressCount = new Setting<>("RocketPressCount", 1, 1, 5,
            v -> rocketBoost.getValue());
    private final Setting<Boolean> silentRockets    = new Setting<>("SilentRocketUsage", true,
            v -> rocketBoost.getValue());
    private final Setting<Boolean> autoSwitchRocket = new Setting<>("AutoSwitchRocket", true,
            v -> rocketBoost.getValue());
    private final Setting<Boolean> alwaysBoost      = new Setting<>("AlwaysBoost", true,
            v -> rocketBoost.getValue());

    /*
     * SwordRocketCycle: her roket ateşlemesinden ÖNCE sunucuya kılıç slotunu
     * bildirir, ardından fişeği silent ateşler ve client slotuna döner.
     * Roket zamanlaması normal rocketTimer ile yönetilir — seyreklik sorunu yok.
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
    private final Setting<Float>   targetRange     = new Setting<>("TargetRange", 64f, 5f, 128f);
    private final Setting<Boolean> onlyWhenFlying  = new Setting<>("OnlyWhenFlying", true);
    private final Setting<Boolean> followTarget    = new Setting<>("FollowTarget", true);
    private final Setting<Float>   followSpeed     = new Setting<>("FollowSpeed", 0.8f, 0.1f, 3.0f,
            v -> followTarget.getValue());
    private final Setting<Float>   orbitRadius     = new Setting<>("OrbitRadius", 4.0f, 1.0f, 15.0f,
            v -> followTarget.getValue());
    private final Setting<Boolean> interceptTarget = new Setting<>("InterceptTarget", true,
            v -> followTarget.getValue());
    /*
     * MinOrbitDistance: hedefe bu mesafeden yaklaşınca orbit açısı dondurulur.
     * orbitReady sıfırlanmaz — yeniden uzaklaşınca smooth devam eder, yaw snap olmaz.
     */
    private final Setting<Float> minOrbitDistance  = new Setting<>("MinOrbitDistance", 3.0f, 1.0f, 8.0f,
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
    private final Setting<Boolean>  sendRotPacket  = new Setting<>("SendRotationPacket", true,
            v -> bypassMode.getValue() != BypassMode.Off);

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

    private float lastSentYaw   = Float.NaN;
    private float lastSentPitch = Float.NaN;

    private boolean firstTick = false;

    /*
     * SwordRocketCycle adım sayacı.
     * 0 → kılıç vur, 1 → fişek at, 2 → kılıç vur, 3 → fişek at, 4 → kılıç vur, 5 → fişek at
     * Her adım bir fireRocket() çağrısında işlenir; burst kaç olursa olsun
     * sayaç kendi sırasını takip eder.
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
            /*
             * BUG FIX B: Hedef kaybolduğunda lastSentYaw/Pitch sıfırlanır.
             * Sıfırlanmazsa yeni hedef alındığında stale base yaw kullanılır
             * → delta küçük çıkar → ilk rotasyon paketi basılmayabilir.
             */
            orbitReady    = false;
            lastSentYaw   = Float.NaN;
            lastSentPitch = Float.NaN;
        }

        /* ── kılıç seçimi (cycle kapalıysa) ───────────────────────────── */
        /*
         * BUG FIX A: sendPacket kullanılır, queueOrSend değil.
         * Lag aktifken queueOrSend kılıç paketini kuyruğa alır ama
         * fireRocket() sendPacket ile roketi direkt gönderir.
         * Sunucu önce roketi, sonra kılıç slotunu görür → sıra bozuk.
         * sendPacket ile kılıç her zaman rocket'tan önce ulaşır.
         */
        if (!swordRocketCycle.getValue() && autoSharpestSword.getValue() && validTarget) {
            SearchInvResult sword = InventoryUtility.getHighestSharpnessSwordHotBar();
            if (sword.found() && mc.player.getInventory().selectedSlot != sword.slot())
                sendPacket(new UpdateSelectedSlotC2SPacket(sword.slot()));
        }

        /* ── kritik vuruş ──────────────────────────────────────────────── */
        /*
         * FIX 1: Elytra uçuşu sırasında PositionAndOnGround paketi GÖNDERİLMEZ.
         * Server elytra trajectory'sini doğrular; sahte pozisyon paketi
         * bu doğrulamayı geçemez → rubber-band → iniş.
         * isFallFlying() kontrolü doCritPacket() içinde de tekrarlanır (güvenlik katmanı).
         */
        if (autoCrit.getValue() && validTarget && !flying && critTimer.passedMs(200)) {
            doCritPacket();
            critTimer.reset();
        }

        /* ── ROCKET ────────────────────────────────────────────────────── */
        /*
         * Roket her zaman normal timer ile ateşlenir.
         * swordRocketCycle açıksa fireRocket() içinde kılıç-fişek-geri dönüş
         * yapılır. Bu sayede roket sıklığı düşmez.
         */
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
        if (fakeLag.getValue() && lagPhaseOn) lagQueue.addLast(packet);
        else sendPacket(packet);
    }

    private void flushLagQueue() {
        while (!lagQueue.isEmpty()) sendPacket(lagQueue.pollFirst());
    }

    /* ═══════════════════════════════════════════════════════════════════════
       ROTATION + ORBIT
       LookAndOnGround kullanılır — sadece rotasyon gönderir, pozisyon göndermez.
       Full paket pozisyon da gönderir ve yakın mesafede rubber-band'e yol açar.
    ═══════════════════════════════════════════════════════════════════════ */

    private void followAndOrbit(Entity target) {
        Vec3d tPos    = target.getPos();
        Vec3d tMotion = target.getVelocity();
        float radius  = orbitRadius.getValue();
        float speed   = followSpeed.getValue();

        /*
         * FIX 2: Hedefe çok yaklaşınca orbit açısı dondurulur ama orbitReady
         * SIFIRLANMAZ. Sıfırlanırsa bir sonraki uzaklaşmada smoothX/Y/Z aniden
         * raw pozisyona atlanır → ani yaw snap → elytra desync.
         * Sadece return ile çıkılır; smooth state korunur.
         */
        double distSq = PlayerUtility.squaredDistanceFromEyes(tPos);
        double minD   = minOrbitDistance.getValue();
        if (distSq < minD * minD) {
            return;
        }

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
        float rawPitch = MathHelper.clamp(
                cPitch + MathHelper.wrapDegrees(tPitch - cPitch) * lerpT, -90f, 90f);

        float finalYaw, finalPitch;
        if (bypassMode.getValue() != BypassMode.Off) {
            finalYaw   = (float)(rawYaw   - (rawYaw   - cYaw)   % gcd);
            finalPitch = (float)(rawPitch - (rawPitch - cPitch) % gcd);
            if (rotationNoise.getValue()) {
                float n  = noiseStrength.getValue();
                finalYaw  += (float)(Math.round(
                        (ThreadLocalRandom.current().nextFloat() * n * 2f - n) / gcd) * gcd);
                finalPitch = MathHelper.clamp(finalPitch + (float)(Math.round(
                        (ThreadLocalRandom.current().nextFloat() * n - n * 0.5f) / gcd) * gcd),
                        -90f, 90f);
            }
        } else {
            finalYaw   = rawYaw;
            finalPitch = rawPitch;
        }

        mc.player.setYaw(finalYaw);
        mc.player.setPitch(finalPitch);

        if (bypassMode.getValue() != BypassMode.Off && sendRotPacket.getValue()) {
            float baseYaw   = Float.isNaN(lastSentYaw)   ? cYaw   : lastSentYaw;
            float basePitch = Float.isNaN(lastSentPitch) ? cPitch : lastSentPitch;
            if (Math.abs(MathHelper.wrapDegrees(finalYaw   - baseYaw))   > 0.5f
             || Math.abs(MathHelper.wrapDegrees(finalPitch - basePitch)) > 0.3f) {
                /* Sadece rotasyon paketi — pozisyon paketi değil */
                queueOrSend(new PlayerMoveC2SPacket.LookAndOnGround(
                        finalYaw, finalPitch, mc.player.isOnGround()));
                lastSentYaw   = finalYaw;
                lastSentPitch = finalPitch;
            }
        }
    }

    /* ═══════════════════════════════════════════════════════════════════════
       KRİTİK VURUŞ
       FIX 1 (güvenlik katmanı): isFallFlying() kontrolü burada da yapılır.
       onPostSync'teki !flying kontrolü birincil guard'dır; bu ikincil.
       Elytra uçuşu sırasında server pozisyon paketini reddeder → iniş.
    ═══════════════════════════════════════════════════════════════════════ */

    private void doCritPacket() {
        if (mc.player.isInLava() || mc.player.isSubmergedInWater()) return;
        if (mc.player.isFallFlying()) return; // Elytra uçuşunda asla pozisyon paketi gönderilmez
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
       swordRocketCycle=true → sıralı 6 adımlı döngü:
         kılıç → fişek → kılıç → fişek → kılıç → fişek (tekrar)
       swordRocketCycle=false → normal silent swap
       Her iki durumda da client selectedSlot'a yazılmaz.
    ═══════════════════════════════════════════════════════════════════════ */

    private void fireRocket() {
        /* Fişek slotunu bul / hotbar'a taşı */
        SearchInvResult rocketHotbar = InventoryUtility.findItemInHotBar(Items.FIREWORK_ROCKET);
        int rocketSlot = rocketHotbar.slot();

        if (rocketSlot == -1) {
            if (!autoSwitchRocket.getValue()) return;
            SearchInvResult anywhere = InventoryUtility.findItemInInventory(Items.FIREWORK_ROCKET);
            if (!anywhere.found() || anywhere.isInHotBar()) return;
            int emptySlot = -1;
            for (int i = 0; i < 9; i++) {
                if (mc.player.getInventory().getStack(i).isEmpty()) { emptySlot = i; break; }
            }
            if (emptySlot == -1) return;
            clickSlot(anywhere.slot(), emptySlot, SlotActionType.SWAP);
            rocketSlot = emptySlot;
        }

        /* Oyuncunun görsel (client) slotu — değiştirilmez */
        int clientSlot = mc.player.getInventory().selectedSlot;

        if (swordRocketCycle.getValue() && autoSharpestSword.getValue()) {
            /*
             * Sıralı döngü — her fireRocket() çağrısı bir adım ilerler:
             *
             *   cycleStep 0 → kılıç paketi gönder (sunucu kılıcı görür, Aura vurur)
             *   cycleStep 1 → fişek ateşle (kılıç → fişek → client slot)
             *   cycleStep 2 → kılıç paketi gönder
             *   cycleStep 3 → fişek ateşle
             *   cycleStep 4 → kılıç paketi gönder
             *   cycleStep 5 → fişek ateşle → sıfırla (0'a dön)
             *
             * mc.player.getInventory().selectedSlot hiç değişmez.
             * Client selectedSlot'a ASLA yazılmaz.
             */
            SearchInvResult sword = InventoryUtility.getHighestSharpnessSwordHotBar();

            boolean isSwordStep  = (cycleStep % 2 == 0); // 0,2,4 → kılıç adımı
            boolean isRocketStep = (cycleStep % 2 == 1); // 1,3,5 → fişek adımı

            if (isSwordStep) {
                /* Kılıç adımı: sunucuya kılıç slotunu bildir, sonra client slotuna dön */
                if (sword.found() && sword.slot() != clientSlot) {
                    sendPacket(new UpdateSelectedSlotC2SPacket(sword.slot()));
                    sendPacket(new UpdateSelectedSlotC2SPacket(clientSlot));
                }
                cycleStep++;
                return; // Bu adımda fişek atılmaz
            }

            if (isRocketStep) {
                /* Fişek adımı: kılıç → [fişek x rocketPressCount] → client slotu */
                if (sword.found() && sword.slot() != rocketSlot) {
                    sendPacket(new UpdateSelectedSlotC2SPacket(sword.slot()));
                }
                sendPacket(new UpdateSelectedSlotC2SPacket(rocketSlot));
                int presses = rocketPressCount.getValue();
                for (int p = 0; p < presses; p++) {
                    sendSequencedPacket(id -> new PlayerInteractItemC2SPacket(
                            Hand.MAIN_HAND, id, mc.player.getYaw(), mc.player.getPitch()));
                }
                sendPacket(new UpdateSelectedSlotC2SPacket(clientSlot));
                cycleStep++;
                if (cycleStep > 5) cycleStep = 0; // 6 adım tamamlandı, sıfırla
            }
            return;
        }

        /* Normal silent swap */
        int presses = rocketPressCount.getValue();
        if (silentRockets.getValue()) {
            if (clientSlot != rocketSlot)
                sendPacket(new UpdateSelectedSlotC2SPacket(rocketSlot));
            for (int p = 0; p < presses; p++) {
                sendSequencedPacket(id -> new PlayerInteractItemC2SPacket(
                        Hand.MAIN_HAND, id, mc.player.getYaw(), mc.player.getPitch()));
            }
            if (clientSlot != rocketSlot)
                sendPacket(new UpdateSelectedSlotC2SPacket(clientSlot));
        } else {
            if (clientSlot != rocketSlot) {
                mc.player.getInventory().selectedSlot = rocketSlot;
                sendPacket(new UpdateSelectedSlotC2SPacket(rocketSlot));
            }
            for (int p = 0; p < presses; p++) {
                sendSequencedPacket(id -> new PlayerInteractItemC2SPacket(
                        Hand.MAIN_HAND, id, mc.player.getYaw(), mc.player.getPitch()));
            }
            if (clientSlot != rocketSlot) {
                mc.player.getInventory().selectedSlot = clientSlot;
                sendPacket(new UpdateSelectedSlotC2SPacket(clientSlot));
            }
        }
    }
}
