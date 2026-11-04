package com.travelguide.orchestration;

import com.travelguide.orchestration.OrchestrationModels.RoutingContext;
import com.travelguide.orchestration.OrchestrationModels.RoutingDecision;

/**
 * 意图路由端口。
 *
 * <p>编排器不绑定具体模型，后续替换本地小模型或云模型时只需要提供新的实现。</p>
 */
public interface IntentRouter {

    RoutingDecision route(RoutingContext context);
}
