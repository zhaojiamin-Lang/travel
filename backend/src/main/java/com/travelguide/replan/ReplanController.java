package com.travelguide.replan;

import com.travelguide.replan.ReplanModels.ClosureEventRequest;
import com.travelguide.replan.ReplanModels.DelayEventRequest;
import com.travelguide.replan.ReplanModels.ReplanProposalView;
import com.travelguide.service.AccessControlService;
import com.travelguide.web.ApiDtos.PlanView;
import jakarta.validation.Valid;
import jakarta.servlet.http.HttpSession;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/replan")
public class ReplanController {

    private final ReplanEventFacade facade;
    private final AccessControlService accessControl;

    public ReplanController(
            ReplanEventFacade facade,
            AccessControlService accessControl
    ) {
        this.facade = facade;
        this.accessControl = accessControl;
    }

    @PostMapping("/events/closure")
    public ReplanProposalView closure(
            @Valid @RequestBody ClosureEventRequest request,
            HttpSession session
    ) {
        accessControl.requirePlan(session, request.planId());
        return facade.reportClosure(request);
    }

    @PostMapping("/events/delay")
    public ReplanProposalView delay(
            @Valid @RequestBody DelayEventRequest request,
            HttpSession session
    ) {
        accessControl.requirePlan(session, request.planId());
        return facade.reportDelay(request);
    }

    @GetMapping("/proposals/{proposalId}")
    public ReplanProposalView get(
            @PathVariable String proposalId,
            HttpSession session
    ) {
        ReplanProposalView proposal = facade.get(proposalId);
        accessControl.requirePlan(session, proposal.planId());
        return proposal;
    }

    @PostMapping("/proposals/{proposalId}/accept")
    public PlanView accept(
            @PathVariable String proposalId,
            HttpSession session
    ) {
        ReplanProposalView proposal = facade.get(proposalId);
        accessControl.requirePlan(session, proposal.planId());
        return facade.accept(proposalId);
    }
}
