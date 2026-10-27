package com.travelguide.repo;

import com.travelguide.domain.TripRequestEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TripRequestRepository extends JpaRepository<TripRequestEntity, String> {
    List<TripRequestEntity> findByOwnerIdOrderByCreatedAtDesc(String ownerId);
}
