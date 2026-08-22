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

public final class ElytraAura extends Module {

    /* ── ENUM'LAR ───────────────────────────────────────────────────────── */

    public enum BypassModu { Kapalı, GrimAC, Güçlü }
    public enum HızModu    { Yumuşak, Sert }
    public enum CritMode   { Paket, Katı }

    /* ── BYPASS ─────────────────────────────────────────────────────────── */
    private final Setting<BypassModu> bypassModu = new Setting<>("BypassModu", BypassModu.GrimAC);

    /* ── ROCKET ─────────────────────────────────────────────────────────── */

    private final Setting<Boolean> fişekBoost = new Setting<>("FişekBoost", true);

    private final Setting<Boolean> anındaAteş = new Setting<>("AnındaAteş", false,
            v -> fişekBoost.getValue());

    private final Setting<Integer> fişekGecikmesi = new Setting<>("FişekGecikmesi", 300, 50, 3000,
            v -> fişekBoost.getValue() && !anındaAteş.getValue());

    private final Setting<Integer> fişekSalvası = new Setting<>("FişekSalvası", 2, 1, 10,
            v -> fişekBoost.getValue());

    private final Setting<Boolean> sessizFişek = new Setting<>("SessizFişek", true,
            v -> fişekBoost.getValue());

    private final Setting<Boolean> otomatikFişekGeç = new Setting<>("OtomatikFişekGeç", true,
            v -> fişekBoost.getValue());

    private final Setting<Boolean> herzamanBoost = new Setting<>("HerzamanBoost", true,
            v -> fişekBoost.getValue());

    /*
     * OtoElytraAç: elytra takılı ama şu an açık değilse (isFallFlying false),
     * oyuncu havadaysa ve yeterince düşüyorsa otomatik zıplatıp elytrayı açar.
     * Bu, "SadeceUçarken açık ama elytra hiç açılmadığı için modül hiç
     * çalışmıyor" sorununu çözer.
     */
    private final Setting<Boolean> otoElytraAç = new Setting<>("OtoElytraAç", true);

    /*
     * MaksimumHız: bu hızı (blok/saniye) geçince fişek basmayı kes → hız sabitlenir.
     * HızModu:
     *   Yumuşak → hız aşımında timer koşmaya devam eder; hız düşer düşmez ateşler.
     *   Sert    → hız aşımında timer sıfırlanır; hız düşünce tam FişekGecikmesi bekler.
     * AktifDüşüşteHızSınırıUygula: kapalıyken serbest düşüşte MaksimumHız yok sayılır
     * (eski davranış). Açıkken düşüşte de hız sınırı uygulanır — sınırsız fişek riskini kapatır.
     */
    private final Setting<Float>   maksimumHız = new Setting<>("MaksimumHız", 3.5f, 0.5f, 20.0f,
            v -> fişekBoost.getValue());
    private final Setting<HızModu> hızModu     = new Setting<>("HızModu", HızModu.Sert,
            v -> fişekBoost.getValue());
    private final Setting<Boolean> aktifDüşüşteHızSınırıUygula = new Setting<>(
            "AktifDüşüşteHızSınırıUygula", false, v -> fişekBoost.getValue());

    /* ── HEDEF ──────────────────────────────────────────────────────────── */

    private final Setting<Float>   hedefMenzili    = new Setting<>("HedefMenzili", 64f, 5f, 128f);
    private final Setting<Boolean> sadeceUçarken   = new Setting<>("SadeceUçarken", true);
    private final Setting<Boolean> hedefTakip      = new Setting<>("HedefTakip", true);
    private final Setting<Float>   takipHızı       = new Setting<>("TakipHızı", 0.8f, 0.1f, 3.0f,
            v -> hedefTakip.getValue());
    private final Setting<Float>   yörüngeYarıçapı = new Setting<>("YörüngeYarıçapı", 4.0f, 1.0f, 15.0f,
            v -> hedefTakip.getValue());
    private final Setting<Boolean> önünGeç         = new Setting<>("ÖnünGeç", true,
            v -> hedefTakip.getValue());

    /* ── KRİTİK VURUŞ ───────────────────────────────────────────────────── */

    private final Setting<Boolean>  otoCrit  = new Setting<>("OtoCrit", true);
    private final Setting<CritMode> critModu = new Setting<>("CritModu", CritMode.Paket,
            v -> otoCrit.getValue());

    /* ── KILIC ──────────────────────────────────────────────────────────── */

    private final Setting<Boolean> otoKılıç = new Setting<>("OtoKılıç", true);

    /* ── STATE ──────────────────────────────────────────────────────────── */

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

    public ElytraAura() {
        super("ElytraAura", Category.COMBAT);
    }

    /* ═══════════════════════════════════════════════════════════════════════
       LIFECYCLE
    ═══════════════════════════════════════════════════════════════════════ */

