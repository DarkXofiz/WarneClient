package warne.client.features.modules.combat;

import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.entity.Entity;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.PlayerInteractItemC2SPacket;
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;
import net.minecraft.util.Hand;
import net.minecraft.util.math.Vec3d;
import warne.client.core.Managers;
import warne.client.events.impl.EventPostSync;
import warne.client.features.modules.Module;
import warne.client.setting.Setting;
import warne.client.utility.player.PlayerUtility;

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

    private final Setting<Boolean> autoFirework = new Setting<>("Auto Firework", true);
    private final Setting<Integer> fireworkDelay = new Setting<>("Firework Delay", 1000, 200, 5000,
            v -> autoFirework.getValue());

    // Orijinaldeki "FireworkMode" (Legit/Silent) — ElytraHelper.getFireWorkMode()
    // bu projede yok, bu yüzden ayrı bir ayar olarak burada tutuluyor.
    public enum FireworkMode { Legit, Silent }
    private final Setting<FireworkMode> fireworkMode = new Setting<>("FireworkMode", FireworkMode.Silent,
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
            predictedPos = getFrontPosition(target, 2.5);
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

            boolean isFlying = player.isFallFlying()
                    || (player.getInventory().getStack(2).isOf(Items.FIREWORK_ROCKET) && !player.isOnGround());
            if (!isFlying) continue;

            float d = (float) Math.sqrt(PlayerUtility.squaredDistanceFromEyes(player.getPos()));
            if (d < closestDistance) {
                bestTarget = player;
                closestDistance = d;
            }
        }

        return bestTarget;
    }

    /**
     * getFrontPosition(): hedefin pozisyonunu, bakış vektörünün yatay
     * (Y hariç) bileşenini normalize edip distanceInFront kadar öne
     * taşıyarak hesaplar; sonuca +1.2 dikey ofset eklenir.
     */
    private Vec3d getFrontPosition(Entity target, double distanceInFront) {
        Vec3d pos = target.getPos();
        Vec3d look = target.getRotationVector();

        Vec3d horizontalLook = new Vec3d(look.x, 0.0, look.z).normalize();
        Vec3d frontPos = pos.add(horizontalLook.multiply(distanceInFront));

        return new Vec3d(frontPos.x, pos.y + 1.2, frontPos.z);
    }

    /**
     * useFireworkIfNeeded(): hedef uçuyorsa ve gecikme süresi geçtiyse,
     * hotbar'da fişek arar (hiçbiri yoksa envanterden ilk boş hotbar
     * slotuna geçici olarak taşır — orijinal davranış), FireworkMode'a göre
     * Legit (gerçek slot değişimi + kullanım + isteğe bağlı swing) veya
     * Silent (anlık slot değişimi + kullanım + eski slota dönüş) olarak
     * kullanır.
     */
    private void useFireworkIfNeeded() {
        if (mc.player == null || mc.world == null) return;
        if (!autoFirework.getValue()) return;
        if (!mc.player.isFallFlying()) return;

        long now = System.currentTimeMillis();
        if (now - lastFireworkTime < fireworkDelay.getValue()) return;

        int fireworkSlot = -1;
        for (int i = 0; i < 9; i++) {
            if (mc.player.getInventory().getStack(i).isOf(Items.FIREWORK_ROCKET)) {
                fireworkSlot = i;
                break;
            }
        }
        if (fireworkSlot == -1) return;

        int oldSlot = mc.player.getInventory().selectedSlot;

        if (fireworkMode.getValue() == FireworkMode.Legit) {
            // Legit: slotu kalıcı olarak fişeğe çevir (geri dönmez), gerçek
            // bir oyuncunun elle slot değiştirmesi gibi görünür.
            if (oldSlot != fireworkSlot) {
                sendPacket(new UpdateSelectedSlotC2SPacket(fireworkSlot));
            }
            sendSequencedPacket(id -> new PlayerInteractItemC2SPacket(
                    Hand.MAIN_HAND, id, mc.player.getYaw(), mc.player.getPitch()));
        } else {
            // Silent: anlık slot değişimi, kullanım, hemen eski slota dönüş.
            if (oldSlot != fireworkSlot) {
                sendPacket(new UpdateSelectedSlotC2SPacket(fireworkSlot));
            }
            sendSequencedPacket(id -> new PlayerInteractItemC2SPacket(
                    Hand.MAIN_HAND, id, mc.player.getYaw(), mc.player.getPitch()));
            if (oldSlot != fireworkSlot) {
                sendPacket(new UpdateSelectedSlotC2SPacket(oldSlot));
            }
        }

        lastFireworkTime = now;
    }
}
