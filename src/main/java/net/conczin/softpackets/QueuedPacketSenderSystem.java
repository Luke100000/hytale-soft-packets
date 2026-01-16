package net.conczin.softpackets;

import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.system.tick.RunWhenPausedSystem;
import com.hypixel.hytale.component.system.tick.TickingSystem;
import com.hypixel.hytale.metrics.metric.HistoricMetric;
import com.hypixel.hytale.protocol.Packet;
import com.hypixel.hytale.protocol.packets.assets.*;
import com.hypixel.hytale.protocol.packets.connection.PongType;
import com.hypixel.hytale.protocol.packets.player.JoinWorld;
import com.hypixel.hytale.protocol.packets.setup.*;
import com.hypixel.hytale.protocol.packets.world.*;
import com.hypixel.hytale.protocol.packets.worldmap.UpdateWorldMap;
import com.hypixel.hytale.server.core.io.PacketHandler;
import com.hypixel.hytale.server.core.io.adapter.PacketFilter;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import io.netty.channel.ChannelPipeline;

import javax.annotation.Nonnull;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;

public class QueuedPacketSenderSystem extends TickingSystem<ChunkStore> implements RunWhenPausedSystem<ChunkStore>, PacketFilter {
    public final Map<PacketHandler, Queue<CachedPacket>> queue = Collections.synchronizedMap(new WeakHashMap<>());

    public final Metrics metrics = new Metrics();

    private Packet currentPacket = null;

    private static final Set<Integer> largePacketIds = Set.of(
            // Chunk
            SetChunk.PACKET_ID,
            SetChunkHeightmap.PACKET_ID,
            SetChunkTintmap.PACKET_ID,
            SetChunkEnvironments.PACKET_ID,
            SetFluids.PACKET_ID,
            // World Map
            UpdateWorldMap.PACKET_ID,
            // Delayed to prevent unexpected behavior
            JoinWorld.PACKET_ID,
            // Assets
            AssetInitialize.PACKET_ID,
            AssetPart.PACKET_ID,
            AssetFinalize.PACKET_ID,
            // Configs
            UpdateParticleSystems.PACKET_ID,
            UpdateItemCategories.PACKET_ID,
            UpdateFieldcraftCategories.PACKET_ID,
            UpdateItemSoundSets.PACKET_ID,
            UpdateItemReticles.PACKET_ID,
            UpdateEnvironments.PACKET_ID,
            UpdateBlockBreakingDecals.PACKET_ID,
            UpdateAudioCategories.PACKET_ID,
            UpdateResourceTypes.PACKET_ID,
            UpdateEntityStatTypes.PACKET_ID,
            UpdateHitboxCollisionConfig.PACKET_ID,
            UpdateReverbEffects.PACKET_ID,
            UpdateInteractions.PACKET_ID,
            UpdateRootInteractions.PACKET_ID,
            UpdateEqualizerEffects.PACKET_ID,
            UpdateBlockGroups.PACKET_ID,
            UpdateBlockParticleSets.PACKET_ID,
            UpdateBlockHitboxes.PACKET_ID,
            UpdateAmbienceFX.PACKET_ID,
            UpdateBlockTypes.PACKET_ID,
            UpdateProjectileConfigs.PACKET_ID,
            UpdateTagPatterns.PACKET_ID,
            UpdateBlockSoundSets.PACKET_ID,
            UpdateFluidFX.PACKET_ID,
            UpdateEntityEffects.PACKET_ID,
            UpdateEntityUIComponents.PACKET_ID,
            UpdateUnarmedInteractions.PACKET_ID,
            UpdateItemPlayerAnimations.PACKET_ID,
            UpdateCameraShake.PACKET_ID,
            UpdateSoundEvents.PACKET_ID,
            UpdateItems.PACKET_ID,
            UpdateWeathers.PACKET_ID,
            UpdateItemQualities.PACKET_ID,
            UpdateRecipes.PACKET_ID,
            UpdateBlockSets.PACKET_ID,
            UpdateSoundSets.PACKET_ID,
            UpdateParticleSpawners.PACKET_ID,
            UpdateViewBobbing.PACKET_ID,
            UpdateFluids.PACKET_ID,
            UpdateModelvfxs.PACKET_ID,
            UpdateRepulsionConfig.PACKET_ID,
            UpdateTrails.PACKET_ID,
            UpdateTranslations.PACKET_ID,
            // Progress indicators
            WorldLoadProgress.PACKET_ID,
            WorldLoadFinished.PACKET_ID
    );

