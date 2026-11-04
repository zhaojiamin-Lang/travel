package com.travelguide.service.agent;

import org.springframework.data.jpa.repository.JpaRepository;

public interface IdempotentOperationRepository
        extends JpaRepository<IdempotentOperationEntity, String> {
}
