package warne.client.features.modules.combat;

import meteordevelopment.orbit.EventHandler;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.PlayerInteractItemC2SPacket;
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;
import net.minecraft.util.Hand;
import warne.client.events.impl.EventPostSync;
import warne.client.features.modules.Module;
import warne.client.setting.Setting;
import warne.client.utility.Timer;
import warne.client.utility.player.InventoryUtility;
import warne.client.utility.player.PlayerUtility;
import warne.client.utility.player.SearchInvResult;

/**
 * Basitleştirilmiş ElytraTarget: rotasyon/hedef takip tamamen kaldırıldı
 * (kullanıcı isteğiyle, ciddi takılmaya sebep olduğu için). Modül artık
 * sadece uçarken, tek bir slotta KILIÇ -> FİŞEK -> KILIÇ -> FİŞEK şeklinde
 * dönüşümlü olarak çalışıyor. Rotasyona hiç dokunmuyor, sadece envanter
 * slotunu değiştirip ilgili eylemi (vuruş / roket kullanımı) tetikliyor.
 */
public final class ElytraTarget extends Module {

    private final Setting<Boolean> hedefKilidi = new Setting<>("HedefKilidi", true);

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
        sıradaFişek = false;
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
        if (hedef == null) {
            sıradaFişek = false;
            return;
        }

        if (!döngüTimer.passedMs(geçişSüresi.getValue())) return;

        if (sıradaFişek) {
            if (kullanFişek()) {
                sıradaFişek = false;
                döngüTimer.reset();
            }
        } else {
            if (kullanKılıç()) {
                sıradaFişek = true;
                döngüTimer.reset();
            }
        }
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
     * Kılıcı geçici olarak seçip anında eski slota döner (sanal/anlık swap).
     * Gerçek envanter slotunu kalıcı değiştirmez, sadece "vuruş anı"
     * paketlerini doğru slotla göndermiş olur.
     */
    private boolean kullanKılıç() {
        SearchInvResult kılıç = InventoryUtility.getHighestSharpnessSwordHotBar();
        if (!kılıç.found()) return false;

        int mevcutSlot = mc.player.getInventory().selectedSlot;
        boolean swap = mevcutSlot != kılıç.slot();

        if (swap) sendPacket(new UpdateSelectedSlotC2SPacket(kılıç.slot()));
        if (swap) sendPacket(new UpdateSelectedSlotC2SPacket(mevcutSlot));

        return true;
    }

    /**
     * Fişeği aynı şekilde anlık slot değişimiyle kullanır: fişek slotuna
     * geçer, kullanım paketini gönderir, hemen eski slota döner.
     */
    private boolean kullanFişek() {
        SearchInvResult roket = InventoryUtility.findItemInHotBar(Items.FIREWORK_ROCKET);
        if (!roket.found()) return false;

        int mevcutSlot = mc.player.getInventory().selectedSlot;
        boolean swap = mevcutSlot != roket.slot();

        if (swap) sendPacket(new UpdateSelectedSlotC2SPacket(roket.slot()));
        sendSequencedPacket(id -> new PlayerInteractItemC2SPacket(
                Hand.MAIN_HAND, id, mc.player.getYaw(), mc.player.getPitch()));
        if (swap) sendPacket(new UpdateSelectedSlotC2SPacket(mevcutSlot));

        return true;
    }
}
