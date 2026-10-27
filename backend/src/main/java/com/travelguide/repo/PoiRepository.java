package com.travelguide.repo;

import com.travelguide.domain.PoiEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PoiRepository extends JpaRepository<PoiEntity, String> {
    List<PoiEntity> findAllByOrderByAreaAscNameAsc();
}
