package warne.client.features.modules.combat;

import meteordevelopment.orbit.EventHandler;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Items;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.c2s.play.PlayerInteractItemC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;
import net.minecraft.util.Hand;
import warne.client.events.impl.PacketEvent;
import warne.client.events.impl.EventPostSync;
import warne.client.features.modules.Module;
import warne.client.setting.Setting;
import warne.client.utility.Timer;
import warne.client.utility.player.InventoryUtility;
import warne.client.utility.player.PlayerUtility;
import warne.client.utility.player.SearchInvResult;

import java.util.Iterator;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * ElytraTarget — Kılıç/Roket dönüşüm modülü + FakeLag bypass sistemi.
 *
 * FakeLag: sadece PlayerMoveC2SPacket paketlerini tutar; slot/interact
 * paketleri hiçbir zaman tutulmaz. Serbest bırakma sırasında kendi
 * gönderdiği paketleri tekrar yakalamamak için "serbest" bayrağı kullanır.
 */
public final class ElytraTarget extends Module {

    // ═══ MEVCUT AYARLAR ══════════════════════════════════════════════════════

    private final Setting<Boolean> hedefKilidi =
            new Setting<>("HedefKilidi", true);

    private final Setting<Integer> geçişSüresi =
            new Setting<>("GeçişSüresi", 250, 100, 2000);

    // ═══ FAKELAG AYARLARI ════════════════════════════════════════════════════

    private final Setting<Boolean> fakeLagAktif =
            new Setting<>("FakeLag", false);

    /**
     * Milisaniye cinsinden gecikme penceresi.
     * Normal: 80-200 ms arası önerilir.
     * Bypass: 100-250 ms arası AC'ye en doğal görünen aralıktır.
     */
    private final Setting<Integer> fakeLagMs =
            new Setting<>("FakeLagGecikmesi", 150, 50, 1000,
                    v -> fakeLagAktif.getValue());

    private final Setting<FakeLagMod> fakeLagMod =
            new Setting<>("FakeLagModu", FakeLagMod.Bypass,
                    v -> fakeLagAktif.getValue());

    /** Kuyrukta tutulabilecek maksimum paket sayısı. Dolunca anında boşaltılır. */
    private final Setting<Integer> paketLimiti =
            new Setting<>("PaketLimiti", 60, 10, 300,
                    v -> fakeLagAktif.getValue());

    /**
     * Bypass modunda serbest bırakma aralığına eklenen rastgele varyans
     * (±ms). AC'ye gecikme örüntüsü insani görünür.
     */
    private final Setting<Integer> varyans =
            new Setting<>("Varyans", 20, 0, 80,
                    v -> fakeLagAktif.getValue());

    // ═══ FAKELAG ENUMERATİON ═════════════════════════════════════════════════

    public enum FakeLagMod {
        /**
         * Normal: gecikme dolunca tüm kuyruğu tek seferde boşaltır.
         * Basit lag simülasyonu.
         */
        Normal,

        /**
         * Bypass: her paketi kendi bireysel zamanına göre serbest bırakır,
         * tick başına 1 paket ile sunucuya doğal bir akış gönderir.
         * GrimAC ve benzeri AC'lerde en güvenli mod.
         */
        Bypass,

        /**
         * Agresif: gecikme dolunca kuyruğun tamamını patlatır.
         * Daha sert lag efekti, saldırı anında kullanım için.
         */
        Agresif
    }

    // ═══ FAKELAG İÇ YAPI ═════════════════════════════════════════════════════

    private static final class TutulmuşPaket {
        final Packet<?> paket;
        final long      zamanDamgası;

        TutulmuşPaket(Packet<?> p) {
            this.paket        = p;
            this.zamanDamgası = System.currentTimeMillis();
        }
    }

    private final ConcurrentLinkedDeque<TutulmuşPaket> paketKuyruğu =
            new ConcurrentLinkedDeque<>();

    private final Timer fakeLagTimer = new Timer();

    /**
     * Serbest bırakma anında kendi gönderdiğimiz paketi tekrar yakalamamak
     * için kullanılan bayrak. volatile: event thread'den de erişilebilir.
     */
    private volatile boolean serbest = false;

    private long rastgeleEkleme = 0L;

    // ═══ MEVCUT İÇ DURUM ═════════════════════════════════════════════════════

