package net.conczin.softpackets;

import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.system.tick.RunWhenPausedSystem;
import com.hypixel.hytale.component.system.tick.TickingSystem;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.metrics.metric.HistoricMetric;
import com.hypixel.hytale.protocol.Packet;
import com.hypixel.hytale.protocol.Vector3i;
import com.hypixel.hytale.protocol.packets.assets.*;
import com.hypixel.hytale.protocol.packets.connection.PongType;
import com.hypixel.hytale.protocol.packets.player.JoinWorld;
import com.hypixel.hytale.protocol.packets.setup.*;
import com.hypixel.hytale.protocol.packets.world.*;
import com.hypixel.hytale.protocol.packets.worldmap.UpdateWorldMap;
import com.hypixel.hytale.server.core.io.PacketHandler;
import com.hypixel.hytale.server.core.io.adapter.PacketFilter;
import com.hypixel.hytale.server.core.io.handlers.game.GamePacketHandler;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import io.netty.channel.ChannelPipeline;

import javax.annotation.Nonnull;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedDeque;

public class QueuedPacketSenderSystem extends TickingSystem<ChunkStore> implements RunWhenPausedSystem<ChunkStore>, PacketFilter {
    public final Map<PacketHandler, PlayerQueue> queue = Collections.synchronizedMap(new WeakHashMap<>());

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

        // Random order to prevent starvation
        List<Map.Entry<PacketHandler, PlayerQueue>> entries;
        synchronized (queue) {
            entries = new ArrayList<>(queue.entrySet());
        }
        Collections.shuffle(entries);

        SoftPacketConfig config = Main.getInstance().getConfig();

        for (Map.Entry<PacketHandler, PlayerQueue> entry : entries) {
            PlayerQueue queue = entry.getValue();

            if (queue.isEmpty()) continue;

            // Observe player ping
            PacketHandler handler = entry.getKey();
            HistoricMetric ping = handler.getPingInfo(PongType.Raw).getPingMetricSet();
            double current = ping.getAverage(0);
            double base = ping.getAverage(ping.getPeriodsNanos().length - 1);
            double ratio = (current + 1) / (base + 1);
            boolean pingDegraded = ratio > 2.0;

            // Verify the status of the queue
            queue.verify();

            while (!queue.isEmpty()) {
                if (metrics.getUsedBytes() > config.getMinBandwidth()) {
                    // Maximum reached
                    if (metrics.getBaseBytes() + metrics.getUsedBytes() > config.getMaxBandwidth()) {
                        metrics.throttleMax++;
                        break;
                    }

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
            queue.put(handler, new PlayerQueue(handler));
            fixPipeline(handler);
        }

        // If this is a new packet
        if (packet != currentPacket) {
            int packetSize = packet.computeSize();

            // Throttle large packets
            if (largePacketIds.contains(packet.getId()) && (!handler.isLocalConnection() || Main.getInstance().getConfig().isThrottleLocalConnections())) {
                if (tooCLose(handler, packet)) {
                    // Send it immediately
                    metrics.prioritized++;
                    return false;
                } else {
                    // Queue the packet
                    queue.get(handler).add(packet, packetSize);
                    return true;
                }
            }

            // Record the rest as base usage
            metrics.addBase(packetSize);
        }

        return false;
    }

    private static Vector3d getPlayerPosition(PacketHandler handler) {
        if (handler instanceof GamePacketHandler gamePacketHandler) {
            return gamePacketHandler.getPlayerRef().getTransform().getPosition();
        }
        return Vector3d.ZERO;
    }

    private boolean tooCLose(PacketHandler handler, Packet packet) {
        Vector3d position = getPlayerPosition(handler);
        if (position == Vector3d.ZERO) return false;
        Vector3i chunkPos = ChunkHeaderParser.fromPacket(packet);
        if (chunkPos == null) return false;
        double distance = ChunkHeaderParser.distanceTo(chunkPos, position);
        return distance < Main.getInstance().getConfig().getMinDistance();
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

    public class PlayerQueue {
        public final ConcurrentLinkedDeque<CachedPacket> queue = new ConcurrentLinkedDeque<>();
        public Vector3d lastPosition;
        public long queueSize = 0;
        public PacketHandler handler;

        public PlayerQueue(PacketHandler handler) {
            this.lastPosition = getPlayerPosition(handler);
            this.handler = handler;
        }

        public void add(Packet packet, int packetSize) {
            queue.add(new CachedPacket(packet, packetSize));
            queueSize += packetSize;
        }

        public boolean isEmpty() {
            return queue.isEmpty();
        }

        public CachedPacket poll() {
            CachedPacket packet = queue.poll();
            if (packet != null) {
                queueSize -= packet.size;
            }
            return packet;
        }

        public void verify() {
            Vector3d playerPosition = getPlayerPosition(handler);
            SoftPacketConfig config = Main.getInstance().getConfig();
            if (playerPosition == null || playerPosition.distanceTo(lastPosition) < config.getMinDistance() / 2.0) {
                return;
            }
            lastPosition = playerPosition;

            Set<Long> removedChunks = new HashSet<>();
            Iterator<CachedPacket> it = queue.descendingIterator();
            while (it.hasNext()) {
                CachedPacket cachedPacket = it.next();
                Packet packet = cachedPacket.packet();
                Vector3i chunkPos = ChunkHeaderParser.fromPacket(packet);

                // Chunk already unloaded, drop the packet
                if (chunkPos != null && removedChunks.contains(chunkPos.x + (((long) chunkPos.z) << 32))) {
                    queueSize -= cachedPacket.size;
                    it.remove();
                    metrics.drops++;
                    continue;
                }

                // Send chunks too close immediately
                if (chunkPos != null && ChunkHeaderParser.distanceTo(chunkPos, playerPosition) < config.getMinDistance()) {
                    handler.write(cachedPacket.packet);
                    queueSize -= cachedPacket.size;
                    metrics.prioritized++;
                    it.remove();
                }

                // Remember unloaded chunks
                Vector3i unloadChunkPos = ChunkHeaderParser.fromUnloadPacket(packet);
                if (unloadChunkPos != null) {
                    long chunkKey = unloadChunkPos.x + (((long) unloadChunkPos.z) << 32);
                    removedChunks.add(chunkKey);
                }
            }
        }
    }
}
