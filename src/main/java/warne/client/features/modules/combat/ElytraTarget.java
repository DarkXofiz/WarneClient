package warne.client.features.modules.combat;

import meteordevelopment.orbit.EventHandler;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.PlayerInteractItemC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;
import net.minecraft.util.Hand;
import net.minecraft.util.math.Box;
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

/**
 * LiquidBounce'un ElytraTarget modülünden (ElytraRotationProcessor,
 * ModuleElytraTarget, AutoFirework, TargetEntityMovementPrediction,
 * TargetRotatePosition) taşınan tüm mantık burada. LiquidBounce'a özel
 * altyapı sınıfları (RotationManager, TargetTracker, Chronometer, KillAura
 * entegrasyonu) senin projende olmadığı için, bunların eşdeğerleri mevcut
 * Setting/Timer/Aura.target yapına uyarlanarak yazıldı.
 */
public final class ElytraTarget extends Module {

    public enum CritMode { Paket, Katı }
    public enum HedefNoktası { Gözler, Merkez }

    // ---- Fişek (AutoFirework) ----
    private final Setting<Boolean> fişekBoost = new Setting<>("FişekBoost", true);
    private final Setting<Integer> fişekGecikmesiMin = new Setting<>("FişekGecikmesiMin", 160, 20, 3000,
            v -> fişekBoost.getValue());
    private final Setting<Integer> fişekGecikmesiMax = new Setting<>("FişekGecikmesiMax", 200, 20, 3000,
            v -> fişekBoost.getValue());
    private final Setting<Integer> fişekSalvası = new Setting<>("FişekSalvası", 2, 1, 10,
            v -> fişekBoost.getValue());
    private final Setting<Boolean> sessizFişek = new Setting<>("SessizFişek", true,
            v -> fişekBoost.getValue());
    private final Setting<Boolean> otomatikFişekGeç = new Setting<>("OtomatikFişekGeç", true,
            v -> fişekBoost.getValue());
    private final Setting<Float> maksimumHız = new Setting<>("MaksimumHız", 3.5f, 0.5f, 20.0f,
            v -> fişekBoost.getValue());
    // AutoFirework'teki "ExtraDistance": hedef bu mesafeden uzaktaysa daha
    // uzun (yavaş) cooldown kullanılır, yakınken daha kısa (hızlı) cooldown.
    private final Setting<Float> fişekEkstraMesafe = new Setting<>("FişekEkstraMesafe", 15f, 2f, 100f,
            v -> fişekBoost.getValue());
    private final Setting<Integer> fişekGecikmesiUzak = new Setting<>("FişekGecikmesiUzak", 500, 20, 3000,
            v -> fişekBoost.getValue());

    // ---- Takip / Rotasyon ----
    private final Setting<Boolean> sadeceUçarken = new Setting<>("SadeceUçarken", true);
    private final Setting<Boolean> hedefTakip    = new Setting<>("HedefTakip", true);

    private final Setting<Float> yawHızı   = new Setting<>("YawHızı", 45.0f, 5.0f, 180.0f,
            v -> hedefTakip.getValue());
    private final Setting<Float> pitchHızı = new Setting<>("PitchHızı", 35.0f, 5.0f, 180.0f,
            v -> hedefTakip.getValue());

    // "Sharp": LiquidBounce'taki Sharp Rotations - hızları %50 artırır.
    private final Setting<Boolean> keskinRotasyon = new Setting<>("KeskinRotasyon", false,
            v -> hedefTakip.getValue());

    // Periyodik hızlanma dalgası (LiquidBounce'taki shouldBoost/smoothBoost).
    // Rotasyonun sürekli aynı hızda "donuk" görünmesini önler.
    private final Setting<Boolean> dalgalıHızlanma = new Setting<>("DalgalıHızlanma", true,
            v -> hedefTakip.getValue());

