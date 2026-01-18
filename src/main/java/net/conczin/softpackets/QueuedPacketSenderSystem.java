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
import com.hypixel.hytale.protocol.packets.setup.*;
import com.hypixel.hytale.protocol.packets.world.*;
import com.hypixel.hytale.protocol.packets.worldmap.UpdateWorldMap;
import com.hypixel.hytale.server.core.io.PacketHandler;
import com.hypixel.hytale.server.core.io.adapter.PacketFilter;
import com.hypixel.hytale.server.core.io.handlers.game.GamePacketHandler;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import io.netty.channel.Channel;
import io.netty.channel.ChannelPipeline;

import javax.annotation.Nonnull;
import java.util.*;

public class QueuedPacketSenderSystem extends TickingSystem<ChunkStore> implements RunWhenPausedSystem<ChunkStore>, PacketFilter {
    private static final double MIN_VERIFY_DISTANCE = 32;

    public final Map<PacketHandler, PlayerQueue> queues = Collections.synchronizedMap(new WeakHashMap<>());

    public final Metrics metrics;
    private final SoftPacketConfig config;
    private final double minDistance;

    private Packet currentPacket = null;

    private static final Set<Integer> largePacketIds = Set.of(
            // Chunk
            SetChunk.PACKET_ID,
            SetChunkHeightmap.PACKET_ID,
            SetChunkTintmap.PACKET_ID,
            SetChunkEnvironments.PACKET_ID,
            SetFluids.PACKET_ID
    );

    private static final Set<Integer> assetPacketIds = Set.of(
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

            // Assets
            AssetInitialize.PACKET_ID,
            AssetPart.PACKET_ID,
            AssetFinalize.PACKET_ID,

            // Progress indicators
            WorldLoadProgress.PACKET_ID,
            WorldLoadFinished.PACKET_ID
    );

    private static final Map<Integer, Double> compressionRatios = Map.of(
            SetChunk.PACKET_ID, 0.065,
            SetChunkHeightmap.PACKET_ID, 0.5,
            SetChunkTintmap.PACKET_ID, 0.75,
            SetChunkEnvironments.PACKET_ID, 0.15,
            SetFluids.PACKET_ID, 0.05,
            UpdateWorldMap.PACKET_ID, 0.3,
            AssetPart.PACKET_ID, 0.85

    );

    public QueuedPacketSenderSystem(SoftPacketConfig config) {
        this.metrics = new Metrics(config);
        this.config = Main.getInstance().getConfig();
        this.minDistance = Math.pow(config.getMinDistance() + MIN_VERIFY_DISTANCE, 2.0);
    }

