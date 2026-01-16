package net.conczin.softpackets;

import com.hypixel.hytale.protocol.Packet;
import com.hypixel.hytale.server.core.io.netty.PacketArrayEncoder;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;

import javax.annotation.Nonnull;
import java.util.List;

public class FixedPacketArrayEncoder extends PacketArrayEncoder {
    @Override
    protected void encode(ChannelHandlerContext ctx, @Nonnull Packet[] packets, @Nonnull List<Object> out) {
        super.encode(ctx, packets, out);

        if (out.isEmpty()) {
            out.add(Unpooled.EMPTY_BUFFER);
        }
    }
}
