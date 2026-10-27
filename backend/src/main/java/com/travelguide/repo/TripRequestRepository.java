package com.travelguide.repo;

import com.travelguide.domain.TripRequestEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TripRequestRepository extends JpaRepository<TripRequestEntity, String> {
}
