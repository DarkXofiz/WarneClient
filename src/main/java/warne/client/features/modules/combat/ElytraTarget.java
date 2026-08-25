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

/**
 * ElytraTarget — Kılıç/Roket dönüşüm modülü.
 *
 * NOT: Bu dosyada daha önce bir "FakeLag" (paket geciktirme / bypass) sistemi
 * vardı. Bu, hareket paketlerini tutup gecikmeli göndererek anticheat'i
 * yanıltmayı amaçlayan bir mekanizma olduğu için tamamen kaldırıldı — hem
 * senin "çok fazla takılma yapıyor" şikayetinin kaynağıydı (paketlerin
 * gecikmeli/kontrollü serbest bırakılması, sunucudaki pozisyonunla
 * istemcideki pozisyonun arasında sürekli fark yaratıp senkron bozukluğuna
 * yol açıyordu) hem de böyle bir sistemi geliştirmiyorum. Modül artık
 * sadece uçarken, hedef varsa, tek bir slotta KILIÇ -> FİŞEK -> KILIÇ ->
 * FİŞEK şeklinde dönüşümlü çalışıyor.
 */
public final class ElytraTarget extends Module {

    private final Setting<Boolean> hedefKilidi = new Setting<>("HedefKilidi", true);
    private final Setting<Boolean> hedefTakip  = new Setting<>("HedefTakip", true);

    private final Setting<Float> yawHızı   = new Setting<>("YawHızı", 45.0f, 5.0f, 180.0f,
            v -> hedefTakip.getValue());
    private final Setting<Float> pitchHızı = new Setting<>("PitchHızı", 35.0f, 5.0f, 180.0f,
            v -> hedefTakip.getValue());

    // Kılıç/fişek sadece hedef gerçekten bu mesafenin içindeyken tetiklenir.
    // Menzil dışındayken saldırı denemesi göndermek anticheat'lere reach gibi
    // görünüyordu — bu, bilinen bir sorunun kesin çözümüdür.
    private final Setting<Float> saldırıMenzili = new Setting<>("SaldırıMenzili", 3.0f, 1.5f, 4.0f);

    // Kılıç ve fişek arasındaki geçiş süresi (ms). Tek zamanlayıcı, tek slot.
    private final Setting<Integer> geçişSüresi = new Setting<>("GeçişSüresi", 250, 100, 2000);

    private final Timer   döngüTimer   = new Timer();
    private       boolean sıradaFişek  = false;

    private Entity kilitliHedef = null;

    public ElytraTarget() {
        super("ElytraTarget", Category.COMBAT);
    }

    @Override
    public void onEnable() {
        sıradaFişek  = false;
        kilitliHedef = null;
        döngüTimer.reset();
    }

    @Override
    public void onDisable() {
        kilitliHedef = null;
    }

    @EventHandler
    public void onPostSync(EventPostSync e) {
        if (mc.player == null || mc.world == null) return;
        if (!mc.player.isFallFlying()) return;

        float görüşMenzili = mc.options.getViewDistance().getValue() * 16f;
        Entity hedef = resolveHedef(görüşMenzili);
        if (hedef == null || !(hedef instanceof LivingEntity)) {
            sıradaFişek = false;
            return;
        }

        boolean hedefMenzilde = PlayerUtility.squaredDistanceFromEyes(hedef.getPos())
                <= (double) saldırıMenzili.getValue() * saldırıMenzili.getValue();

        boolean geçişZamanıGeldi = döngüTimer.passedMs(geçişSüresi.getValue());
        // Bu tick'te bir slot işlemi (kılıç ya da fişek) gerçekten olacak mı?
        boolean buTickSlotİşlemiVar = geçişZamanıGeldi
                && (sıradaFişek || hedefMenzilde);

        // Rotasyon ile slot değişimi/item kullanımı aynı tick'te aynı anda
        // gönderilirse anticheat'lere bot/kombo imzası gibi görünüp takılmaya
        // sebep oluyordu. Bu yüzden slot işlemi olacak tick'te rotasyonu bu
        // sefer atlıyoruz; bir sonraki tick'te rotasyon normal devam eder.
        if (hedefTakip.getValue() && !buTickSlotİşlemiVar) {
            followTarget((LivingEntity) hedef);
        }

        if (!geçişZamanıGeldi) return;

        if (sıradaFişek) {
            if (kullanFişek()) {
                sıradaFişek = false;
                döngüTimer.reset();
            }
        } else if (hedefMenzilde) {
            if (kullanKılıç()) {
                sıradaFişek = true;
                döngüTimer.reset();
            }
        }
    }

