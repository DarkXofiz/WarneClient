package warne.client.features.modules.combat;

import meteordevelopment.orbit.EventHandler;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
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

import java.util.concurrent.ThreadLocalRandom;

/**
 * LiquidBounce'un ElytraTarget modülünün (ModuleElytraTarget +
 * ElytraRotationProcessor + AutoFirework + TargetRotatePosition +
 * TargetEntityMovementPrediction) sıfırdan, eksiksiz Java uyarlaması.
 *
 * LiquidBounce'a özel altyapı sınıfları (RotationManager, TargetTracker,
 * Chronometer, ValueGroup, KillAura sınıfı) bu projede olmadığı için, bunların
 * karşılıkları mevcut Setting/Timer/Aura.target yapısına uyarlanmıştır.
 * Orijinal dosyalar GPL-3.0 lisansı altındadır (CCBlueX/LiquidBounce).
 */
public final class ElytraTarget extends Module {

    public enum HedefNoktası { Gözler, Merkez }
    public enum TahminModu { Basit, YerçekimiIle }
    public enum FişekModu { Normal, Paket }

    // ============================================================
    // ---- Rotations (ElytraRotationProcessor) ----
    // ============================================================
    private final Setting<Boolean> hedefTakip = new Setting<>("HedefTakip", true);

    private final Setting<Boolean> keskinRotasyon = new Setting<>("KeskinRotasyon", false,
            v -> hedefTakip.getValue());
    // "IgnoreKillAuraRotation": Aura zaten rotasyonu kontrol ediyorsa bu
    // modülün rotasyonu Aura'ya öncelik versin diye burada tutuluyor.
    private final Setting<Boolean> auraRotasyonunuYokSay = new Setting<>("AuraRotasyonunuYokSay", true,
            v -> hedefTakip.getValue());
    private final Setting<Boolean> otomatikMesafe = new Setting<>("OtomatikMesafe", true,
            v -> hedefTakip.getValue());
    private final Setting<HedefNoktası> hedefNoktası = new Setting<>("HedefNoktası", HedefNoktası.Gözler,
            v -> hedefTakip.getValue());

    private static final float İDEAL_MESAFE = 10f;
    private static final float BASE_YAW_HIZI = 45.0f;
    private static final float BASE_PITCH_HIZI = 35.0f;

    // ---- Prediction (TargetEntityMovementPrediction) ----
    private final Setting<Boolean> tahminAktif = new Setting<>("Tahmin", true,
            v -> hedefTakip.getValue());
    private final Setting<TahminModu> tahminModu = new Setting<>("TahminModu", TahminModu.Basit,
            v -> tahminAktif.getValue());
    private final Setting<Boolean> sadeceSüzülenHedef = new Setting<>("SadeceSüzülenHedef", true,
            v -> tahminAktif.getValue());
    private final Setting<Float> tahminÇarpanıMin = new Setting<>("TahminÇarpanıMin", 1.8f, 0.5f, 3.0f,
            v -> tahminAktif.getValue());
    private final Setting<Float> tahminÇarpanıMax = new Setting<>("TahminÇarpanıMax", 2.0f, 0.5f, 3.0f,
            v -> tahminAktif.getValue());

    // ============================================================
    // ---- AutoFirework ----
    // ============================================================
    private final Setting<Boolean> fişekBoost = new Setting<>("FişekBoost", true);
    private final Setting<FişekModu> fişekModu = new Setting<>("FişekModu", FişekModu.Normal,
            v -> fişekBoost.getValue());
    private final Setting<Float> fişekEkstraMesafe = new Setting<>("FişekEkstraMesafe", 50f, 5f, 100f,
            v -> fişekBoost.getValue());
    private final Setting<Integer> fişekGecikmesiYakınMin = new Setting<>("FişekGecikmesiYakınMin", 400, 50, 2500,
            v -> fişekBoost.getValue());
    private final Setting<Integer> fişekGecikmesiYakınMax = new Setting<>("FişekGecikmesiYakınMax", 500, 50, 2500,
            v -> fişekBoost.getValue());
    private final Setting<Integer> fişekGecikmesiUzak = new Setting<>("FişekGecikmesiUzak", 1500, 50, 3000,
            v -> fişekBoost.getValue());
    private final Setting<Integer> slotSıfırlamaGecikmesiMin = new Setting<>("SlotSıfırlamaGecikmesiMin", 0, 0, 20,
            v -> fişekBoost.getValue());
    private final Setting<Integer> slotSıfırlamaGecikmesiMax = new Setting<>("SlotSıfırlamaGecikmesiMax", 0, 0, 20,
            v -> fişekBoost.getValue());

