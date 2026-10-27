package com.travelguide.replan;

import com.travelguide.replan.ReplanModels.ClosureEventRequest;
import com.travelguide.replan.ReplanModels.DelayEventRequest;
import com.travelguide.replan.ReplanModels.ReplanProposalView;
import com.travelguide.web.ApiDtos.PlanView;
import jakarta.validation.Valid;
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

    public ReplanController(ReplanEventFacade facade) {
        this.facade = facade;
    }

    @PostMapping("/events/closure")
    public ReplanProposalView closure(@Valid @RequestBody ClosureEventRequest request) {
        return facade.reportClosure(request);
    }

    @PostMapping("/events/delay")
    public ReplanProposalView delay(@Valid @RequestBody DelayEventRequest request) {
        return facade.reportDelay(request);
    }

    @GetMapping("/proposals/{proposalId}")
    public ReplanProposalView get(@PathVariable String proposalId) {
        return facade.get(proposalId);
    }

    @PostMapping("/proposals/{proposalId}/accept")
    public PlanView accept(@PathVariable String proposalId) {
        return facade.accept(proposalId);
    }
}
