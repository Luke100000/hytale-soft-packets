package net.conczin.softpackets;

import com.hypixel.hytale.common.util.FormatUtil;
import com.hypixel.hytale.protocol.GameMode;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.auth.PlayerAuthentication;
import com.hypixel.hytale.server.core.command.system.AbstractCommand;
import com.hypixel.hytale.server.core.command.system.CommandContext;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public class NetworkMonitorCommand extends AbstractCommand {
    public NetworkMonitorCommand() {
        super("softpackets", "Displays soft packet stats.");
        this.addAliases("sp");
        this.setPermissionGroup(GameMode.Creative);
    }

    @Nullable
    @Override
    protected CompletableFuture<Void> execute(@Nonnull CommandContext context) {
        QueuedPacketSenderSystem queue = Main.getInstance().getQueue();
        long baseBytes = queue.metrics.getBaseBytes();
        double totalDelay = queue.metrics.getTotalSeconds();
        long packets = queue.metrics.getTotalPackets();

        context.sendMessage(Message.raw("Soft Packet Stats:"));
        context.sendMessage(Message.raw(" Base bandwidth: " + FormatUtil.bytesToString(baseBytes)));
        context.sendMessage(Message.raw(" Average delay: " + FormatUtil.simpleTimeUnitFormat((long) (totalDelay / Math.max(packets, 1) * 1000), TimeUnit.MILLISECONDS, 2)));
        context.sendMessage(Message.raw(" Packets throttled: " + packets));
        context.sendMessage(Message.raw(" Throttles: Ping=" + queue.metrics.throttlePing + " Buffer=" + queue.metrics.throttleBuffer + " Max=" + queue.metrics.throttleMax));
        context.sendMessage(Message.raw(" Prioritized " + queue.metrics.prioritized + " packets, dropped " + queue.metrics.drops));
        context.sendMessage(Message.raw(" Spent " + FormatUtil.simpleTimeUnitFormat(queue.metrics.timeSorted, TimeUnit.NANOSECONDS, 4) + " sorting packets"));

        queue.queues.forEach((handler, q) -> {
            if (!q.isEmpty()) {
                PlayerAuthentication auth = handler.getAuth();
                if (auth == null) return;
                String identifier = auth.getUsername();
                String s = FormatUtil.bytesToString(q.queueSize);
                context.sendMessage(Message.raw(String.format("    %s: %s packets, %s, %s map chunks", identifier, q.getSize(), s, q.getLazyMap().getQueueSize())));
            }
        });

        return CompletableFuture.completedFuture(null);
    }
}