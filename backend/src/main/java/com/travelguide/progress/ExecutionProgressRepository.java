package com.travelguide.progress;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ExecutionProgressRepository extends JpaRepository<ExecutionProgressEntity, String> {
    Optional<ExecutionProgressEntity> findByPlanId(String planId);
}
