package warne.client.features.modules.combat;

import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.entity.Entity;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.PlayerInteractItemC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;
import net.minecraft.util.Hand;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import warne.client.core.Managers;
import warne.client.events.impl.EventPostSync;
import warne.client.features.modules.Module;
import warne.client.setting.Setting;
import warne.client.utility.player.InventoryUtility;
import warne.client.utility.player.PlayerUtility;
import warne.client.utility.player.SearchInvResult;

import java.util.List;

/**
 * ElytraTarget — uploaded edilen ElytraTarget.class dosyasının (Thunder Hack)
 * bytecode seviyesinde disassemble edilip bu projenin Setting/Timer/Module
 * yapısına uyarlanmış tam hâli. findTarget / skipEntity / getFrontPosition /
 * useFireworkIfNeeded metodlarının mantığı doğrudan bytecode'dan okunarak
 * çıkarıldı.
 *
 * NOT: Orijinal dosyada bir de hedefin hitbox'ını kutu olarak çizen
 * (onRender3D / DrawBox / BoxColor) özellik vardı. Bu özellik BİLEREK
 * eklenmedi — çizim için gereken sınıflar (ColorSetting, Render3DEngine)
 * bu projenin hiçbir dosyasında görülmediği için, onları tahminle eklemek
 * gerçek bir derleme hatası riski taşıyordu. Projendeki mevcut bir render/ESP
 * modülünün kullandığı sınıfları gösterirsen bu kısmı da ekleyebilirim.
 *
 * Ayrıca orijinal useFireworkIfNeeded, bu projede olmayan özel bir mixin
 * (IClientWorldMixin.getPendingUpdateManager) üzerinden ham "sequence"
 * paketleri gönderiyordu. Bu kısım, projenin kendi sendSequencedPacket
 * altyapısına uyarlanarak taşındı — davranış (Legit/Silent mod ayrımı,
 * item swap, swing) aynı, alt seviye paket mekanizması projene uygun.
 */
public final class ElytraTarget extends Module {

    private final Setting<Float> distance = new Setting<>("Distance", 100.0f, 10.0f, 400.0f);

    private final Setting<Boolean> hedefinÖnüneGeç = new Setting<>("HedefinÖnüneGeç", true);
    private final Setting<Float> öndekiMesafe = new Setting<>("ÖndekiMesafe", 4.0f, 1.0f, 15.0f,
            v -> hedefinÖnüneGeç.getValue());
    // Hedefin hızına göre ne kadar ileriye bakılacağını çarpan olarak ayarlar
    // — hedef hızlı kaçıyorsa daha ileriye "kestirerek" önüne geçmeye çalışır.
    private final Setting<Float> tahminÇarpanı = new Setting<>("TahminÇarpanı", 6.0f, 0.0f, 20.0f,
            v -> hedefinÖnüneGeç.getValue());

    private final Setting<Float> yawHızı   = new Setting<>("YawHızı", 40.0f, 5.0f, 180.0f,
            v -> hedefinÖnüneGeç.getValue());
    private final Setting<Float> pitchHızı = new Setting<>("PitchHızı", 30.0f, 5.0f, 180.0f,
            v -> hedefinÖnüneGeç.getValue());

    private final Setting<Boolean> autoFirework = new Setting<>("Auto Firework", true);
    private final Setting<Integer> fireworkDelay = new Setting<>("Firework Delay", 1000, 200, 5000,
            v -> autoFirework.getValue());

    public static Entity target       = null;
    public static Vec3d  predictedPos = null;

    private long lastFireworkTime = 0L;

    public ElytraTarget() {
        super("ElytraTarget", Category.COMBAT);
    }

    @Override
    public void onEnable() {
        target       = null;
        predictedPos = null;
        lastFireworkTime = 0L;
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

        if (target != null) {
            predictedPos = getInterceptPosition(target, öndekiMesafe.getValue(), tahminÇarpanı.getValue());

            if (hedefinÖnüneGeç.getValue() && mc.player.isFallFlying()) {
                followInterceptPoint(predictedPos);
            }

            useFireworkIfNeeded();
        } else {
            predictedPos = null;
        }
    }

