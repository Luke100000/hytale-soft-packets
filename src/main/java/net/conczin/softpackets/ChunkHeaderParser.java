package net.conczin.softpackets;

import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.protocol.CachedPacket;
import com.hypixel.hytale.protocol.Packet;
import com.hypixel.hytale.protocol.Vector3i;
import com.hypixel.hytale.protocol.packets.world.*;
import io.netty.buffer.ByteBuf;

import java.lang.reflect.Field;

public class ChunkHeaderParser {
    private static ByteBuf getCachedBytes(CachedPacket<?> packet) {
        try {
            Field field = CachedPacket.class.getDeclaredField("cachedBytes");
            field.setAccessible(true);
            return (ByteBuf) field.get(packet);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }

    public static Vector3i fromChunk(CachedPacket<?> packet) {
        ByteBuf buf = getCachedBytes(packet);
        return new Vector3i(
                buf.getIntLE(1),
                buf.getIntLE(5),
                buf.getIntLE(9)
        );
    }

    public static Vector3i from2dChunk(CachedPacket<?> packet) {
        ByteBuf buf = getCachedBytes(packet);
        return new Vector3i(
                buf.getIntLE(1),
                0,
                buf.getIntLE(5)
        );
    }

    public static Vector3i fromPacket(Packet packet) {
        if (packet instanceof com.hypixel.hytale.protocol.CachedPacket<?> cachedPacket) {
            if (packet.getId() == SetChunk.PACKET_ID || packet.getId() == SetFluids.PACKET_ID) {
                return fromChunk(cachedPacket);
            } else if (packet.getId() == SetChunkHeightmap.PACKET_ID || packet.getId() == SetChunkTintmap.PACKET_ID || packet.getId() == SetChunkEnvironments.PACKET_ID) {
                return from2dChunk(cachedPacket);
            }
        } else if (packet instanceof SetChunk setChunk) {
            return new Vector3i(setChunk.x, setChunk.y, setChunk.z);
        } else if (packet instanceof SetFluids setFluids) {
            return new Vector3i(setFluids.x, setFluids.y, setFluids.z);
        } else if (packet instanceof SetChunkHeightmap setChunkHeightmap) {
            return new Vector3i(setChunkHeightmap.x, 0, setChunkHeightmap.z);
        } else if (packet instanceof SetChunkTintmap setChunkTintmap) {
            return new Vector3i(setChunkTintmap.x, 0, setChunkTintmap.z);
        } else if (packet instanceof SetChunkEnvironments setChunkEnvironments) {
            return new Vector3i(setChunkEnvironments.x, 0, setChunkEnvironments.z);
        }
        return null;
    }

    public static Vector3i fromUnloadPacket(Packet packet) {
        if (packet instanceof CachedPacket<?> cachedPacket) {
            if (packet.getId() == UnloadChunk.PACKET_ID) {
                ByteBuf buffer = getCachedBytes(cachedPacket);
                return new Vector3i(
                        buffer.getIntLE(0),
                        0,
                        buffer.getIntLE(4)
                );
            }
        } else if (packet instanceof UnloadChunk unloadChunk) {
            return new Vector3i(unloadChunk.chunkX, 0, unloadChunk.chunkZ);
        }
        return null;
    }

    public static double distanceTo(Vector3i chunkPos, Vector3d point) {
        return Math.sqrt(
                Math.pow(point.x - (chunkPos.x * 32 + 16), 2) +
                Math.pow(point.z - (chunkPos.z * 32 + 16), 2)
        );
    }
}
