package com.travelguide.repo;

import com.travelguide.domain.GuideSessionEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GuideSessionRepository extends JpaRepository<GuideSessionEntity, String> {
}