    @Override
    public void onEnable() {
        orbitAngle    = 0;
        orbitSmoothed = false;
        lastSentYaw   = Float.NaN;
        lastSentPitch = Float.NaN;
        rocketTimer.reset();
        critTimer.reset();
        if (mc.player != null) slotToRestore = mc.player.getInventory().selectedSlot;
    }

    @Override
    public void onDisable() {
        orbitSmoothed = false;
        lastSentYaw   = Float.NaN;
        lastSentPitch = Float.NaN;
    }

    /* ═══════════════════════════════════════════════════════════════════════
       ANA LOOP
    ═══════════════════════════════════════════════════════════════════════ */

    @EventHandler
    public void onPostSync(EventPostSync e) {
        if (mc.player == null || mc.world == null) return;

        slotToRestore = mc.player.getInventory().selectedSlot;

        Entity  target   = Aura.target;
        boolean hedefVar = target != null
                && PlayerUtility.squaredDistanceFromEyes(target.getPos())
                   < (hedefMenzili.getValue() * hedefMenzili.getValue());

        boolean uçuyor = mc.player.isFallFlying();

        if (uçuyor) {
            if (hedefTakip.getValue() && hedefVar) {
                followAndOrbit(target);
            } else {
                lastSentYaw   = Float.NaN;
                lastSentPitch = Float.NaN;
            }

            /*
             * FIX: Kritik paketi artık sadece hedef gerçekten menzildeyken VE
             * yerde/suda olmadığımızda gönderiliyor (doCritPacket zaten bunu
             * kontrol ediyordu, ama artık lava/su kontrolüyle tutarlı hale getirildi).
             */
            if (otoCrit.getValue() && hedefVar && critTimer.passedMs(200)) {
                doCritPacket();
                critTimer.reset();
            }
        } else {
            // Uçmuyorken orbit state'i temizle, bir sonraki uçuşta sıfırdan başlasın.
            orbitSmoothed = false;
        }

        // Kılıç seçimi
        if (otoKılıç.getValue() && hedefVar) {
            SearchInvResult kılıç = InventoryUtility.getHighestSharpnessSwordHotBar();
            if (kılıç.found() && slotToRestore != kılıç.slot()) {
                sendPacket(new UpdateSelectedSlotC2SPacket(kılıç.slot()));
                sendPacket(new UpdateSelectedSlotC2SPacket(slotToRestore));
            }
        }

        /* ── ROCKET ──────────────────────────────────────────────────────── */
        /*
         * FIX 1: elytra takılıysa ama henüz "isFallFlying" olmayan an (yere
         * yakınken zıplayıp elytra açmanın ilk tick'i gibi) boost başlamıyordu.
         * Artık elytraTakılı kontrolü de ekleniyor: elytra giyiliyse ve
         * SadeceUçarken kapalıysa yerdeyken bile boost çalışabilir.
         */
        boolean elytraTakılı = mc.player.getEquippedChestStack().isOf(net.minecraft.item.Items.ELYTRA);

        /*
         * Elytra takılı ama açık değilse ve oyuncu yerde değilse, otomatik
         * aç. Bu sayede modül sadece "zaten süzülüyorsam" değil, "elytra
         * takılıysa ve havadaysam" durumunda da devreye girebiliyor.
         */
        if (otoElytraAç.getValue() && elytraTakılı && !uçuyor && !mc.player.isOnGround()
                && !mc.player.hasVehicle()) {
            mc.player.startFallFlying();
        }

        if (!fişekBoost.getValue()) return;
        if (sadeceUçarken.getValue() && !uçuyor) return;
        /*
         * FIX 2: HerzamanBoost artık gerçek anlamıyla çalışıyor.
         *   Açık  → hedef olsun olmasın (uçuş şartı sağlandıysa) boost devam eder.
         *   Kapalı → sadece geçerli bir hedef varken boost yapılır.
         */
        if (!herzamanBoost.getValue() && !hedefVar) return;

        double  anHız      = mc.player.getVelocity().length();
        boolean aktifDüşüş = uçuyor && mc.player.getVelocity().y < -0.3;

        /*
         * FIX: aktifDüşüşteHızSınırıUygula kapalıyken (varsayılan, eski davranış)
         * dalışta hız sınırı uygulanmaz — dalışta hızlanmak istenen bir şey.
         * Açıldığında dalışta da MaksimumHız uygulanır, sınırsız ivmelenmeyi engeller.
         */
        boolean hızSınırıAtla = aktifDüşüş && !aktifDüşüşteHızSınırıUygula.getValue();

        if (!hızSınırıAtla && anHız > maksimumHız.getValue()) {
            if (hızModu.getValue() == HızModu.Sert) rocketTimer.reset();
            return;
        }

        if (anındaAteş.getValue()) {
            if (anHız >= 0.7 || !rocketTimer.passedMs(600)) return;
        } else {
            if (!rocketTimer.passedMs(fişekGecikmesi.getValue())) return;
        }

        int atıldı = 0;
        for (int i = 0; i < fişekSalvası.getValue(); i++) {
            if (fireRocket()) atıldı++;
        }
        // FIX: hiç fişek atılamadıysa (envanterde yok) timer'ı sıfırlama —
        // aksi halde fişek bitince modül sessizce "bekliyormuş" gibi görünür
        // ama aslında hiçbir şey denemiyordur ta ki fişek gelene kadar.
        if (atıldı > 0) rocketTimer.reset();
    }

