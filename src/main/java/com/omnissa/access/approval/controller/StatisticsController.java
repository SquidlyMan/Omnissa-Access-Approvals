package com.omnissa.access.approval.controller;

import com.omnissa.access.approval.model.AppRequests;
import com.omnissa.access.approval.model.Mappings;
import com.omnissa.access.approval.model.State;
import com.omnissa.access.approval.model.Statistics;
import com.omnissa.access.approval.repository.ApprovalsRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

@RestController
@RequestMapping(value = {Mappings.STATISTICS, "/stats"})
public class StatisticsController {

    @Autowired
    ApprovalsRepository repository;

    @GetMapping("/approvals")
    public ResponseEntity<?> approvalStats() {
        HashMap<String, Integer> approvalStates = new HashMap<>();
        for (State state : State.values()) {
            approvalStates.put(state.name().toLowerCase(), repository.countByState(state.name().toLowerCase()));
        }

        List<AppRequests> appReqs = new ArrayList<>();
        for (String resource : repository.findDistinctResourceNames()) {
            AppRequests appRequests = new AppRequests();
            appRequests.setName(resource);
            appRequests.setValue(repository.countByResourceName(resource));
            appReqs.add(appRequests);
        }

        return ResponseEntity.ok(new Statistics(approvalStates, appReqs));
    }
}
