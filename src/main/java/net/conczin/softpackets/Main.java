package net.conczin.softpackets;

import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.HytaleServer;
import com.hypixel.hytale.server.core.HytaleServerConfig;
import com.hypixel.hytale.server.core.io.adapter.PacketAdapters;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;
import com.hypixel.hytale.server.core.util.Config;

import javax.annotation.Nonnull;
import java.time.Duration;
import java.time.temporal.ChronoUnit;


public class Main extends JavaPlugin {
    private static Main instance;
    public static final HytaleLogger LOGGER = HytaleLogger.get("SoftPackets");

    private QueuedPacketSenderSystem queue;

    private final Config<SoftPacketConfig> config = this.withConfig(SoftPacketConfig.CODEC);

    public Main(@Nonnull JavaPluginInit init) {
        super(init);
        instance = this;
    }

    @Override
    protected void setup() {
        queue = new QueuedPacketSenderSystem(getConfig());

        PacketAdapters.registerOutbound(queue);

        this.getChunkStoreRegistry().registerSystem(queue);
        this.getCommandRegistry().registerCommand(new NetworkMonitorCommand());

        config.save();

        // Override connection timeouts to accommodate large modpacks
        HytaleServerConfig.TimeoutProfile connectionTimeouts = HytaleServer.get().getConfig().getConnectionTimeouts();
        if (connectionTimeouts.getPlay() == HytaleServerConfig.TimeoutProfile.defaults().getPlay()) {
            connectionTimeouts.setPlay(Duration.of(10L, ChronoUnit.MINUTES));
            LOGGER.atInfo().log("Overriding play timeout to 10 minutes since soft packets may increase login time.");
        }
    }

    public static Main getInstance() {
        return instance;
    }

    public QueuedPacketSenderSystem getQueue() {
        return queue;
    }

    public SoftPacketConfig getConfig() {
        return config.get();
    }
}