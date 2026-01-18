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
                    new KeyedCodec<>("MinDistance", Codec.LONG),
                    (config, s) -> config.minDistance = s,
                    config -> config.minDistance
            )
            .documentation("Chunks closer than this distance (in blocks) will not be throttled to avoid falling through the world. However, due to Hytale's minimum rendering distance, you may see visual artifacts when lower than 128.")
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
            .append(
                    new KeyedCodec<>("ThrottleAssetDownloading", Codec.BOOLEAN),
                    (config, s) -> config.throttleAssetDownloading = s,
                    config -> config.throttleAssetDownloading
            )
            .documentation("Whether to throttle asset downloading connections.")
            .add()
            .build();

    private long minBandwidth = 64 * 1024;
    private long maxBandwidth = 16 * 1024 * 1024;

    private long minDistance = 64;

    private boolean throttleWhenPingDegrades = true;
    private boolean throttleLocalConnections = false;
    private boolean throttleAssetDownloading = true;

    public long getMinBandwidth() {
        return minBandwidth;
    }

    public long getMaxBandwidth() {
        return maxBandwidth;
    }

    public long getMinDistance() {
        return minDistance;
    }

    public boolean isThrottleWhenPingDegrades() {
        return throttleWhenPingDegrades;
    }

    public boolean isThrottleLocalConnections() {
        return throttleLocalConnections;
    }

    public boolean isThrottleAssetDownloading() {
        return throttleAssetDownloading;
    }
}