    private final Timer   döngüTimer  = new Timer();
    private       boolean sıradaFişek = false;
    private       Entity  kilitliHedef = null;

    // ═══════════════════════════════════════════════════════════════════════

    public ElytraTarget() {
        super("ElytraTarget", Category.COMBAT);
    }

    @Override
    public void onEnable() {
        sıradaFişek  = false;
        kilitliHedef = null;
        serbest      = false;
        döngüTimer.reset();
        fakeLagTimer.reset();
        paketKuyruğu.clear();
        yeniRastgele();
    }

    @Override
    public void onDisable() {
        kilitliHedef = null;
        // Devre dışı kalınca kuyruktaki tüm paketleri sunucuya gönder;
        // aksi hâlde oyuncu position desync'e düşer.
        boşalt();
        paketKuyruğu.clear();
    }

    // ═══ FAKELAG — PAKET YAKALAMA ════════════════════════════════════════════

    @EventHandler
    public void onPacketSend(PacketEvent.Send e) {
        if (!fakeLagAktif.getValue()) return;
        if (serbest)                  return; // kendi serbest bıraktığımız paketi tekrar tutma
        if (mc.player == null)        return;

        // Sadece hareket paketlerini tut.
        // Slot değişimi ve interact paketleri (kılıç/roket) HİÇBİR ZAMAN tutulmaz;
        // bu paketlerin gecikmesi modülün kendi mantığını bozar ve sunucuda
        // yanlış slot-state'e yol açar.
        if (!(e.getPacket() instanceof PlayerMoveC2SPacket)) return;

        // Kuyruk doldu: anında boşalt, bu paketi normal geçir
        if (paketKuyruğu.size() >= paketLimiti.getValue()) {
            boşalt();
            return;
        }

        e.cancel();
        paketKuyruğu.addLast(new TutulmuşPaket(e.getPacket()));
    }

    // ═══ ANA DÖNGÜ ═══════════════════════════════════════════════════════════

    @EventHandler
    public void onPostSync(EventPostSync e) {
        if (mc.player == null || mc.world == null) return;

        // FakeLag serbest bırakma (uçuyor olsun ya da olmasın)
        if (fakeLagAktif.getValue()) işleFakeLag();

        if (!mc.player.isFallFlying()) return;

        float görüşMenzili = mc.options.getViewDistance().getValue() * 16f;
        Entity hedef = resolveHedef(görüşMenzili);
        if (hedef == null) {
            sıradaFişek = false;
            return;
        }

        if (!döngüTimer.passedMs(geçişSüresi.getValue())) return;

        if (sıradaFişek) {
            if (kullanFişek()) { sıradaFişek = false; döngüTimer.reset(); }
        } else {
            if (kullanKılıç()) { sıradaFişek = true;  döngüTimer.reset(); }
        }
    }

    // ═══ FAKELAG MANTIK ══════════════════════════════════════════════════════

    private void işleFakeLag() {
        long şuAnki  = System.currentTimeMillis();
        long gecikme = fakeLagMs.getValue() + rastgeleEkleme;

        switch (fakeLagMod.getValue()) {

            case Normal -> {
                // Gecikme penceresi dolunca tüm kuyruğu bir seferde gönder
                if (fakeLagTimer.passedMs(gecikme)) {
                    boşalt();
                    fakeLagTimer.reset();
                    yeniRastgele();
                }
            }

            case Bypass -> {
                // Her paketin bireysel zamanına bak;
                // gecikme süresi geçen paketi serbest bırak, tick başına 1 adet.
                // Bu, sunucuya tutarlı ama seyrek bir hareket akışı görünümü verir.
                Iterator<TutulmuşPaket> it = paketKuyruğu.iterator();
                while (it.hasNext()) {
                    TutulmuşPaket tp = it.next();
                    if (şuAnki - tp.zamanDamgası >= gecikme) {
                        it.remove();
                        gönder(tp.paket);
                        break; // tick başına 1 paket: doğal akış
                    }
                }
                // Rastgele varyansı periyodik olarak yenile
                if (fakeLagTimer.passedMs(gecikme + 30)) {
                    fakeLagTimer.reset();
                    yeniRastgele();
                }
            }

            case Agresif -> {
                // Gecikme dolunca tüm kuyruğu patlat (rubber-band efekti)
                if (fakeLagTimer.passedMs(gecikme)) {
                    boşalt();
                    fakeLagTimer.reset();
                    yeniRastgele();
                }
            }
        }
    }