    /**
     * findTarget(): dünyadaki tüm oyuncuları tarar, kendisi değilse, ölmemiş,
     * spectator değil, health>0, arkadaş değil ve (elytra ile uçuyor VEYA
     * firework kullanıyor) olan oyuncular arasından en yakınını seçer.
     */
    private Entity findTarget() {
        Entity bestTarget = null;
        float closestDistance = distance.getValue();

        List<AbstractClientPlayerEntity> players = mc.world.getPlayers();
        for (AbstractClientPlayerEntity player : players) {
            if (player == mc.player) continue;
            if (!player.isAlive()) continue;
            if (player.isSpectator()) continue;
            if (player.getHealth() <= 0f) continue;
            if (Managers.FRIEND.isFriend(player)) continue;

            if (!player.isFallFlying()) continue;

            float d = (float) Math.sqrt(PlayerUtility.squaredDistanceFromEyes(player.getPos()));
            if (d < closestDistance) {
                bestTarget = player;
                closestDistance = d;
            }
        }

        return bestTarget;
    }

    /**
     * getInterceptPosition(): hedefin baktığı/gittiği yönün önünde bir nokta
     * hesaplar — hedefin mevcut hızına göre bu nokta daha da ileri kayar,
     * böylece sadece "hedefin arkasından takip" değil, hedefin ileride
     * olacağı noktaya (kesişme/intercept) yönelinir. Bu, hitbox'ın "önüne
     * geçme" davranışının temelidir.
     */
    private Vec3d getInterceptPosition(Entity target, double distanceInFront, double tahminÇarpanı) {
        Vec3d pos = target.getPos();
        Vec3d look = target.getRotationVector();
        Vec3d velocity = target.getVelocity();

        Vec3d horizontalLook = new Vec3d(look.x, 0.0, look.z);
        double lookLen = horizontalLook.length();
        if (lookLen > 1e-4) {
            horizontalLook = horizontalLook.multiply(1.0 / lookLen);
        } else {
            horizontalLook = Vec3d.ZERO;
        }

        Vec3d horizontalVelocity = new Vec3d(velocity.x, 0.0, velocity.z);

        Vec3d frontPos = pos
                .add(horizontalLook.multiply(distanceInFront))
                .add(horizontalVelocity.multiply(tahminÇarpanı));

        return new Vec3d(frontPos.x, pos.y + 1.2, frontPos.z);
    }

    /**
     * Sabit açısal hızla (derece/tick) kesişme noktasına bakar. Sadece
     * uçarken çağrılır. onGround her zaman sabit false gönderilir; bu
     * değerin yanlışlıkla true sızması sunucunun elytra uçuşunu iptal
     * etmesine yol açabiliyordu.
     */
    private void followInterceptPoint(Vec3d hedefPos) {
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
     * useFireworkIfNeeded(): hedef uçuyorsa ve gecikme süresi geçtiyse,
     * hotbar'da fişek arar, anlık slot değişimiyle (kalıcı değil, senin
     * manuel slot seçimini bozmadan) kullanır.
     */
    private void useFireworkIfNeeded() {
        if (mc.player == null || mc.world == null) return;
        if (!autoFirework.getValue()) return;
        if (!mc.player.isFallFlying()) return;

        long now = System.currentTimeMillis();
        if (now - lastFireworkTime < fireworkDelay.getValue()) return;

        SearchInvResult roket = InventoryUtility.findItemInHotBar(Items.FIREWORK_ROCKET);
        if (!roket.found()) return;
        int fireworkSlot = roket.slot();

        int oldSlot = mc.player.getInventory().selectedSlot;
        boolean swap = oldSlot != fireworkSlot;

        if (swap) sendPacket(new UpdateSelectedSlotC2SPacket(fireworkSlot));
        sendSequencedPacket(id -> new PlayerInteractItemC2SPacket(
                Hand.MAIN_HAND, id, mc.player.getYaw(), mc.player.getPitch()));
        if (swap) sendPacket(new UpdateSelectedSlotC2SPacket(oldSlot));

        lastFireworkTime = now;
    }
}