    @Override
    public void tick(float dt, int idx, @Nonnull Store<ChunkStore> store) {
        metrics.tick();

        SoftPacketConfig config = Main.getInstance().getConfig();

        // Random order to prevent starvation
        List<Map.Entry<PacketHandler, Queue<CachedPacket>>> entries;
        synchronized (queue) {
            entries = new ArrayList<>(queue.entrySet());
        }
        Collections.shuffle(entries);

        for (Map.Entry<PacketHandler, Queue<CachedPacket>> entry : entries) {
            Queue<CachedPacket> queue = entry.getValue();
            PacketHandler handler = entry.getKey();

            if (queue.isEmpty()) continue;

            // Observe player ping
            HistoricMetric ping = handler.getPingInfo(PongType.Raw).getPingMetricSet();
            double current = ping.getAverage(0);
            double base = ping.getAverage(ping.getPeriodsNanos().length - 1);
            double ratio = (current + 1) / (base + 1);
            boolean pingDegraded = ratio > 2.0;

            while (!queue.isEmpty()) {
                if (metrics.getUsedBytes() > config.getMinBandwidth()) {
                    // Maximum reached
                    if (metrics.getBaseBytes() + metrics.getUsedBytes() > config.getMaxBandwidth()) {
                        metrics.throttleMax++;
                        break;
                    };

                    // Ping degraded
                    if (pingDegraded && config.isThrottleWhenPingDegrades()) {
                        metrics.throttlePing++;
                        break;
                    }

                    // Channel buffer full
                    if (handler.getChannel().bytesBeforeUnwritable() <= config.getBufferReserveMargin()) {
                        metrics.throttleBuffer++;
                        break;
                    }
                }

                // Send the packet
                CachedPacket packet = queue.poll();
                if (packet == null) break;
                currentPacket = packet.packet();
                handler.write(packet.packet);
                currentPacket = null;

                // Record usage
                metrics.add(packet.size, packet.time);
            }
        }
    }

    public static final FixedPacketArrayEncoder FILTER = new FixedPacketArrayEncoder();

    @Override
    public boolean test(PacketHandler handler, Packet packet) {
        if (!queue.containsKey(handler)) {
            queue.put(handler, new ConcurrentLinkedQueue<>());
            fixPipeline(handler);
        }

        // If this is a new packet
        if (packet != currentPacket) {
            int packetSize = packet.computeSize();

            // Throttle large packets
            if (largePacketIds.contains(packet.getId()) && (!handler.isLocalConnection() || Main.getInstance().getConfig().isThrottleLocalConnections())) {
                queue.get(handler).add(new CachedPacket(packet, packetSize));
                return true;
            }

            // Record the rest as base usage
            metrics.addBase(packetSize);
        }

        return false;
    }

    private static void fixPipeline(PacketHandler handler) {
        try {
            ChannelPipeline p = handler.getChannel().pipeline();
            p.replace(
                    "packetArrayEncoder",
                    "fixedPacketArrayEncoder",
                    FILTER
            );
        } catch (Exception ignored) {
            Main.LOGGER.atSevere().log("Failed to replace packet array encoder in pipeline for handler " + handler);
        }
    }

    public record CachedPacket(Packet packet, int size, long time) {
        public CachedPacket(Packet packet, int size) {
            this(packet, size, System.nanoTime());
        }
    }
}
