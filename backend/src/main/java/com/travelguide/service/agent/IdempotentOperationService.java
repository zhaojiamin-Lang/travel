package com.travelguide.service.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.function.Supplier;

/**
 * 将业务写入与幂等结果放在同一数据库事务中提交。
 */
@Service
public class IdempotentOperationService {
    private final IdempotentOperationRepository repository;
    private final ObjectMapper objectMapper;

    public IdempotentOperationService(
            IdempotentOperationRepository repository,
            ObjectMapper objectMapper
    ) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public <T> T execute(
            String operationKey,
            Class<T> resultType,
            Supplier<T> action
    ) {
        var existing = repository.findById(operationKey);
        if (existing.isPresent()) {
            return read(existing.get(), resultType);
        }

        T result = action.get();
        IdempotentOperationEntity entity = new IdempotentOperationEntity();
        entity.setOperationKey(operationKey);
        entity.setResultType(resultType.getName());
        entity.setResultJson(write(result));
        entity.setCreatedAt(Instant.now());
        repository.saveAndFlush(entity);
        return result;
    }

    private <T> T read(IdempotentOperationEntity entity, Class<T> resultType) {
        if (!resultType.getName().equals(entity.getResultType())) {
            throw new IllegalStateException("幂等键对应了不同类型的业务结果");
        }
        try {
            return objectMapper.readValue(entity.getResultJson(), resultType);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("幂等业务结果解析失败", exception);
        }
    }

    private String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("幂等业务结果序列化失败", exception);
        }
    }
}