    @Override
    public void tick(float dt, int idx, @Nonnull Store<ChunkStore> store) {
        metrics.tick();

        // Random order to prevent starvation
        List<Map.Entry<PacketHandler, PlayerQueue>> entries;
        synchronized (queues) {
            entries = new ArrayList<>(queues.entrySet());
        }
        Collections.shuffle(entries);

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
            queue.verify(handler);

            while (!queue.isEmpty()) {
                if (metrics.getMinimumBucket() <= 0) {
                    // Maximum reached
                    if (metrics.getMaximumBucket() - metrics.getBaseBytes() <= 0) {
                        metrics.throttleMax++;
                        break;
                    }

                    // Ping degraded
                    if (pingDegraded && config.isThrottleWhenPingDegrades()) {
                        metrics.throttlePing++;
                        break;
                    }

                    // Channel buffer full
                    Channel channel = handler.getChannel();
                    if (!channel.isWritable()) {
                        metrics.throttleBuffer++;
                        break;
                    }
                }

                // Send the packet
                CachedPacket packet = queue.poll();
                if (packet == null) break;
                currentPacket = packet.packet();
                handler.write(currentPacket);
                currentPacket = null;

                // Record usage if the handler is active
                // (dead channels usually don't consume real bandwidth)
                // (but the channel may also still be used despite being inactive, e.g., during login)
                if (handler.stillActive()) {
                    metrics.add(packet.size, packet.time);
                }
            }
        }
    }

    public static final FixedPacketArrayEncoder FILTER = new FixedPacketArrayEncoder();

    @Override
    public boolean test(PacketHandler handler, Packet packet) {
        if (!queues.containsKey(handler)) {
            queues.put(handler, new PlayerQueue(handler));
            fixPipeline(handler);
        }

        // If this is a new packet
        if (packet != currentPacket) {
            PlayerQueue playerQueue = queues.get(handler);

            // Don't throttle local connections if disabled
            if (handler.isLocalConnection() && !config.isThrottleLocalConnections()) {
                return false;
            }

            // Default is JSON-based assets with ~80% compression
            int packetSize = getSize(packet);

            // This is an unload-chunk-packet, clear chunk updates not even sent yet from the queue
            Vector3i unloadChunkPos = ChunkHeaderParser.fromUnloadPacket(packet);
            if (unloadChunkPos != null) {
                playerQueue.remove(unloadChunkPos);
            }

            // Throttle large packets
            if (largePacketIds.contains(packet.getId()) || config.isThrottleAssetDownloading() && assetPacketIds.contains(packet.getId())) {
                if (tooCLose(handler, packet)) {
                    // Send it immediately
                    metrics.prioritized++;
                    return false;
                } else {
                    // Queue the packet
                    playerQueue.add(packet, packetSize);
                    return true;
                }
            }

            // Intercept and rebatch world map updates
            if (packet instanceof UpdateWorldMap updateWorldMap) {
                UpdateWorldMap filter = playerQueue.getLazyMap().filter(updateWorldMap);
                currentPacket = filter;
                handler.write(filter);
                currentPacket = null;
                return true;
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
        double distance = ChunkHeaderParser.distanceTo3dSquared(chunkPos, position);
        return distance < minDistance;
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

    public record CachedPacket(Packet packet, int size, long time, Vector3i chunkPos) {
        public CachedPacket(Packet packet, int size) {
            this(packet, size, System.nanoTime(), ChunkHeaderParser.fromPacket(packet));
        }
    }

    private static int getSize(Packet packet) {
        return (int) (packet.computeSize() * compressionRatios.getOrDefault(packet.getId(), 0.25));
    }

    public class PlayerQueue {
        private final Queue<CachedPacket> assetQueue = new LinkedList<>();
        private Queue<CachedPacket> chunkQueue;

        private final LazyWorldMap lazyMap = new LazyWorldMap();

        public Vector3d lastPosition;
        public long queueSize = 0;

        public PlayerQueue(PacketHandler handler) {
            this.lastPosition = getPlayerPosition(handler).clone();
            this.chunkQueue = getSortedQueue(lastPosition, 32);
        }

        public synchronized void add(Packet packet, int packetSize) {
            CachedPacket cachedPacket = new CachedPacket(packet, packetSize);
            if (cachedPacket.chunkPos == null) {
                assetQueue.add(cachedPacket);
            } else {
                chunkQueue.add(cachedPacket);
            }
            queueSize += packetSize;
        }

        public boolean isEmpty() {
            return assetQueue.isEmpty() && chunkQueue.isEmpty() && lazyMap.getQueueSize() == 0;
        }

        public int getSize() {
            return assetQueue.size() + chunkQueue.size();
        }

        public synchronized CachedPacket poll() {
            CachedPacket packet = assetQueue.poll();
            if (packet == null) {
                packet = chunkQueue.poll();
            }
            if (packet != null) {
                queueSize -= packet.size;
            }
            if (packet == null) {
                UpdateWorldMap nextPacket = lazyMap.getNextPacket();
                if (nextPacket != null) {
                    packet = new CachedPacket(nextPacket, QueuedPacketSenderSystem.getSize(nextPacket));
                }
            }
            return packet;
        }

        public synchronized void verify(PacketHandler handler) {
            // Only recheck if the player has moved significantly
            Vector3d playerPosition = getPlayerPosition(handler);
            if (playerPosition == null || playerPosition.distanceTo(lastPosition) < MIN_VERIFY_DISTANCE) {
                return;
            }
            lastPosition.assign(playerPosition);

            // Send chunks too close immediately and resort queue
            long time = System.nanoTime();
            Queue<CachedPacket> sortedQueue = getSortedQueue(playerPosition, chunkQueue.size());
            for (CachedPacket p : chunkQueue) {
                if (ChunkHeaderParser.distanceTo3dSquared(p.chunkPos, playerPosition) < minDistance) {
                    handler.write(p.packet);
                    queueSize -= p.size;
                    metrics.prioritized++;
                } else {
                    sortedQueue.add(p);
                }
            }
            this.chunkQueue = sortedQueue;
            metrics.timeSorted += System.nanoTime() - time;
        }

        private static PriorityQueue<CachedPacket> getSortedQueue(Vector3d playerPosition, int initialCapacity) {
            return new PriorityQueue<>(Math.max(32, initialCapacity), Comparator.comparingDouble(p -> ChunkHeaderParser.distanceTo3dSquared(p.chunkPos, playerPosition)));
        }

        public LazyWorldMap getLazyMap() {
            return lazyMap;
        }

        public synchronized void remove(Vector3i unloadChunkPos) {
            // Drop unloaded chunks from the queue
            Iterator<CachedPacket> it = chunkQueue.iterator();
            while (it.hasNext()) {
                CachedPacket p = it.next();
                if (p.chunkPos.x == unloadChunkPos.x && p.chunkPos.z == unloadChunkPos.z) {
                    queueSize -= p.size;
                    it.remove();
                    metrics.drops++;
                }
            }
        }
    }
}
