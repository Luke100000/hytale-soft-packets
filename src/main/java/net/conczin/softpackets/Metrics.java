package net.conczin.softpackets;

public class Metrics {
    long usedBytes = 0;
    long baseBytes = 0;
    long lastBaseBytes = 0;

    double totalSeconds = 0;
    long totalPackets = 0;

    long currentSecond = 0;

    public long throttlePing = 0;
    public long throttleBuffer = 0;
    public long throttleMax = 0;
    public long drops = 0;
    public long prioritized = 0;

    public void tick() {
        long time = System.nanoTime();
        long second = time / 1_000_000_000;
        if (second != currentSecond) {
            lastBaseBytes = baseBytes;
            usedBytes = 0;
            baseBytes = 0;
            currentSecond = second;
        }
    }

    public void add(int size, long timestampNanos) {
        long time = System.nanoTime();
        long delay = time - timestampNanos;
        usedBytes += size;
        totalPackets += 1;
        totalSeconds += delay / 1_000_000_000.0;
    }

    public long getUsedBytes() {
        return usedBytes;
    }

    public long getBaseBytes() {
        return Math.max(baseBytes, lastBaseBytes);
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