    /** Kuyruktaki tüm paketleri sırayla gönderir ve kuyruğu temizler. */
    private void boşalt() {
        TutulmuşPaket tp;
        while ((tp = paketKuyruğu.pollFirst()) != null) {
            gönder(tp.paket);
        }
    }

    /**
     * Paketi network handler üzerinden doğrudan gönderir.
     * "serbest" bayrağı, bu paketin yakalama handler'ında tekrar tutulmasını engeller.
     */
    private void gönder(Packet<?> p) {
        if (mc.getNetworkHandler() == null) return;
        serbest = true;
        mc.getNetworkHandler().sendPacket(p);
        serbest = false;
    }

    /** [0, varyans*2) aralığında rastgele bir gecikme eklemesi üretir. */
    private void yeniRastgele() {
        int v = varyans.getValue();
        rastgeleEkleme = v > 0 ? (long)(Math.random() * v * 2) - v : 0L;
    }

    // ═══ HEDEF ÇÖZÜMLEME ═════════════════════════════════════════════════════

    private Entity resolveHedef(float görüşMenzili) {
        if (!hedefKilidi.getValue()) return bulEnYakınOyuncu(görüşMenzili);

        if (kilitliHedef != null) {
            boolean geçersiz = kilitliHedef.isRemoved()
                    || !kilitliHedef.isAlive()
                    || PlayerUtility.squaredDistanceFromEyes(kilitliHedef.getPos())
                       >= (görüşMenzili * görüşMenzili);
            if (geçersiz) kilitliHedef = null;
        }

        if (kilitliHedef == null) kilitliHedef = bulEnYakınOyuncu(görüşMenzili);
        return kilitliHedef;
    }

    private Entity bulEnYakınOyuncu(float görüşMenzili) {
        if (Aura.target != null) {
            boolean auraMenzilde = PlayerUtility.squaredDistanceFromEyes(Aura.target.getPos())
                    < (görüşMenzili * görüşMenzili);
            if (auraMenzilde && Aura.target.isAlive() && !Aura.target.isRemoved())
                return Aura.target;
        }

        Entity enYakın          = null;
        double enYakınMesafeSq  = (double) görüşMenzili * görüşMenzili;

        for (PlayerEntity oyuncu : mc.world.getPlayers()) {
            if (oyuncu == mc.player || oyuncu.isRemoved() || !oyuncu.isAlive()) continue;
            double mesafeSq = PlayerUtility.squaredDistanceFromEyes(oyuncu.getPos());
            if (mesafeSq < enYakınMesafeSq) {
                enYakınMesafeSq = mesafeSq;
                enYakın         = oyuncu;
            }
        }

        return enYakın;
    }

    // ═══ KILIÇ / FİŞEK ═══════════════════════════════════════════════════════

    private boolean kullanKılıç() {
        SearchInvResult kılıç = InventoryUtility.getHighestSharpnessSwordHotBar();
        if (!kılıç.found()) return false;

        int mevcutSlot = mc.player.getInventory().selectedSlot;
        boolean swap   = mevcutSlot != kılıç.slot();

        // Slot/interact paketleri FakeLag kuyruğuna girmez (onPacketSend'de filtreli).
        // sendPacket buradan doğrudan gider.
        if (swap) sendPacket(new UpdateSelectedSlotC2SPacket(kılıç.slot()));
        if (swap) sendPacket(new UpdateSelectedSlotC2SPacket(mevcutSlot));
        return true;
    }

    private boolean kullanFişek() {
        SearchInvResult roket = InventoryUtility.findItemInHotBar(Items.FIREWORK_ROCKET);
        if (!roket.found()) return false;

        int mevcutSlot = mc.player.getInventory().selectedSlot;
        boolean swap   = mevcutSlot != roket.slot();

        if (swap) sendPacket(new UpdateSelectedSlotC2SPacket(roket.slot()));
        sendSequencedPacket(id -> new PlayerInteractItemC2SPacket(
                Hand.MAIN_HAND, id, mc.player.getYaw(), mc.player.getPitch()));
        if (swap) sendPacket(new UpdateSelectedSlotC2SPacket(mevcutSlot));
        return true;
    }
}