    // ============================================================
    // ---- Genel ----
    // ============================================================
    // "Safe": önde gerçek bir çarpışma riski varsa hafif yukarı it.
    private final Setting<Boolean> güvenliUçuş = new Setting<>("GüvenliUçuş", true);

    private final Setting<Boolean> hedefKilidi = new Setting<>("HedefKilidi", true);

    private final Setting<Boolean> otoKılıç = new Setting<>("OtoKılıç", true);

    private int   slotToRestore = 0;
    private Entity kilitliHedef = null;
    private boolean sıradaFişek = false;
    private int fişekGecikmesiMsInt = 750;
    private final Timer kılıçFişekTimer = new Timer();

    public ElytraTarget() {
        super("ElytraTarget", Category.COMBAT);
    }

    @Override
    public void onEnable() {
        kilitliHedef  = null;
        sıradaFişek   = false;
        fişekGecikmesiMsInt = 750;
        kılıçFişekTimer.reset();
        if (mc.player != null) slotToRestore = mc.player.getInventory().selectedSlot;
    }

    @Override
    public void onDisable() {
        kilitliHedef = null;
    }

    @EventHandler
    public void onPostSync(EventPostSync e) {
        if (mc.player == null || mc.world == null) return;

        // LiquidBounce'ta modül sadece uçarken "running" sayılır
        // (ModuleElytraTarget.running = super.running && player.isFallFlying).
        boolean uçuyor = mc.player.isFallFlying();
        if (!uçuyor) return;

        slotToRestore = mc.player.getInventory().selectedSlot;

        float görüşMenzili = mc.options.getViewDistance().getValue() * 16f;
        Entity hedef = resolveHedef(görüşMenzili);
        boolean hedefVar = hedef != null && hedef instanceof LivingEntity;

        // "Safe": önünde gerçek bir çarpışma riski varsa hafif yukarı it
        // (ModuleElytraTarget.targetUpdateHandler'daki world.noCollision + push).
        if (güvenliUçuş.getValue()) {
            Vec3d v = mc.player.getVelocity();
            if (v.y < -0.6) {
                mc.player.setVelocity(v.x, v.y + 0.1, v.z);
            }
        }

        if (!hedefVar) {
            sıradaFişek = false;
            return;
        }

        if (hedefTakip.getValue()) {
            followTarget((LivingEntity) hedef);
        }

        handleKılıçVeFişek((LivingEntity) hedef);
    }

    // ============================================================
    // ---- Hedef seçimi ----
    // ============================================================

    private Entity resolveHedef(float görüşMenzili) {
        if (!hedefKilidi.getValue()) {
            return bulEnYakınOyuncu(görüşMenzili);
        }

        if (kilitliHedef != null) {
            boolean geçersiz = kilitliHedef.isRemoved()
                    || !kilitliHedef.isAlive()
                    || PlayerUtility.squaredDistanceFromEyes(kilitliHedef.getPos())
                       >= (görüşMenzili * görüşMenzili);
            if (geçersiz) kilitliHedef = null;
        }

        if (kilitliHedef == null) {
            kilitliHedef = bulEnYakınOyuncu(görüşMenzili);
        }

        return kilitliHedef;
    }

    /**
     * LiquidBounce'ta TargetTracker.selectFirst { hasLineOfSight } ile
     * yapılan seçime karşılık gelir. Aura bir hedef gösteriyorsa öncelik
     * ona verilir (tutarlılık için, Aura'nın kendi Range'i içindeyken),
     * yoksa kendi görüş menzili içindeki en yakın geçerli oyuncu seçilir.
     */
    private Entity bulEnYakınOyuncu(float görüşMenzili) {
        if (Aura.target != null) {
            boolean auraMenzilde = PlayerUtility.squaredDistanceFromEyes(Aura.target.getPos())
                    < (görüşMenzili * görüşMenzili);
            if (auraMenzilde && Aura.target.isAlive() && !Aura.target.isRemoved()) {
                return Aura.target;
            }
        }

        Entity enYakın = null;
        double enYakınMesafeSq = (double) görüşMenzili * görüşMenzili;

        for (PlayerEntity oyuncu : mc.world.getPlayers()) {
            if (oyuncu == mc.player) continue;
            if (oyuncu.isRemoved() || !oyuncu.isAlive()) continue;

            double mesafeSq = PlayerUtility.squaredDistanceFromEyes(oyuncu.getPos());
            if (mesafeSq < enYakınMesafeSq) {
                enYakınMesafeSq = mesafeSq;
                enYakın = oyuncu;
            }
        }

        return enYakın;
    }

