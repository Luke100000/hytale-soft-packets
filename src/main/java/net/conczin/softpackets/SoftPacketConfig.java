package net.conczin.softpackets;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;

public class SoftPacketConfig {
    public static final BuilderCodec<SoftPacketConfig> CODEC = BuilderCodec.builder(SoftPacketConfig.class, SoftPacketConfig::new)
            .append(
                    new KeyedCodec<>("MinBandwidth", Codec.LONG),
                    (config, s) -> config.minBandwidth = s,
                    config -> config.minBandwidth
            )
            .documentation("Minimum bandwidth in bytes per second.")
            .add()
            .append(
                    new KeyedCodec<>("MaxBandwidth", Codec.LONG),
                    (config, s) -> config.maxBandwidth = s,
                    config -> config.maxBandwidth
            )
            .documentation("Maximum bandwidth in bytes per second.")
            .add()
            .append(
                    new KeyedCodec<>("BufferReserveMargin", Codec.LONG),
                    (config, s) -> config.bufferReserveMargin = s,
                    config -> config.bufferReserveMargin
            )
            .documentation("Amount of extra buffer to keep in bytes.")
            .add()
            .append(
                    new KeyedCodec<>("ThrottleWhenPingDegrades", Codec.BOOLEAN),
                    (config, s) -> config.throttleWhenPingDegrades = s,
                    config -> config.throttleWhenPingDegrades
            )
            .documentation("Whether to throttle bandwidth when player ping degrades.")
            .add()
            .append(
                    new KeyedCodec<>("ThrottleLocalConnections", Codec.BOOLEAN),
                    (config, s) -> config.throttleLocalConnections = s,
                    config -> config.throttleLocalConnections
            )
            .documentation("Whether to throttle local connections as well.")
            .add()
            .build();

    private long minBandwidth = 64 * 1024;
    private long maxBandwidth = 16 * 1024 * 1024;
    private long bufferReserveMargin = 16 * 1024;
    private boolean throttleWhenPingDegrades = true;
    private boolean throttleLocalConnections = false;

    public long getMinBandwidth() {
        return minBandwidth;
    }
    public long getMaxBandwidth() {
        return maxBandwidth;
    }

    public long getBufferReserveMargin() {
        return bufferReserveMargin;
    }

    public boolean isThrottleWhenPingDegrades() {
        return throttleWhenPingDegrades;
    }

    public boolean isThrottleLocalConnections() {
        return throttleLocalConnections;
    }
}