    /* ═══════════════════════════════════════════════════════════════════════
       ORBIT + FOLLOW
    ═══════════════════════════════════════════════════════════════════════ */

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
            rawY = eyeY + 5.0;
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
            /*
             * FIX: kaçışModu'na girişte k=0.6 sabiti orbit hedefini tek
             * tick'te neredeyse yeni pozisyona sıçratıyordu. Bu da rawYaw/
             * rawPitch'te ani büyük delta üretip lerpT ile birleşince
             * "hedefe yaklaşınca aniden hızlanma" hissi yaratıyordu.
             * Artık kaçışModu'nda da normal aralıkta (biraz daha hızlı ama
             * sınırlı) bir k kullanılıyor — geçiş yumuşak kalıyor.
             */
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
        if (hDist < 1e-4) return;

        float hedefYaw = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90f;

        float pitchMaxAşağı;
        if (kaçışModu) {
            pitchMaxAşağı = -15f;
        } else if (distSq < safeThresholdSq) {
            double yakınlık = MathHelper.clamp(
                    (float)(1.0 - dist / (radius * 3.0)), 0f, 1f);
            pitchMaxAşağı = (float)(20.0 - yakınlık * 30.0);
        } else {
            pitchMaxAşağı = 90f;
        }

        float hedefPitch = MathHelper.clamp(
                (float) -Math.toDegrees(Math.atan2(dy, hDist)),
                -90f, pitchMaxAşağı);

        float mevcutYaw   = mc.player.getYaw();
        float mevcutPitch = mc.player.getPitch();
        /*
         * FIX: kaçışModu'nda lerpT sabit 0.5f'ye sıçrıyordu — hedefe yaklaşınca
         * rotasyon aniden çok agresif dönüyordu, bu da hızlanma hissinin asıl
         * kaynağıydı. Artık FollowSpeed ile orantılı, sınırlı bir değer.
         */
        float lerpT = kaçışModu
                ? MathHelper.clamp(speed * 0.45f, 0.15f, 0.55f)
                : speed * 0.3f;

        float rawYaw   = mevcutYaw   + MathHelper.wrapDegrees(hedefYaw   - mevcutYaw)   * lerpT;
        float rawPitch = MathHelper.clamp(
                mevcutPitch + MathHelper.wrapDegrees(hedefPitch - mevcutPitch) * lerpT,
                -90f, 90f);

        // ── BYPASS ── (dokunulmadı)
        BypassModu bm = bypassModu.getValue();
        float finalYaw, finalPitch;

        if (bm == BypassModu.Kapalı) {
            finalYaw   = rawYaw;
            finalPitch = rawPitch;
        } else {
            double sens = mc.options.getMouseSensitivity().getValue() * 0.6 + 0.2;
            double gcd  = Math.pow(sens, 3.0) * 1.2;

            if (bm == BypassModu.Güçlü) {
                gcd += (Math.random() - 0.5) * gcd * 0.08;
            }

            finalYaw   = (float)(rawYaw   - (rawYaw   - mevcutYaw)   % gcd);
            finalPitch = (float)(rawPitch - (rawPitch - mevcutPitch) % gcd);
        }

        mc.player.setYaw(finalYaw);
        mc.player.setPitch(finalPitch);

        if (bm != BypassModu.Kapalı) {
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
    }

    /* ═══════════════════════════════════════════════════════════════════════
       KRİTİK VURUŞ
    ═══════════════════════════════════════════════════════════════════════ */

    private void doCritPacket() {
        if (mc.player.isInLava() || mc.player.isSubmergedInWater()) return;
        /*
         * FIX: onGround parametresi artık gerçek oyuncu durumuna göre gönderiliyor.
         * Eskiden ikinci pakette sabit `true` gönderiliyordu — bu, elytra uçuşu
         * gibi havadayken sunucuya yanlış "yerdeyim" sinyali veriyordu ve bazı
         * sunucularda pozisyon doğrulamasını bozup rubber-band'e yol açabiliyordu.
         */
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

    /* ═══════════════════════════════════════════════════════════════════════
       ROCKET
    ═══════════════════════════════════════════════════════════════════════ */

    /*
     * FIX: eskiden rocket hotbar'da yoksa envanterde arayıp bulsa bile
     * hiçbir swap yapmadan boş return ediyordu — otomatikFişekGeç ayarı
     * fiilen hiçbir işe yaramıyordu. Artık gerçekten hotbar'a taşıyor.
     * Metod artık bir fişeğin gerçekten atılıp atılmadığını bildirmek için
     * boolean dönüyor (çağıran taraf artık bunu rocketTimer kararı için kullanıyor).
     */
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

            InventoryUtility.swap(rocketAnywhere.slot(), emptySlot);
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