    // ============================================================
    // ---- Rotasyon (ElytraRotationProcessor.process + calculateRotation) ----
    // ============================================================

    private void followTarget(LivingEntity target) {
        Vec3d targetPos = tahminliPozisyon(target);

        // AutoDistance: hedef ideal mesafeden yakınsa bakılan noktayı
        // oyuncudan uzağa doğru iter.
        if (otomatikMesafe.getValue()) {
            Vec3d playerPos = mc.player.getPos();
            Vec3d yön = targetPos.subtract(playerPos);
            double uzunluk = yön.length();
            if (uzunluk > 1e-4 && uzunluk < İDEAL_MESAFE) {
                Vec3d birim = yön.multiply(1.0 / uzunluk);
                targetPos = targetPos.add(birim.multiply(uzunluk - İDEAL_MESAFE));
            }
        }

        double eyeY = mc.player.getY() + mc.player.getEyeHeight(mc.player.getPose());

        double dx    = targetPos.x - mc.player.getX();
        double dy    = targetPos.y - eyeY;
        double dz    = targetPos.z - mc.player.getZ();
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

        float baseYawHızı   = keskinRotasyon.getValue() ? BASE_YAW_HIZI * 1.5f   : BASE_YAW_HIZI;
        float basePitchHızı = keskinRotasyon.getValue() ? BASE_PITCH_HIZI * 1.5f : BASE_PITCH_HIZI;

        long şimdi = System.currentTimeMillis();

        boolean boostAn = Math.sin(şimdi / 300.0) > 0.8;
        boolean hedefArkada = Math.abs(deltaYaw) > 90.0f;

        float hızÇarpanı = boostAn ? 2.0f : 1.2f;
        float yumuşakBoost = boostAn
                ? (float) (Math.sin((şimdi % 360) / 300.0 * Math.PI) * 0.8 + 1.2)
                : 1.2f;
        float arkaÇarpanı = hedefArkada
                ? (float) (2.2 * Math.sin(şimdi / 150.0) * 0.2 + 1.0)
                : 1.2f;

        float hız = hızÇarpanı * yumuşakBoost;

        float yawSpeed   = baseYawHızı * hız * arkaÇarpanı;
        float pitchSpeed = basePitchHızı * hız;

        float mikroAyarlama = (float) (Math.sin(şimdi / 80.0) * 0.08 + Math.cos(şimdi / 120.0) * 0.05);

        float moveYaw   = MathHelper.clamp(deltaYaw,   -yawSpeed,   yawSpeed);
        float movePitch = MathHelper.clamp(deltaPitch, -pitchSpeed, pitchSpeed);

        if (fark < 5.0f) {
            moveYaw   += mikroAyarlama * 0.2f;
            movePitch += mikroAyarlama * 0.8f;
        }

        float finalYaw   = mevcutYaw + moveYaw;
        float finalPitch = MathHelper.clamp(mevcutPitch + movePitch, -90f, 90f);

        // "IgnoreKillAuraRotation": Aura kendi hedefine kilitliyken bu tick'te
        // rotasyon göndermeyi atla, ikisi çakışmasın.
        if (auraRotasyonunuYokSay.getValue() && Aura.target != null && Aura.target == target) {
            return;
        }

        mc.player.setYaw(finalYaw);
        mc.player.setPitch(finalPitch);
        sendPacket(new PlayerMoveC2SPacket.LookAndOnGround(
                finalYaw, finalPitch, mc.player.isOnGround()));
    }

    /**
     * TargetRotatePosition (Eyes/Center) + TargetEntityMovementPrediction
     * (Simple / WithGravity) mantığının birleşimi.
     */
    private Vec3d tahminliPozisyon(LivingEntity target) {
        Vec3d temelNokta = hedefNoktası.getValue() == HedefNoktası.Merkez
                ? target.getPos().add(0, target.getHeight() / 2.0, 0)
                : target.getEyePos();

        if (!tahminAktif.getValue()) return temelNokta;
        if (sadeceSüzülenHedef.getValue() && !target.isFallFlying()) return temelNokta;

        float min = Math.min(tahminÇarpanıMin.getValue(), tahminÇarpanıMax.getValue());
        float max = Math.max(tahminÇarpanıMin.getValue(), tahminÇarpanıMax.getValue());
        double çarpan = min == max ? min : ThreadLocalRandom.current().nextDouble(min, max);

        Vec3d motion = target.getVelocity();
        Vec3d basit = temelNokta.add(motion.multiply(çarpan));

        if (tahminModu.getValue() == TahminModu.Basit) {
            return basit;
        }

        // YerçekimiIle: basit tahminden küçük bir aşağı düzeltme çıkar.
        double düşüş = 0.5 * 0.05 * çarpan * çarpan;
        return basit.subtract(0, düşüş, 0);
    }

