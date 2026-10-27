package com.travelguide.replan;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ReplanProposalRepository extends JpaRepository<ReplanProposalEntity, String> {
    Optional<ReplanProposalEntity> findByEventId(String eventId);
}
