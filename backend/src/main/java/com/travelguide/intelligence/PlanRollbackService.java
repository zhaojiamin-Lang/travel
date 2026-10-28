package com.travelguide.intelligence;

import com.travelguide.intelligence.PlanDiffModels.PlanDiffView;
import com.travelguide.intelligence.PlanDiffModels.RollbackResult;
import com.travelguide.service.PlanningService;
import com.travelguide.web.ApiDtos.PlanView;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Applies rollback through the existing version service.
 *
 * <p>A rollback creates a new latest version instead of mutating history. If
 * the selected version already matches the latest content, no duplicate
 * version is created.</p>
 */
@Service
public class PlanRollbackService {

    private final PlanningService planningService;
    private final PlanDiffService diffService;

    public PlanRollbackService(PlanningService planningService, PlanDiffService diffService) {
        this.planningService = planningService;
        this.diffService = diffService;
    }

    /**
     * Rollback never mutates history. It creates a new latest version unless the
     * selected version is already semantically identical to the latest one.
     */
    @Transactional
    public RollbackResult rollback(String targetPlanId) {
        PlanView target = planningService.getPlan(targetPlanId);
        List<PlanView> versions = planningService.listVersions(target.requestId());
        PlanView latest = versions.get(0);
        PlanDiffView latestToTarget = diffService.diff(latest, target);
        if (latestToTarget.identical()) {
            return new RollbackResult(false, latest, latestToTarget);
        }

        PlanView restored = planningService.restoreVersion(targetPlanId);
        PlanDiffView applied = diffService.diff(latest, restored);
        return new RollbackResult(true, restored, applied);
    }
}