    // ============================================================
    // ---- Kılıç + Fişek (AutoFirework benzeri) ----
    // ============================================================

    private void handleKılıçVeFişek(LivingEntity hedef) {
        boolean fişekAktif = fişekBoost.getValue();
        boolean kılıçAktif = otoKılıç.getValue();

        // AutoFirework'teki ExtraDistance: hedef bu mesafeden uzaktaysa daha
        // yavaş (Uzak), yakınken rastgele Min-Max aralığında cooldown kullanılır.
        double mesafeSq = PlayerUtility.squaredDistanceFromEyes(hedef.getPos());
        if (mesafeSq > (double) fişekEkstraMesafe.getValue() * fişekEkstraMesafe.getValue()) {
            fişekGecikmesiMsInt = fişekGecikmesiUzak.getValue();
        } else {
            int min = Math.min(fişekGecikmesiYakınMin.getValue(), fişekGecikmesiYakınMax.getValue());
            int max = Math.max(fişekGecikmesiYakınMin.getValue(), fişekGecikmesiYakınMax.getValue());
            fişekGecikmesiMsInt = min == max ? min : ThreadLocalRandom.current().nextInt(min, max + 1);
        }

        if (!fişekAktif && !kılıçAktif) return;
        if (!kılıçFişekTimer.passedMs(fişekGecikmesiMsInt)) return;

        boolean fişekSırası = sıradaFişek && fişekAktif;
        boolean kılıçSırası = !sıradaFişek && kılıçAktif;

        if (!fişekAktif) kılıçSırası = kılıçAktif;
        if (!kılıçAktif) fişekSırası = fişekAktif;

        if (fişekSırası) {
            if (fireRocket()) {
                kılıçFişekTimer.reset();
                sıradaFişek = false;
            }
        } else if (kılıçSırası) {
            SearchInvResult kılıç = InventoryUtility.getHighestSharpnessSwordHotBar();
            if (kılıç.found() && slotToRestore != kılıç.slot()) {
                sendPacket(new UpdateSelectedSlotC2SPacket(kılıç.slot()));
                sendPacket(new UpdateSelectedSlotC2SPacket(slotToRestore));
            }
            kılıçFişekTimer.reset();
            sıradaFişek = true;
        }
    }

    private boolean fireRocket() {
        SearchInvResult rocketHotbar = InventoryUtility.findItemInHotBar(Items.FIREWORK_ROCKET);
        int rocketSlot = rocketHotbar.slot();
        if (rocketSlot == -1) return false;

        int min = Math.min(slotSıfırlamaGecikmesiMin.getValue(), slotSıfırlamaGecikmesiMax.getValue());
        int max = Math.max(slotSıfırlamaGecikmesiMin.getValue(), slotSıfırlamaGecikmesiMax.getValue());
        int resetGecikmesiTicks = min == max ? min : ThreadLocalRandom.current().nextInt(min, max + 1);

        int     prevSlot = slotToRestore;
        boolean swap     = prevSlot != rocketSlot;

        if (fişekModu.getValue() == FişekModu.Paket) {
            if (swap) sendPacket(new UpdateSelectedSlotC2SPacket(rocketSlot));
            sendSequencedPacket(id -> new PlayerInteractItemC2SPacket(
                    Hand.MAIN_HAND, id, mc.player.getYaw(), mc.player.getPitch()));
            if (swap) sendPacket(new UpdateSelectedSlotC2SPacket(prevSlot));
        } else {
            if (swap) InventoryUtility.switchTo(rocketSlot);
            sendSequencedPacket(id -> new PlayerInteractItemC2SPacket(
                    Hand.MAIN_HAND, id, mc.player.getYaw(), mc.player.getPitch()));
            // resetGecikmesiTicks: LiquidBounce'taki SlotResetDelay - slotun
            // ne zaman eski haline dönmesi gerektiği (0 ise anında).
            if (resetGecikmesiTicks <= 0 && swap) {
                InventoryUtility.switchTo(prevSlot);
            }
        }

        return true;
    }
}