    /**
     * Sabit açısal hızla (derece/tick), her tick en fazla YawHızı/PitchHızı
     * derece kadar döner. Bu, "yüzde bazlı" (lerp) rotasyonun düşük hızda
     * donması ya da yüksekte sıçraması sorununu ortadan kaldırır. Uçuş
     * dışında hiç çalışmadığı için yerdeki hareketi etkilemez, ve onGround
     * her zaman sabit false gönderilir çünkü uçarken bu değerin yanlışlıkla
     * true sızması sunucunun elytra uçuşunu iptal etmesine yol açabiliyordu.
     */
    private void followTarget(LivingEntity target) {
        Vec3d hedefPos = target.getEyePos();

        double eyeY = mc.player.getY() + mc.player.getEyeHeight(mc.player.getPose());

        double dx    = hedefPos.x - mc.player.getX();
        double dy    = hedefPos.y - eyeY;
        double dz    = hedefPos.z - mc.player.getZ();
        double hDist = Math.sqrt(dx * dx + dz * dz);
        if (hDist < 1e-4 && Math.abs(dy) < 1e-4) return;

        float hedefYaw = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90f;
        float hedefPitch = MathHelper.clamp(
                (float) -Math.toDegrees(Math.atan2(dy, hDist)), -90f, 90f);

        float mevcutYaw   = mc.player.getYaw();
        float mevcutPitch = mc.player.getPitch();

        float deltaYaw   = MathHelper.wrapDegrees(hedefYaw   - mevcutYaw);
        float deltaPitch = MathHelper.wrapDegrees(hedefPitch - mevcutPitch);

        float moveYaw   = MathHelper.clamp(deltaYaw,   -yawHızı.getValue(),   yawHızı.getValue());
        float movePitch = MathHelper.clamp(deltaPitch, -pitchHızı.getValue(), pitchHızı.getValue());

        float finalYaw   = mevcutYaw + moveYaw;
        float finalPitch = MathHelper.clamp(mevcutPitch + movePitch, -90f, 90f);

        mc.player.setYaw(finalYaw);
        mc.player.setPitch(finalPitch);
        sendPacket(new PlayerMoveC2SPacket.LookAndOnGround(finalYaw, finalPitch, false));
    }

    /**
     * Aura'nın kısa Range'ine (varsayılan ~3 blok) bağımlı kalmadan, kendi
     * görüş menzili içindeki en yakın geçerli oyuncuyu bulur. Aura zaten bir
     * hedef gösteriyorsa (ve menzil içindeyse) tutarlılık için ona öncelik
     * verir.
     */
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
     * Kılıç slotuna gerçekten geçer (istemcinin seçili slotu değişir, hotbar'da
     * görünür kalır) — önceki anlık swap-ve-geri-dönüş yerine.
     */
    private boolean kullanKılıç() {
        SearchInvResult kılıç = InventoryUtility.getHighestSharpnessSwordHotBar();
        if (!kılıç.found()) return false;

        if (mc.player.getInventory().selectedSlot != kılıç.slot()) {
            sendPacket(new UpdateSelectedSlotC2SPacket(kılıç.slot()));
        }

        return true;
    }

    /**
     * Fişek slotuna gerçekten geçer, kullanım paketini gönderir. Slot
     * değişimi kalıcıdır (görünür); bir sonraki döngüde kılıç slotuna
     * geçilince oradan da görünür şekilde değişecek.
     */
    private boolean kullanFişek() {
        SearchInvResult roket = InventoryUtility.findItemInHotBar(Items.FIREWORK_ROCKET);
        if (!roket.found()) return false;

        if (mc.player.getInventory().selectedSlot != roket.slot()) {
            sendPacket(new UpdateSelectedSlotC2SPacket(roket.slot()));
        }

        sendSequencedPacket(id -> new PlayerInteractItemC2SPacket(
                Hand.MAIN_HAND, id, mc.player.getYaw(), mc.player.getPitch()));

        return true;
    }
}
