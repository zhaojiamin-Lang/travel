package com.travelguide.intelligence;

import com.travelguide.intelligence.PlanDiffModels.PlanDiffView;
import com.travelguide.intelligence.PlanDiffModels.RollbackResult;
import com.travelguide.service.PlanningService;
import com.travelguide.web.ApiDtos.PlanView;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 通过现有版本服务执行行程回滚。
 *
 * <p>回滚会创建一个新的最新版本，不会覆盖历史版本；
 * 如果所选版本已经与最新内容一致，则不会重复创建版本。</p>
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
     * 回滚不会修改历史。只要目标版本与最新版本语义不同，
     * 就会复制目标内容并生成新的最新版本。
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