    // Çok küçük açı farklarında eklenen organik mikro-titreşim.
    private final Setting<Boolean> mikroAyarlama = new Setting<>("MikroAyarlama", true,
            v -> hedefTakip.getValue());

    private final Setting<HedefNoktası> hedefNoktası = new Setting<>("HedefNoktası", HedefNoktası.Gözler,
            v -> hedefTakip.getValue());

    private final Setting<Boolean> önünGeç = new Setting<>("ÖnünGeç", true,
            v -> hedefTakip.getValue());
    private final Setting<Float> tahminÇarpanı = new Setting<>("TahminÇarpanı", 2.0f, 0.5f, 3.0f,
            v -> önünGeç.getValue());

    // "AutoDistance": hedef ideal mesafeden yakınsa, bakılan noktayı biraz
    // geriye/uzağa iterek sürekli aynı noktaya kilitlenip "yapışmayı" önler.
    private final Setting<Boolean> otomatikMesafe = new Setting<>("OtomatikMesafe", true,
            v -> hedefTakip.getValue());
    private final Setting<Float> idealMesafe = new Setting<>("İdealMesafe", 10f, 2f, 30f,
            v -> otomatikMesafe.getValue());

    // "Safe": önde gerçek bir blok çarpışma riski varsa hafif yukarı it.
    private final Setting<Boolean> güvenliUçuş = new Setting<>("GüvenliUçuş", true);

    private final Setting<Boolean> hedefKilidi = new Setting<>("HedefKilidi", true);

    private final Setting<Boolean>  otoCrit  = new Setting<>("OtoCrit", true);
    private final Setting<CritMode> critModu = new Setting<>("CritModu", CritMode.Paket,
            v -> otoCrit.getValue());

    private final Setting<Boolean> otoKılıç = new Setting<>("OtoKılıç", true);

    private final Timer rocketTimer = new Timer();
    private final Timer critTimer   = new Timer();

    private int   slotToRestore = 0;
    private float lastSentYaw   = Float.NaN;
    private float lastSentPitch = Float.NaN;

    private Entity kilitliHedef = null;
    private boolean sıradaFişek = false;

    public ElytraTarget() {
        super("ElytraTarget", Category.COMBAT);
    }

    @Override
    public void onEnable() {
        lastSentYaw   = Float.NaN;
        lastSentPitch = Float.NaN;
        kilitliHedef  = null;
        sıradaFişek   = false;
        rocketTimer.reset();
        critTimer.reset();
        if (mc.player != null) slotToRestore = mc.player.getInventory().selectedSlot;
    }

