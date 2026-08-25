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

import java.util.List;

/**
 * ElytraTarget — "Thunder Hack"in decompile edilmiş ElytraTarget modülünün
 * (findTarget/skipEntity/getFrontPosition/useFireworkIfNeeded) mantığı
 * çıkarılıp bu projenin Setting/Timer/Module yapısına uyarlanmış hâli.
 *
 * NOT: Orijinal bytecode'da bir de hitbox kutusu çizen (onRender3D/DrawBox)
 * özellik vardı. Bu özellik burada YOK — çizim için gereken sınıflar
 * (ColorSetting, EventRender3D, RenderUtility.drawBoxESP) bu projenin hiçbir
 * dosyasında görülmediği için, onları tahminle eklemek gerçek bir derleme
 * hatası riski taşıyordu. Görsel kutu çizimi istersen, projedeki mevcut bir
 * render/ESP modülünün (varsa) hangi sınıfları kullandığını gösterirsen
 * onu da ekleyebilirim.
 *
 * Ayrıca daha önce bu dosyada kanıtlanmış olan üç düzeltme korunuyor:
 * (1) rotasyon sadece uçarken çalışır, yerde hareketi etkilemez,
 * (2) kılıç/fişek gerçek mesafe kontrolüyle tetiklenir (reach görünümünü
 * önler), (3) rotasyon ile slot değişimi aynı tick'te çakışmaz.
 */
public final class ElytraTarget extends Module {

    // ============================================================
    // ---- Hedef bulma (findTarget / skipEntity) ----
    // ============================================================
    private final Setting<Float> menzil = new Setting<>("Menzil", 100f, 10f, 400f);

    // ============================================================
    // ---- Rotasyon ----
    // ============================================================
    private final Setting<Boolean> hedefTakip = new Setting<>("HedefTakip", true);

    private final Setting<Float> yawHızı   = new Setting<>("YawHızı", 45.0f, 5.0f, 180.0f,
            v -> hedefTakip.getValue());
    private final Setting<Float> pitchHızı = new Setting<>("PitchHızı", 35.0f, 5.0f, 180.0f,
            v -> hedefTakip.getValue());

    // "Hedefin önü": bytecode'daki getFrontPosition(target, distanceInFront)
    // ile aynı — hedefin baktığı yönün distanceInFront kadar önündeki nokta.
    private final Setting<Float> önündekiMesafe = new Setting<>("ÖnündekiMesafe", 2.5f, 0f, 20f,
            v -> hedefTakip.getValue());

    // ============================================================
    // ---- Kılıç / Fişek ----
    // ============================================================
    private final Setting<Boolean> otoFişek = new Setting<>("OtoFişek", true);
    private final Setting<Integer> fişekGecikmesi = new Setting<>("FişekGecikmesi", 1000, 200, 5000,
            v -> otoFişek.getValue());

    private final Setting<Boolean> otoKılıç = new Setting<>("OtoKılıç", true);

    // Kılıç/fişek sadece hedef gerçekten bu mesafenin içindeyken tetiklenir.
    private final Setting<Float> saldırıMenzili = new Setting<>("SaldırıMenzili", 3.0f, 1.5f, 4.0f);

    private final Setting<Integer> geçişSüresi = new Setting<>("GeçişSüresi", 250, 100, 2000);

    // ============================================================
    // ---- Durum ----
    // ============================================================
    public static Entity target       = null;
    public static Vec3d  predictedPos = null;

    private final Timer fişekTimer = new Timer();
    private final Timer döngüTimer = new Timer();
    private boolean sıradaFişek = false;

    public ElytraTarget() {
        super("ElytraTarget", Category.COMBAT);
    }

    @Override
    public void onEnable() {
        target        = null;
        predictedPos  = null;
        sıradaFişek   = false;
        döngüTimer.reset();
        fişekTimer.reset();
    }

    @Override
    public void onDisable() {
        target       = null;
        predictedPos = null;
    }

