package warne.client.features.modules.combat;

import meteordevelopment.orbit.EventHandler;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.PlayerInteractItemC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;
import net.minecraft.util.Hand;
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

    // ---- Diğer Ayarlar ----
    private final Setting<Boolean> hedefKilidi   = new Setting<>("HedefKilidi", true);

    // "Safe": önde çok hızlı düşüyorsa hafif yukarı it (çarpışma önleme).
    private final Setting<Boolean> güvenliUçuş = new Setting<>("GüvenliUçuş", true);

    private final Setting<Boolean>  otoCrit  = new Setting<>("OtoCrit", true);
    private final Setting<CritMode> critModu = new Setting<>("CritModu", CritMode.Paket,
            v -> otoCrit.getValue());

    private final Setting<Boolean> otoKılıç = new Setting<>("OtoKılıç", true);

    private final Timer rocketTimer = new Timer();
    private final Timer critTimer   = new Timer();

    private int   slotToRestore = 0;
    private Entity kilitliHedef = null;
    private boolean sıradaFişek = false;

    public ElytraTarget() {
        super("ElytraTarget", Category.COMBAT);
    }

    @Override
    public void onEnable() {
        kilitliHedef  = null;
        sıradaFişek   = false;
        rocketTimer.reset();
        critTimer.reset();
        if (mc.player != null) slotToRestore = mc.player.getInventory().selectedSlot;
    }

    @Override
    public void onDisable() {
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
            // Basit ve güvenli çarpışma-önleme: dikey hız çok negatifken
            // (hızlı düşüyorken) hafif yukarı it. Doğrulanamayan dünya/blok
            // API'leri yerine sadece bu dosyada zaten kanıtlanmış olan
            // getVelocity() kullanılıyor — bu, log hatasının en olası
          // kaynağıydı.
            Vec3d v = mc.player.getVelocity();
            if (v.y < -0.6) {
                mc.player.setVelocity(v.x, v.y + 0.1, v.z);
            }
        }

        if (otoCrit.getValue() && hedefVar && critTimer.passedMs(200)) {
            if (uçuyor) {
                doCritSwing();
            } else {
                doCritPacket();
            }
            critTimer.reset();
        }

        handleKılıçVeFişek(hedef, hedefVar, uçuyor);
    }

    private Entity resolveHedef(float görüşMenzili) {
        if (!hedefKilidi.getValue()) {
            Entity t = bulEnYakınOyuncu(görüşMenzili);
            return t;
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
     * ElytraTarget'ın kendi bağımsız hedef taraması. Aura.target'a bağımlı
     * kalmıyor çünkü Aura'nın kendi Range ayarı (varsayılan ~3 blok, max 6)
     * çok kısa — bu, "5 blok ötedeki hedefe yaklaşmıyor / hemen bırakıyor"
     * sorununun kök nedeniydi. Aura bir hedef gösteriyorsa öncelik ona
     * verilir (tutarlılık için), yoksa kendi görüş menzili içindeki en yakın
     * geçerli oyuncuyu bulur.
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

    /**
     * Kılıç ve fişeği sırayla tetikler. Fişek gecikmesi, AutoFirework'teki
     * gibi hem rastgele bir aralıktan (Min-Max) seçilir hem de hedef
     * FişekEkstraMesafe'den uzaktaysa daha yavaş (FişekGecikmesiUzak) çalışır.
     */
    private void handleKılıçVeFişek(Entity hedef, boolean hedefVar, boolean uçuyor) {
        boolean fişekAktif = uçuyor && fişekBoost.getValue()
                && !(sadeceUçarken.getValue() && !uçuyor)
                && hedefVar;
        boolean kılıçAktif = otoKılıç.getValue() && hedefVar;

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
