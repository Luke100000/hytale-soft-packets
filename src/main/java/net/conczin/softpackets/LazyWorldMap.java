package net.conczin.softpackets;

import com.hypixel.hytale.protocol.packets.worldmap.MapChunk;
import com.hypixel.hytale.protocol.packets.worldmap.UpdateWorldMap;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class LazyWorldMap {
    private static final int CHUNK_SIZE = 8;

    private final Map<Long, MapChunk> chunks = new HashMap<>();

    public synchronized UpdateWorldMap filter(UpdateWorldMap packet) {
        List<MapChunk> filtered = new ArrayList<>();
        if (packet.chunks != null) {
            for (MapChunk chunk : packet.chunks) {
                long key = chunk.chunkX + chunk.chunkZ * 32L;
                if (chunk.image == null) {
                    chunks.remove(key);
                    filtered.add(chunk);
                } else {
                    chunks.put(key, chunk);
                }
            }
        }
        return new UpdateWorldMap(
                filtered.isEmpty() ? null : filtered.toArray(MapChunk[]::new),
                packet.addedMarkers,
                packet.removedMarkers
        );
    }

    public synchronized UpdateWorldMap getNextPacket() {
        if (chunks.isEmpty()) {
            return null;
        }

        List<MapChunk> chunkList = new ArrayList<>(CHUNK_SIZE);
        while (chunkList.size() < CHUNK_SIZE && !chunks.isEmpty()) {
            long key = chunks.keySet().iterator().next();
            chunkList.add(chunks.remove(key));
        }

        return new UpdateWorldMap(
                chunkList.toArray(MapChunk[]::new),
                null,
                null
        );
    }

    public int getQueueSize() {
        return chunks.size();
    }
}