    @Override
    public void onDisable() {
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

        if (uçuyor && güvenliUçuş.getValue()) {
            Box tahminiKutu = mc.player.getBoundingBox().offset(mc.player.getVelocity());
            boolean çarpışmaVar = mc.world.getBlockCollisions(mc.player, tahminiKutu).iterator().hasNext();
            if (çarpışmaVar) {
                Vec3d v = mc.player.getVelocity();
                mc.player.setVelocity(v.x, v.y + 0.1, v.z);
            }
        }

        if (uçuyor) {
            if (hedefTakip.getValue() && hedefVar) {
                followTarget(hedef);
            } else {
                lastSentYaw   = Float.NaN;
                lastSentPitch = Float.NaN;
            }

            if (otoCrit.getValue() && hedefVar && critTimer.passedMs(200)) {
                doCritSwing();
                critTimer.reset();
            }
        } else {
            if (otoCrit.getValue() && hedefVar && critTimer.passedMs(200)) {
                doCritPacket();
                critTimer.reset();
            }
        }

        handleKılıçVeFişek(hedef, hedefVar, uçuyor);
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

    /**
     * LiquidBounce'un ElytraRotationProcessor.process() mantığının birebir
     * uyarlaması: sabit açısal hız + periyodik sinüs-dalgası hızlanma +
     * hedef arkadaysa ekstra hız + çok yakın açılarda mikro-titreşim.
     */
    private void followTarget(Entity target) {
        Vec3d hedefNoktasıPos = hedefNoktası.getValue() == HedefNoktası.Merkez
                ? target.getPos().add(0, target.getHeight() / 2.0, 0)
                : (target instanceof LivingEntity le ? le.getEyePos()
                        : target.getPos().add(0, target.getHeight() / 2.0, 0));

        Vec3d hedefMotion = target.getVelocity();

        Vec3d tahminiPos = hedefNoktasıPos;
        if (önünGeç.getValue() && target instanceof LivingEntity le && le.isFallFlying()) {
            tahminiPos = hedefNoktasıPos.add(hedefMotion.multiply(tahminÇarpanı.getValue()));
        }

        // AutoDistance: hedef ideal mesafeden yakınsa, bakılan noktayı
        // oyuncudan uzaklaştırarak sürekli aynı noktaya "yapışmayı" önler.
        if (otomatikMesafe.getValue()) {
            Vec3d playerPos = mc.player.getPos();
            Vec3d yön = tahminiPos.subtract(playerPos);
            double uzunluk = yön.length();
            if (uzunluk > 1e-4) {
                Vec3d birim = yön.multiply(1.0 / uzunluk);
                double ideal = idealMesafe.getValue();
                if (uzunluk < ideal) {
                    tahminiPos = tahminiPos.add(birim.multiply(uzunluk - ideal));
                }
            }
        }

        double eyeY = mc.player.getY() + mc.player.getEyeHeight(mc.player.getPose());

        double dx    = tahminiPos.x - mc.player.getX();
        double dy    = tahminiPos.y - eyeY;
        double dz    = tahminiPos.z - mc.player.getZ();
        double hDist = Math.sqrt(dx * dx + dz * dz);
        if (hDist < 1e-4 && Math.abs(dy) < 1e-4) return;

        float hedefYaw = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90f;
        float hedefPitch = MathHelper.clamp(
                (float) -Math.toDegrees(Math.atan2(dy, hDist)), -90f, 90f);

        float mevcutYaw   = mc.player.getYaw();
        float mevcutPitch = mc.player.getPitch();

        float deltaYaw   = MathHelper.wrapDegrees(hedefYaw   - mevcutYaw);
        float deltaPitch = MathHelper.wrapDegrees(hedefPitch - mevcutPitch);
        float fark       = (float) Math.sqrt(deltaYaw * deltaYaw + deltaPitch * deltaPitch);

        float baseYawHızı   = keskinRotasyon.getValue() ? yawHızı.getValue() * 1.5f   : yawHızı.getValue();
        float basePitchHızı = keskinRotasyon.getValue() ? pitchHızı.getValue() * 1.5f : pitchHızı.getValue();

        long şimdi = System.currentTimeMillis();

        // Periyodik dalgalı hızlanma: sabit 45°/tick yerine, zaman içinde
        // sinüs dalgasıyla yumuşakça hızlanıp yavaşlar — "donuk" hissi azalır.
        float hız = 1.2f;
        if (dalgalıHızlanma.getValue()) {
            boolean boostAn = Math.sin(şimdi / 300.0) > 0.8;
            float hızÇarpanı = boostAn ? 2.0f : 1.2f;
            float yumuşakBoost = boostAn
                    ? (float) (Math.sin((şimdi % 360) / 300.0 * Math.PI) * 0.8 + 1.2)
                    : 1.2f;
            hız = hızÇarpanı * yumuşakBoost;
        }

        // Hedef neredeyse tam arkadaysa (90°'den fazla dönüş gerekiyorsa)
        // ekstra yaw hızı ekle, yoksa dönüş çok yavaş/garip görünür.
        float arkaÇarpanı = 1.2f;
        if (Math.abs(deltaYaw) > 90.0f) {
            arkaÇarpanı = (float) (2.2 * Math.sin(şimdi / 150.0) * 0.2 + 1.0);
        }

        float yawSpeed   = baseYawHızı * hız * arkaÇarpanı;
        float pitchSpeed = basePitchHızı * hız;

        float moveYaw   = MathHelper.clamp(deltaYaw,   -yawSpeed,   yawSpeed);
        float movePitch = MathHelper.clamp(deltaPitch, -pitchSpeed, pitchSpeed);

        // Mikro-ayarlama: hedefe çok yakın açıdaysak (< 5°) küçük bir
        // organik titreşim ekle, tamamen sabit/donuk kalmasın.
        if (mikroAyarlama.getValue() && fark < 5.0f) {
            float mikro = (float) (Math.sin(şimdi / 80.0) * 0.08 + Math.cos(şimdi / 120.0) * 0.05);
            moveYaw   += mikro * 0.2f;
            movePitch += mikro * 0.8f;
        }

        float finalYaw   = mevcutYaw + moveYaw;
        float finalPitch = MathHelper.clamp(mevcutPitch + movePitch, -90f, 90f);

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

    /**
     * Kılıç ve fişeği sırayla tetikler. Fişek gecikmesi, AutoFirework'teki
     * gibi hem rastgele bir aralıktan (Min-Max) seçilir hem de hedef
     * FişekEkstraMesafe'den uzaktaysa daha yavaş (FişekGecikmesiUzak) çalışır.
     */
    private void handleKılıçVeFişek(Entity hedef, boolean hedefVar, boolean uçuyor) {
        boolean fişekAktif = uçuyor && fişekBoost.getValue()
                && !(sadeceUçarken.getValue() && !uçuyor)
                && hedefVar;
        boolean kılıçAktif = uçuyor && otoKılıç.getValue() && hedefVar;

        long gecikme = fişekGecikmesiMin.getValue();
        if (hedefVar) {
            double mesafeSq = PlayerUtility.squaredDistanceFromEyes(hedef.getPos());
            if (mesafeSq > (double) fişekEkstraMesafe.getValue() * fişekEkstraMesafe.getValue()) {
                gecikme = fişekGecikmesiUzak.getValue();
            } else {
                int min = Math.min(fişekGecikmesiMin.getValue(), fişekGecikmesiMax.getValue());
                int max = Math.max(fişekGecikmesiMin.getValue(), fişekGecikmesiMax.getValue());
                gecikme = min == max ? min : ThreadLocalRandom.current().nextInt(min, max + 1);
            }
        }

        if ((fişekAktif || kılıçAktif) && rocketTimer.passedMs(gecikme)) {
            boolean fişekSırası = sıradaFişek && fişekAktif;
            boolean kılıçSırası = !sıradaFişek && kılıçAktif;

            if (!fişekAktif) kılıçSırası = kılıçAktif;
            if (!kılıçAktif) fişekSırası = fişekAktif;

            if (fişekSırası) {
                double anHız = mc.player.getVelocity().length();
                boolean düşüyor = uçuyor && mc.player.getVelocity().y < -0.15;
                if (anHız > maksimumHız.getValue() && !düşüyor) {
                    // Hız zaten yüksek, bu turu atla ama sırayı bozma.
                } else {
                    int atıldı = 0;
                    for (int i = 0; i < fişekSalvası.getValue(); i++) {
                        if (fireRocket()) atıldı++;
                    }
                    if (atıldı > 0) {
                        rocketTimer.reset();
                        sıradaFişek = false;
                    }
                }
            } else if (kılıçSırası) {
                SearchInvResult kılıç = InventoryUtility.getHighestSharpnessSwordHotBar();
                if (kılıç.found() && slotToRestore != kılıç.slot()) {
                    sendPacket(new UpdateSelectedSlotC2SPacket(kılıç.slot()));
                    sendPacket(new UpdateSelectedSlotC2SPacket(slotToRestore));
                }
                rocketTimer.reset();
                sıradaFişek = true;
            }
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
