package warne.client.injection;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.proxy.Socks5ProxyHandler;

import net.minecraft.network.packet.s2c.play.BundleS2CPacket;
import warne.client.WarneClient;
import warne.client.core.Managers;
import warne.client.core.manager.client.ModuleManager;
import warne.client.core.manager.client.ProxyManager;
import warne.client.events.impl.PacketEvent;
import warne.client.features.modules.Module;
import net.minecraft.network.ClientConnection;
import net.minecraft.network.listener.PacketListener;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.NetworkSide;
import net.minecraft.network.handler.PacketSizeLogger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.net.InetSocketAddress;

@Mixin(ClientConnection.class)
public class MixinClientConnection {

    @Inject(method = "exceptionCaught", at = @At("HEAD"), cancellable = true)
    private void exceptionCaughtHook(ChannelHandlerContext context, Throwable t, CallbackInfo ci) {
        if (ModuleManager.antiPacketException.isEnabled()) {
            ModuleManager.antiPacketException.sendChatMessage(t.getMessage());
            ci.cancel();
        }
    }

    @Inject(method = "handlePacket", at = @At("HEAD"), cancellable = true)
    private static <T extends PacketListener> void onHandlePacket(Packet<T> packet, PacketListener listener, CallbackInfo info) {
        if(Module.fullNullCheck()) return;
        if (packet instanceof BundleS2CPacket packs) {
            packs.getPackets().forEach(p -> {
                PacketEvent.Receive event = new PacketEvent.Receive(p);
                WarneClient.EVENT_BUS.post(event);
                if (event.isCancelled()) {
                    info.cancel();
                }
            });
        } else {
            PacketEvent.Receive event = new PacketEvent.Receive(packet);
            WarneClient.EVENT_BUS.post(event);
            if (event.isCancelled()) {
                info.cancel();
            }
        }
    }


    @Inject(method = "handlePacket", at = @At("TAIL"), cancellable = true)
    private static <T extends PacketListener> void onHandlePacketPost(Packet<T> packet, PacketListener listener, CallbackInfo info) {
        if(Module.fullNullCheck()) return;
        if (packet instanceof BundleS2CPacket packs) {
            packs.getPackets().forEach(p -> {
                PacketEvent.ReceivePost event = new PacketEvent.ReceivePost(p);
                WarneClient.EVENT_BUS.post(event);
                if (event.isCancelled()) {
                    info.cancel();
                }
            });
        } else {
            PacketEvent.ReceivePost event = new PacketEvent.ReceivePost(packet);
            WarneClient.EVENT_BUS.post(event);
            if (event.isCancelled()) {
                info.cancel();
            }
        }
    }

    @Inject(method = "send(Lnet/minecraft/network/packet/Packet;)V", at = @At("HEAD"),cancellable = true)
    private void onSendPacketPre(Packet<?> packet, CallbackInfo info) {
        if(Module.fullNullCheck()) return;
        if(WarneClient.core.silentPackets.contains(packet)) {
            WarneClient.core.silentPackets.remove(packet);
            return;
        }

        PacketEvent.Send event = new PacketEvent.Send(packet);
        WarneClient.EVENT_BUS.post(event);
        if (event.isCancelled()) info.cancel();
    }

    @Inject(method = "send(Lnet/minecraft/network/packet/Packet;)V", at = @At("RETURN"),cancellable = true)
    private void onSendPacketPost(Packet<?> packet, CallbackInfo info) {
        if(Module.fullNullCheck()) return;
        PacketEvent.SendPost event = new PacketEvent.SendPost(packet);
        WarneClient.EVENT_BUS.post(event);
        if (event.isCancelled()) info.cancel();
    }

    // Thanks to Meteor
    @Inject(method = "addHandlers", at = @At("RETURN"))
    private static void addHandlersHook(ChannelPipeline pipeline, NetworkSide side, boolean local, PacketSizeLogger packetSizeLogger, CallbackInfo ci) {
        ProxyManager.ThProxy proxy = Managers.PROXY.getActiveProxy();
        if (proxy != null && side == NetworkSide.CLIENTBOUND && !local)
            pipeline.addFirst(new Socks5ProxyHandler(new InetSocketAddress(proxy.getIp(), proxy.getPort()), proxy.getL(), proxy.getP()));
    }
}