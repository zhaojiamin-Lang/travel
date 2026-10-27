package com.travelguide.replan;

import org.springframework.data.jpa.repository.JpaRepository;

public interface TravelEventRepository extends JpaRepository<TravelEventEntity, String> {
}
