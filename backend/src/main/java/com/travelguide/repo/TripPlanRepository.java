package com.travelguide.repo;

import com.travelguide.domain.TripPlanEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TripPlanRepository extends JpaRepository<TripPlanEntity, String> {
    List<TripPlanEntity> findByRequestIdOrderByVersionDesc(String requestId);
}