    @EventHandler
    public void onPostSync(EventPostSync e) {
        if (mc.player == null || mc.world == null) {
            target       = null;
            predictedPos = null;
            return;
        }

        target = findTarget();

        if (target == null) {
            predictedPos = null;
            sıradaFişek  = false;
            return;
        }

        predictedPos = getFrontPosition(target, önündekiMesafe.getValue());

        if (!mc.player.isFallFlying()) return;

        boolean hedefMenzilde = PlayerUtility.squaredDistanceFromEyes(target.getPos())
                <= (double) saldırıMenzili.getValue() * saldırıMenzili.getValue();

        boolean geçişZamanıGeldi = döngüTimer.passedMs(geçişSüresi.getValue());
        boolean buTickSlotİşlemiVar = geçişZamanıGeldi && (sıradaFişek || hedefMenzilde);

        // Rotasyon ile slot değişimi aynı tick'te çakışmasın diye, slot
        // işlemi olacak tick'te rotasyonu bu sefer atlıyoruz.
        if (hedefTakip.getValue() && !buTickSlotİşlemiVar && target instanceof LivingEntity) {
            followTarget((LivingEntity) target);
        }

        useFireworkIfNeeded();

        if (!geçişZamanıGeldi) return;

        if (sıradaFişek) {
            if (otoFişek.getValue() && kullanFişek()) {
                sıradaFişek = false;
                döngüTimer.reset();
            }
        } else if (hedefMenzilde && otoKılıç.getValue()) {
            if (kullanKılıç()) {
                sıradaFişek = true;
                döngüTimer.reset();
            }
        }
    }

    /**
     * findTarget(): dünyadaki tüm oyuncuları tarar, skipEntity ile elenenleri
     * atlar, aralarından en yakınını seçer.
     */
    private Entity findTarget() {
        Entity bestTarget = null;
        float closestDistance = menzil.getValue();

        List<PlayerEntity> players = mc.world.getPlayers();
        for (PlayerEntity player : players) {
            if (player == mc.player) continue;
            if (skipEntity(player)) continue;

            double dSq = PlayerUtility.squaredDistanceFromEyes(player.getPos());
            if (dSq < (double) closestDistance * closestDistance) {
                bestTarget = player;
                closestDistance = (float) Math.sqrt(dSq);
            }
        }

        return bestTarget;
    }

    /**
     * skipEntity(): oyuncu değilse, ölmüşse, spectator ise, health<=0 ise,
     * uçmuyorsa veya menzil dışındaysa hedefi atla.
     */
    private boolean skipEntity(Entity entity) {
        if (!(entity instanceof PlayerEntity)) return true;
        PlayerEntity player = (PlayerEntity) entity;

        boolean geçerli = !player.isSpectator()
                && player.isAlive()
                && player.getHealth() > 0f
                && player.isFallFlying()
                && PlayerUtility.squaredDistanceFromEyes(player.getPos())
                   <= (double) menzil.getValue() * menzil.getValue();

        return !geçerli;
    }

    /**
     * getFrontPosition(): hedefin baktığı yönün distanceInFront kadar
     * önündeki noktayı hesaplar.
     */
    private Vec3d getFrontPosition(Entity target, double distanceInFront) {
        float yaw = target.getYaw();
        double rad = Math.toRadians(yaw);
        double dx = -Math.sin(rad) * distanceInFront;
        double dz = Math.cos(rad) * distanceInFront;
        return target.getPos().add(dx, 0, dz);
    }

    /**
     * Sabit açısal hızla (derece/tick) hedefin önündeki tahmini noktaya
     * bakar. Sadece uçarken çağrılır, yerdeki hareketi hiç etkilemez.
     * onGround her zaman sabit false gönderilir; bu değerin yanlışlıkla
     * true sızması sunucunun elytra uçuşunu iptal etmesine yol açabiliyordu.
     */
    private void followTarget(LivingEntity target) {
        Vec3d hedefPos = predictedPos != null ? predictedPos : target.getEyePos();

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

    /** useFireworkIfNeeded(): dikey hız düşükse (düşüyorsa) fişek kullan. */
    private void useFireworkIfNeeded() {
        if (!otoFişek.getValue()) return;
        if (mc.player.getVelocity().y >= -0.1) return;
        if (!fişekTimer.passedMs(fişekGecikmesi.getValue())) return;

        if (kullanFişek()) fişekTimer.reset();
    }

    private boolean kullanKılıç() {
        SearchInvResult kılıç = InventoryUtility.getHighestSharpnessSwordHotBar();
        if (!kılıç.found()) return false;

        if (mc.player.getInventory().selectedSlot != kılıç.slot()) {
            sendPacket(new UpdateSelectedSlotC2SPacket(kılıç.slot()));
        }

        return true;
    }

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

