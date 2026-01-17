package net.conczin.softpackets;

import com.hypixel.hytale.common.util.FormatUtil;

public class Metrics {
    SoftPacketConfig config;

    long minimumBucket;
    long maximumBucket;

    long baseBytes = 0;
    double averageBaseBandwidth = 0;
    double averageBaseBandwidthWindow = 0;

    double totalSeconds = 0;
    long totalPackets = 0;

    long lastNano;

    public long throttlePing = 0;
    public long throttleBuffer = 0;
    public long throttleMax = 0;
    public long drops = 0;
    public long prioritized = 0;

    public Metrics(SoftPacketConfig config) {
        this.config = config;
        this.minimumBucket = config.getMinBandwidth();
        this.maximumBucket = config.getMaxBandwidth();
        this.lastNano = System.nanoTime();
    }

    public void tick() {
        long time = System.nanoTime();
        double delta = (time - lastNano) / 1_000_000_000.0;
        lastNano = time;

        // Keep track of the average base bandwidth
        averageBaseBandwidthWindow += delta;
        if (averageBaseBandwidthWindow > 1.0) {
            averageBaseBandwidth = averageBaseBandwidth * 0.9 + baseBytes / averageBaseBandwidthWindow * 0.1;
            averageBaseBandwidthWindow = 0;
            baseBytes = 0;
        }

        // Refill the bucket
        minimumBucket = Math.min(minimumBucket + (long) (config.getMinBandwidth() * delta), config.getMinBandwidth());
        maximumBucket = Math.min(maximumBucket + (long) (config.getMaxBandwidth() * delta), config.getMaxBandwidth());
    }

    public void add(int size, long timestampNanos) {
        long time = System.nanoTime();
        long delay = time - timestampNanos;

        if (minimumBucket > 0) {
            minimumBucket -= size;
        }
        if (maximumBucket > 0) {
            maximumBucket -= size;
        }

        totalPackets += 1;
        totalSeconds += delay / 1_000_000_000.0;
    }

    public long getMinimumBucket() {
        return minimumBucket;
    }

    public long getMaximumBucket() {
        return maximumBucket;
    }

    public long getBaseBytes() {
        return (long) averageBaseBandwidth;
    }

    public double getTotalSeconds() {
        return totalSeconds;
    }

    public long getTotalPackets() {
        return totalPackets;
    }

    public void addBase(int packetSize) {
        baseBytes += packetSize;
    }
}
