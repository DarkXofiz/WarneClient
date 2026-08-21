package warne.client.features.modules.misc;

import meteordevelopment.orbit.EventHandler;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.ChatMessageC2SPacket;
import warne.client.core.Managers;
import warne.client.events.impl.PacketEvent;
import warne.client.features.modules.Module;
import warne.client.setting.Setting;

import java.util.Objects;

public class MessageAppend extends Module {
    public MessageAppend() {
        super("MessageAppend", Category.MISC);
    }

    private final Setting<String> word = new Setting<>("word", " TH RECODE");
    private String skip;

    @EventHandler
    public void onPacketSend(PacketEvent.Send e) {
        if (fullNullCheck()) return;
        if (e.getPacket() instanceof ChatMessageC2SPacket pac) {
            if (Objects.equals(pac.chatMessage(), skip)) {
                return;
            }

            // Чтоб не добавляло когда ты вводишь капчу на сервере
            if (mc.player.getMainHandStack().getItem() == Items.FILLED_MAP || mc.player.getOffHandStack().getItem() == Items.FILLED_MAP)
                return;

            if (pac.chatMessage().startsWith("/") || pac.chatMessage().startsWith(Managers.COMMAND.getPrefix()))
                return;

            skip = pac.chatMessage() + word.getValue();
            mc.player.networkHandler.sendChatMessage(pac.chatMessage() + word.getValue());
            e.cancel();
        }
    }
}