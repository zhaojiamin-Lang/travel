package com.travelguide.memory;

import com.travelguide.memory.MemoryModels.MemoryAudit;
import com.travelguide.memory.MemoryModels.PreferenceSignal;
import com.travelguide.memory.MemoryModels.RecalledMemory;
import com.travelguide.memory.MemoryModels.SignalStatus;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * 长期偏好记忆的写入与召回。
 *
 * <p>显式纠正会替代旧信号但保留审计；推断偏好必须有较高置信度才允许写入。</p>
 */
public final class MemoryService {

    private static final double MIN_INFERRED_CONFIDENCE = 0.70;

    private final MemoryStore store;

    public MemoryService(MemoryStore store) {
        this.store = store;
    }

    public PreferenceSignal rememberExplicit(
            String userId,
            String type,
            String key,
            String value,
            String sourceId,
            Instant now
    ) {
        return remember(userId, type, key, value, 1.0, sourceId, null, now);
    }

    public PreferenceSignal rememberInferred(
            String userId,
            String type,
            String key,
            String value,
            double confidence,
            String sourceId,
            Instant expiresAt,
            Instant now
    ) {
        if (confidence < MIN_INFERRED_CONFIDENCE) {
            throw new IllegalArgumentException("低置信度推断不能写入长期记忆");
        }
        return remember(userId, type, key, value, confidence, sourceId, expiresAt, now);
    }

    public List<RecalledMemory> recall(String userId, String type, Instant now, int limit) {
        Instant effectiveNow = now == null ? Instant.now() : now;
        return store.findPreferenceSignals(userId).stream()
                .filter(signal -> signal.status() == SignalStatus.ACTIVE)
                .filter(signal -> signal.expiresAt() == null || effectiveNow.isBefore(signal.expiresAt()))
                .filter(signal -> type == null || type.isBlank() || type.equals(signal.type()))
                .map(signal -> new RecalledMemory(
                        signal,
                        score(signal, effectiveNow),
                        "confidence × recency"
                ))
                .sorted(Comparator.comparingDouble(RecalledMemory::score).reversed())
                .limit(limit)
                .toList();
    }

    public void forget(String userId, String signalId, String reason, Instant now) {
        store.findPreferenceSignals(userId).stream()
                .filter(signal -> signal.signalId().equals(signalId))
                .findFirst()
                .map(signal -> new PreferenceSignal(
                        signal.signalId(),
                        signal.userId(),
                        signal.type(),
                        signal.key(),
                        signal.value(),
                        signal.confidence(),
                        signal.sourceId(),
                        signal.observedAt(),
                        signal.expiresAt(),
                        SignalStatus.DELETED
                ))
                .ifPresent(signal -> {
                    store.savePreferenceSignal(signal);
                    audit(userId, signal.signalId(), "DELETE", reason, now);
                });
    }

    private PreferenceSignal remember(
            String userId,
            String type,
            String key,
            String value,
            double confidence,
            String sourceId,
            Instant expiresAt,
            Instant now
    ) {
        Instant effectiveNow = now == null ? Instant.now() : now;
        store.findPreferenceSignals(userId).stream()
                .filter(signal -> signal.status() == SignalStatus.ACTIVE)
                .filter(signal -> signal.type().equals(type) && signal.key().equals(key))
                .filter(signal -> !signal.value().equals(value))
                .forEach(signal -> {
                    store.savePreferenceSignal(new PreferenceSignal(
                            signal.signalId(),
                            signal.userId(),
                            signal.type(),
                            signal.key(),
                            signal.value(),
                            signal.confidence(),
                            signal.sourceId(),
                            signal.observedAt(),
                            signal.expiresAt(),
                            SignalStatus.SUPERSEDED
                    ));
                    audit(userId, signal.signalId(), "SUPERSEDE", "explicit correction", effectiveNow);
                });

        PreferenceSignal signal = new PreferenceSignal(
                "mem_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12),
                userId,
                type,
                key,
                value,
                confidence,
                sourceId,
                effectiveNow,
                expiresAt,
                SignalStatus.ACTIVE
        );
        store.savePreferenceSignal(signal);
        audit(userId, signal.signalId(), "UPSERT", "memory write", effectiveNow);
        return signal;
    }

    private double score(PreferenceSignal signal, Instant now) {
        long ageDays = Math.max(0, Duration.between(signal.observedAt(), now).toDays());
        double recency = Math.exp(-ageDays / 180.0);
        return Math.max(0, Math.min(1, signal.confidence())) * recency;
    }

    private void audit(String userId, String memoryId, String action, String reason, Instant now) {
        store.saveAudit(new MemoryAudit(
                "audit_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12),
                userId,
                memoryId,
                action,
                null,
                null,
                reason,
                now
        ));
    }

    static String hash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder();
            for (byte item : digest) {
                result.append(String.format("%02x", item));
            }
            return result.toString();
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 不可用", exception);
        }
    }
}
