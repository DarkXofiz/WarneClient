package warne.client.features.modules.combat;

import meteordevelopment.orbit.EventHandler;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.PotionContentsComponent;
import net.minecraft.entity.effect.StatusEffect;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.item.ItemStack;
import net.minecraft.item.SplashPotionItem;
import net.minecraft.network.packet.c2s.play.PlayerInteractItemC2SPacket;
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.util.Hand;
import warne.client.core.manager.client.ModuleManager;
import warne.client.events.impl.EventAfterRotate;
import warne.client.events.impl.EventPostSync;
import warne.client.features.modules.Module;
import warne.client.setting.Setting;
import warne.client.setting.impl.BooleanSettingGroup;
import warne.client.utility.Timer;

public final class AutoBuff extends Module {
    private final Setting<Boolean> strength = new Setting<>("Strength", true);
    private final Setting<Boolean> speed = new Setting<>("Speed", true);
    private final Setting<Boolean> fire = new Setting<>("FireResistance", true);
    private final Setting<BooleanSettingGroup> heal = new Setting<>("InstantHealing", new BooleanSettingGroup(true));
    private final Setting<Integer> healthH = new Setting<>("Health", 8, 0, 20).addToGroup(heal);
    private final Setting<Boolean> healPriority = new Setting<>("HealPriority", true).addToGroup(heal);
    private final Setting<Integer> healDelay = new Setting<>("HealDelay", 150, 0, 1000).addToGroup(heal);
    private final Setting<BooleanSettingGroup> regen = new Setting<>("Regeneration", new BooleanSettingGroup(true));
    private final Setting<TriggerOn> triggerOn = new Setting<>("Trigger", TriggerOn.LackOfRegen).addToGroup(regen);
    private final Setting<Integer> healthR = new Setting<>("HP", 8, 0, 20, v -> triggerOn.is(TriggerOn.Health)).addToGroup(regen);
    private final Setting<Boolean> onDaGround = new Setting<>("OnlyOnGround", true);
    private final Setting<Boolean> pauseAura = new Setting<>("PauseAura", false);

    public Timer timer = new Timer();
    public Timer healTimer = new Timer();
    private boolean spoofed = false;
    private boolean healSpoofed = false;

    public AutoBuff() {
        super("AutoBuff", Category.COMBAT);
    }

    public static int getPotionSlot(Potions potion) {
        for (int i = 0; i < 9; ++i)
            if (isStackPotion(mc.player.getInventory().getStack(i), potion)) return i;
        return -1;
    }

    public static boolean isPotionOnHotBar(Potions potions) {
        return getPotionSlot(potions) != -1;
    }

    public static boolean isStackPotion(ItemStack stack, Potions potion) {
        if (stack == null) return false;

        if (stack.getItem() instanceof SplashPotionItem) {
            PotionContentsComponent potionContentsComponent = stack.getOrDefault(DataComponentTypes.POTION_CONTENTS, PotionContentsComponent.DEFAULT);

            RegistryEntry<StatusEffect> id = null;

            switch (potion) {
                case STRENGTH -> id = StatusEffects.STRENGTH;
                case SPEED -> id = StatusEffects.SPEED;
                case FIRERES -> id = StatusEffects.FIRE_RESISTANCE;
                case HEAL -> id = StatusEffects.INSTANT_HEALTH;
                case REGEN -> id = StatusEffects.REGENERATION;
            }

            for (StatusEffectInstance effect : potionContentsComponent.getEffects()) {
                if (effect.getEffectType() == id) return true;
            }
        }
        return false;
    }

    @EventHandler
    public void onPostRotationSet(EventAfterRotate event) {
        boolean emergencyHeal = shouldHeal() && !healGateBlocked();

        if (mc.player.age > 80 && emergencyHeal) {
            mc.player.setPitch(90);
            healSpoofed = true;
            return;
        }

        if (Aura.target != null && mc.player.getAttackCooldownProgress(1) > 0.5f) return;
        if (mc.player.age > 80 && shouldThrow()) {
            mc.player.setPitch(90);
            spoofed = true;
        }
    }

