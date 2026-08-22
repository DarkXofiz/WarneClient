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

    /*   ROCKET AYARLARI   */
    private final Setting<Boolean> rocketBoost       = new Setting<>("RocketBoost", true);
    private final Setting<Boolean> instantFire       = new Setting<>("InstantFire", false,
            v -> rocketBoost.getValue());
    private final Setting<Integer> rocketDelay       = new Setting<>("RocketDelay", 5, 0, 300,
            v -> rocketBoost.getValue() && !instantFire.getValue());
    private final Setting<Integer> rocketBurst       = new Setting<>("RocketBurst", 2, 1, 10,
            v -> rocketBoost.getValue());
    private final Setting<Boolean> silentRockets      = new Setting<>("SilentRocketUsage", true,
            v -> rocketBoost.getValue());
    private final Setting<Boolean> autoSwitchRocket  = new Setting<>("AutoSwitchRocket", true,
            v -> rocketBoost.getValue());
    private final Setting<Boolean> alwaysBoost       = new Setting<>("AlwaysBoost", false,
            v -> rocketBoost.getValue());

    /*   HEDEF AYARLARI   */
    private final Setting<Float>   targetRange       = new Setting<>("TargetRange", 64f, 5f, 128f);
    private final Setting<Boolean> onlyWhenFlying    = new Setting<>("OnlyWhenFlying", true);
    private final Setting<Boolean> followTarget      = new Setting<>("FollowTarget", true);
    private final Setting<Float>   followSpeed       = new Setting<>("FollowSpeed", 0.8f, 0.1f, 3.0f,
            v -> followTarget.getValue());
    private final Setting<Float>   orbitRadius       = new Setting<>("OrbitRadius", 4.0f, 1.0f, 15.0f,
            v -> followTarget.getValue());
    private final Setting<Boolean> interceptTarget   = new Setting<>("InterceptTarget", true,
            v -> followTarget.getValue());

    /*   KRİTİK VURUŞ   */
    private final Setting<Boolean> autoCrit          = new Setting<>("AutoCrit", true);
    private final Setting<CritMode> critMode         = new Setting<>("CritMode", CritMode.Packet,
            v -> autoCrit.getValue());

    /*   KILIC AYARLARI   */
    private final Setting<Boolean> autoSharpestSword = new Setting<>("AutoSwitchToSharpestSword", true);

    private final Timer rocketTimer = new Timer();
    private final Timer critTimer   = new Timer();
    private double  orbitAngle    = 0;

    /* Log düşmesini önleyen EMA (Exponential Moving Average) orbit tamponu.
     * Orbit hedef noktası her tick ham olarak hesaplanır; ani sıçramaları
     * yutmak için smooth{X,Y,Z} değerlerine yavaşça kayar.
     * orbitSmoothed = false iken direkt yerleştirilir (enable'da reset edilir). */
    private double  smoothOrbitX  = 0;
    private double  smoothOrbitY  = 0;
    private double  smoothOrbitZ  = 0;
    private boolean orbitSmoothed = false;

    /* Tick başı slot snapshot: fireRocket() restore paketinde bunu kullanır.
     * autoSharpestSword veya başka bir işlem slot değiştirmiş olsa bile
     * oyuncunun o tick başındaki gerçek slotuna döner. */
    private int slotToRestore = 0;

    public ElytraTarget() {
        super("ElytraTarget", Category.COMBAT);
    }

    @Override
    public void onEnable() {
        orbitAngle    = 0;
        orbitSmoothed = false;
        if (mc.player != null) slotToRestore = mc.player.getInventory().selectedSlot;
    }

    @Override
    public void onDisable() {
        orbitSmoothed = false;
    }

    @EventHandler
    public void onPostSync(EventPostSync e) {
        if (mc.player == null || mc.world == null) return;

        /* Slot snapshot — her şeyden önce alınır. fireRocket() bu değeri kullanır.
         * autoSharpestSword veya orbit paketleri araya girmiş olsa bile
         * restore her zaman oyuncunun bu tick başındaki gerçek slotuna gider. */
        slotToRestore = mc.player.getInventory().selectedSlot;

        Entity target = Aura.target;
        boolean hasValidTarget = target != null
                && PlayerUtility.squaredDistanceFromEyes(target.getPos())
                   < (targetRange.getValue() * targetRange.getValue());

        // Elytra ile uçuyor muyuz? OnlyWhenFlying kapalıyken bile bu kontrol
        // ayrıca isFallFlying() olarak aşağıdaki her alt sisteme tek tek uygulanır,
        // aksi halde oyuncu yürürken de yaw/pitch zorla döndürülüyordu.
        boolean isFlying = mc.player.isFallFlying();
        if (onlyWhenFlying.getValue() && !isFlying) return;

        // Uçuş sırasında hedefe doğru aktif yönlendirme.
        // DÜZELTME: onlyWhenFlying kapalıyken bile bu her zaman gerçek uçuş
        // durumuna (isFlying) bağlanır, yoksa yürürken kafa döndürülüyordu.
        if (followTarget.getValue() && hasValidTarget && isFlying) {
            followAndOrbit(target);
        }

        // Kılıç seçimi: Aura hedef vuruyorsa en keskin kılıcı sessizce seç.
        // Kılıç paketi gönderilir ve hemen restore edilir; anticheat rubber-band
        // yaratmaması için sunucu asla kılıç slotuna kilitlenmez.
        if (autoSharpestSword.getValue() && hasValidTarget) {
            SearchInvResult sword = InventoryUtility.getHighestSharpnessSwordHotBar();
            if (sword.found() && slotToRestore != sword.slot()) {
                sendPacket(new UpdateSelectedSlotC2SPacket(sword.slot()));
                sendPacket(new UpdateSelectedSlotC2SPacket(slotToRestore)); // hemen geri dön
            }
        }

        // Kritik vuruş paketi (her saldırıdan önce).
        // DÜZELTME: eskiden "!isFallFlying()" şartı vardı, ama onlyWhenFlying=true
        // olduğunda bu satıra zaten sadece isFallFlying()=true iken geliniyordu,
        // yani autoCrit hiçbir zaman tetiklenmiyordu (ölü kod). Artık gerçek kritik
        // vuruş sadece "isFlying" false olduğunda (yerdeyken) gönderiliyor; elytra
        // ile uçarken vanilla zaten kritik vermediği ve fizik doğrulamasıyla
        // çelişip rubber-band yarattığı için o durumda hâlâ atlanıyor.
        if (autoCrit.getValue() && hasValidTarget && !isFlying && critTimer.passedMs(200)) {
            doCritPacket();
            critTimer.reset();
        }

        // Fişek boost
        if (!rocketBoost.getValue()) return;
        boolean shouldBoost = alwaysBoost.getValue() || hasValidTarget;
        if (!shouldBoost) return;

        if (instantFire.getValue()) {
            /*
             * InstantFire: velocity bazlı tetikleyici — normal oyuncu davranışı.
             * Vanilla'da oyuncu fişeği ancak hızı belirli bir eşiğin altına düşünce
             * tekrar kullanır. Sabit ms timer yerine bu eşik kullanılır.
             *
             * Minimum cooldown (600ms) ek güvenlik katmanı:
             * Önceki fişeğin sunucuda kayıtlı olması için gereken minimum süre.
             * Normal oyuncu refleks süresi ~500-800ms — bu aralıkta kalır.
             *
             * Eşik 0.7: vanilla elytra fişek boostunun ortalama sönüm noktası.
             * Yüksek tutulursa daha sık basar (daha hızlı ama daha riskli),
             * düşük tutulursa daha az basar (daha yavaş ama daha güvenli).
             */
            double speed = mc.player.getVelocity().length();
            boolean boostFaded  = speed < 0.7;
            boolean cooldownOk  = rocketTimer.passedMs(600);
            if (!boostFaded || !cooldownOk) return;
        } else {
            // InstantFire kapalı: kullanıcının ayarladığı ms gecikmesine göre
            if (!rocketTimer.passedMs(rocketDelay.getValue())) return;
        }

        // Burst: ayarlanan sayıda fişek at
        for (int i = 0; i < rocketBurst.getValue(); i++) {
            fireRocket();
        }
        rocketTimer.reset();
    }

    /**
     * Hedefin etrafında yörüngede döner ve kaçıyorsa önüne geçmeye çalışır.
     */
    private void followAndOrbit(Entity target) {
        Vec3d targetPos   = target.getPos();
        Vec3d targetMotion = target.getVelocity();
        float radius       = orbitRadius.getValue();
        float baseSpeed    = followSpeed.getValue();

        // Hedefe yaklaştıkça hızlan: uzaktayken baseSpeed, yakındayken daha hızlı dönüş.
        double distToTarget = Math.sqrt(PlayerUtility.squaredDistanceFromEyes(targetPos));
        float range         = targetRange.getValue();
        float proximity     = 1f - MathHelper.clamp((float) (distToTarget / range), 0f, 1f); // 0 uzak, 1 yakın
        float speed         = baseSpeed * (1f + proximity * 1.5f); // yakında en fazla 2.5x hız

        // Hedef elytra ile uçuyorsa önüne geçmeye çalış (intercept)
        Vec3d predictedPos = targetPos;
        if (interceptTarget.getValue() && target instanceof net.minecraft.entity.LivingEntity le && le.isFallFlying()) {
            double dist = Math.sqrt(PlayerUtility.squaredDistanceFromEyes(targetPos));
            double ticks = dist / Math.max(speed * 2.0, 0.1); // sıfır bölme önlemi
            predictedPos = targetPos.add(targetMotion.multiply(Math.min(ticks, 20.0))); // max 20 tick lookahead
        }

        // Yörünge açısını sürekli artır (etrafında dön)
        // % 2π → uzun uçuşlarda double hassasiyet kaybını önler
        orbitAngle = (orbitAngle + 0.04 * speed) % (Math.PI * 2.0);

        // Ham orbit noktası — hedef her tick hareket ettiğinde bu değer sıçrar.
        double rawOrbitX = predictedPos.x + Math.cos(orbitAngle) * radius;
        double rawOrbitZ = predictedPos.z + Math.sin(orbitAngle) * radius;
        double rawOrbitY = predictedPos.y + 2.0; // biraz üstten

        /*
         * EMA (Exponential Moving Average) orbit tamponu:
         * Ham nokta doğrudan kullanılmaz; smooth değere yavaşça kayar.
         * k ne kadar küçükse o kadar yavaş/soft geçiş (log düşmesi önlenir).
         * İlk tickte direkt yerleştir; sonrasında kademeli yumuşat.
         * DÜZELTME: smoothOrbit alanları artık double. Yüksek dünya
         * koordinatlarında (targetRange 128'e kadar çıkabiliyor) float
         * hassasiyeti kayboluyor ve gözle görülür titreme yaratıyordu.
         */
        if (!orbitSmoothed) {
            smoothOrbitX  = rawOrbitX;
            smoothOrbitY  = rawOrbitY;
            smoothOrbitZ  = rawOrbitZ;
            orbitSmoothed = true;
        } else {
            double k = MathHelper.clamp(speed * 0.12f, 0.04f, 0.28f);
            smoothOrbitX += (rawOrbitX - smoothOrbitX) * k;
            smoothOrbitY += (rawOrbitY - smoothOrbitY) * k;
            smoothOrbitZ += (rawOrbitZ - smoothOrbitZ) * k;
        }

        // Hedefe bakış açılarını hesapla (smooth orbit noktasına)
        double dx = smoothOrbitX - mc.player.getX();
        double dy = smoothOrbitY - (mc.player.getY() + mc.player.getEyeHeight(mc.player.getPose()));
        double dz = smoothOrbitZ - mc.player.getZ();
        double horizontalDist = Math.sqrt(dx * dx + dz * dz);

        // Guard: oyuncu tam üst/altındaysa yatay mesafe sıfırlanır → NaN/Inf olur, atla
        if (horizontalDist < 1e-4) return;

        float yaw   = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90f;
        float pitch = MathHelper.clamp(
                (float) -Math.toDegrees(Math.atan2(dy, horizontalDist)),
                -90f, 90f); // clamp: sunucu geçersiz açı görmez → rubber-band yok

        // Yavaş yavaş bak (smooth)
        float currentYaw   = mc.player.getYaw();
        float currentPitch = mc.player.getPitch();
        float newYaw   = currentYaw   + MathHelper.wrapDegrees(yaw   - currentYaw)   * speed * 0.3f;
        float newPitch = currentPitch + MathHelper.wrapDegrees(pitch - currentPitch) * speed * 0.3f;

        mc.player.setYaw(newYaw);
        mc.player.setPitch(newPitch);
    }

    /**
     * Kritik vuruş paketleri gönder (Criticals modülü gibi).
     * DÜZELTME: onGround bayrağı artık vanilla crit sekansını taklit ediyor
     * (yerden kalk → in). Önceki sabit false her adımda anticheat'lerin
     * kritik vuruşu tanımamasına yol açabiliyordu.
     */
    private void doCritPacket() {
        if (mc.player.isInLava() || mc.player.isSubmergedInWater()) return;
        switch (critMode.getValue()) {
            case Packet -> {
                sendPacket(new PlayerMoveC2SPacket.PositionAndOnGround(
                        mc.player.getX(), mc.player.getY() + 0.000000271875, mc.player.getZ(), false));
                sendPacket(new PlayerMoveC2SPacket.PositionAndOnGround(
                        mc.player.getX(), mc.player.getY(), mc.player.getZ(), true));
            }
            case Strict -> {
                sendPacket(new PlayerMoveC2SPacket.PositionAndOnGround(
                        mc.player.getX(), mc.player.getY() + 0.062600301692775, mc.player.getZ(), false));
                sendPacket(new PlayerMoveC2SPacket.PositionAndOnGround(
                        mc.player.getX(), mc.player.getY() + 0.07260029960661, mc.player.getZ(), false));
                sendPacket(new PlayerMoveC2SPacket.PositionAndOnGround(
                        mc.player.getX(), mc.player.getY(), mc.player.getZ(), true));
            }
        }
    }

    private void fireRocket() {
        SearchInvResult rocketHotbar = InventoryUtility.findItemInHotBar(Items.FIREWORK_ROCKET);
        int rocketSlot = rocketHotbar.slot();

        if (rocketSlot == -1) {
            if (!autoSwitchRocket.getValue()) return;
            SearchInvResult rocketAnywhere = InventoryUtility.findItemInInventory(Items.FIREWORK_ROCKET);
            if (!rocketAnywhere.found()) return;

            // DÜZELTME: eskiden burada hiçbir şey yapılmadan return ediliyordu,
            // yani AutoSwitchRocket ayarının pratikte hiçbir etkisi yoktu.
            // Artık envanterden bulunan roket, hotbar'daki mevcut slota (slotToRestore)
            // taşınıyor ki fireRocket() aynı tick içinde onu kullanabilsin.
            InventoryUtility.moveToHotbar(rocketAnywhere.slot(), slotToRestore);
            rocketSlot = slotToRestore;
        }

        int prevSlot    = slotToRestore; // tick başı snapshot — race condition yok
        boolean needsSwap = prevSlot != rocketSlot;

        if (silentRockets.getValue()) {
            if (needsSwap) sendPacket(new UpdateSelectedSlotC2SPacket(rocketSlot));
            sendSequencedPacket(id -> new PlayerInteractItemC2SPacket(
                    Hand.MAIN_HAND, id, mc.player.getYaw(), mc.player.getPitch()));
            if (needsSwap) sendPacket(new UpdateSelectedSlotC2SPacket(prevSlot));
        } else {
            if (needsSwap) InventoryUtility.switchTo(rocketSlot);
            sendSequencedPacket(id -> new PlayerInteractItemC2SPacket(
                    Hand.MAIN_HAND, id, mc.player.getYaw(), mc.player.getPitch()));
        }
    }

    public enum CritMode {
        Packet, Strict
    }
}