    private boolean shouldThrow() {
        return (!mc.player.hasStatusEffect(StatusEffects.SPEED) && isPotionOnHotBar(Potions.SPEED) && speed.getValue()) || (!mc.player.hasStatusEffect(StatusEffects.STRENGTH) && isPotionOnHotBar(Potions.STRENGTH) && strength.getValue()) || (!mc.player.hasStatusEffect(StatusEffects.FIRE_RESISTANCE) && isPotionOnHotBar(Potions.FIRERES) && fire.getValue()) || (!mc.player.hasStatusEffect(StatusEffects.REGENERATION) && triggerOn.is(TriggerOn.LackOfRegen) && isPotionOnHotBar(Potions.REGEN) && regen.getValue().isEnabled()) || (mc.player.getHealth() + mc.player.getAbsorptionAmount() < healthR.getValue() && triggerOn.is(TriggerOn.Health) && isPotionOnHotBar(Potions.REGEN) && regen.getValue().isEnabled());
    }

    /**
     * Instant Health check, kept separate from shouldThrow() so it can fire immediately
     * (bypassing the combat-lock / ground-check / shared 1s cooldown) instead of waiting
     * in line behind the other buffs. Works with both Instant Health I and II splash potions,
     * since only the effect type is checked, not the amplifier/tier.
     */
    private boolean shouldHeal() {
        return heal.getValue().isEnabled() && isPotionOnHotBar(Potions.HEAL) && (mc.player.getHealth() + mc.player.getAbsorptionAmount()) < healthH.getValue();
    }

    /**
     * When HealPriority is on (default), the emergency heal ignores the aura combat-lock
     * and the OnlyOnGround restriction so it can react the instant health drops.
     * When off, heal behaves like the other buffs and respects those gates.
     */
    private boolean healGateBlocked() {
        if (healPriority.getValue()) return false;
        if (Aura.target != null && mc.player.getAttackCooldownProgress(1) > 0.5f) return true;
        return onDaGround.getValue() && !mc.player.isOnGround();
    }

    @EventHandler
    public void onPostSync(EventPostSync e) {
        // Emergency instant-health: handled first and independently so it can react
        // right away instead of waiting behind the combat-lock/ground-check/1s cooldown
        // that gate the other buffs.
        if (shouldHeal() && !healGateBlocked() && mc.player.age > 80 && healSpoofed && healTimer.passedMs(healDelay.getValue())) {
            throwPotion(Potions.HEAL);
            sendPacket(new UpdateSelectedSlotC2SPacket(mc.player.getInventory().selectedSlot));
            healTimer.reset();
            healSpoofed = false;
        }

        if (Aura.target != null && mc.player.getAttackCooldownProgress(1) > 0.5f) return;

        if (onDaGround.getValue() && !mc.player.isOnGround()) return;

        if (mc.player.age > 80 && shouldThrow() && timer.passedMs(1000) && spoofed) {
            if (!mc.player.hasStatusEffect(StatusEffects.SPEED) && isPotionOnHotBar(Potions.SPEED) && speed.getValue())
                throwPotion(Potions.SPEED);

            if (!mc.player.hasStatusEffect(StatusEffects.STRENGTH) && isPotionOnHotBar(Potions.STRENGTH) && strength.getValue())
                throwPotion(Potions.STRENGTH);

            if (!mc.player.hasStatusEffect(StatusEffects.FIRE_RESISTANCE) && isPotionOnHotBar(Potions.FIRERES) && fire.getValue())
                throwPotion(Potions.FIRERES);

            if (((!mc.player.hasStatusEffect(StatusEffects.REGENERATION) && triggerOn.is(TriggerOn.LackOfRegen)) || (mc.player.getHealth() + mc.player.getAbsorptionAmount() < healthR.getValue() && triggerOn.is(TriggerOn.Health))) && isPotionOnHotBar(Potions.REGEN) && regen.getValue().isEnabled())
                throwPotion(Potions.REGEN);

            sendPacket(new UpdateSelectedSlotC2SPacket(mc.player.getInventory().selectedSlot));
            timer.reset();
            spoofed = false;
        }
    }

    public void throwPotion(Potions potion) {
        if (pauseAura.getValue()) ModuleManager.aura.pause();
        sendPacket(new UpdateSelectedSlotC2SPacket(getPotionSlot(potion)));
        sendSequencedPacket(id -> new PlayerInteractItemC2SPacket(Hand.MAIN_HAND, id, mc.player.getYaw(), mc.player.getPitch()));
    }

    public enum Potions {
        STRENGTH, SPEED, FIRERES, HEAL, REGEN
    }

    public enum TriggerOn {
        LackOfRegen, Health
    }
}
