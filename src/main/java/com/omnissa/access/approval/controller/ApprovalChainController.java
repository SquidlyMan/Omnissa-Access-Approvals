package com.omnissa.access.approval.controller;

import com.omnissa.access.approval.model.ApprovalChain;
import com.omnissa.access.approval.model.ApprovalStage;
import com.omnissa.access.approval.repository.ApprovalChainRepository;
import com.omnissa.access.approval.repository.ApprovalStageRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Collections;
import java.util.List;

/**
 * CRUD API for approval chains (#53). Authenticated by default — intentionally
 * NOT in the security permitAll list, mirroring {@link RulesController}.
 *
 * <p>API-only in this slice: no admin UI page exists yet for authoring a
 * chain. See #53's handoff brief for why that's a deliberate, separate piece
 * of work rather than an oversight.
 */
@RestController
@RequestMapping("/api/chains")
public class ApprovalChainController {

    @Autowired
    ApprovalChainRepository chainRepository;

    @Autowired
    ApprovalStageRepository stageRepository;

    @GetMapping
    public ResponseEntity<List<ApprovalChain>> listChains() {
        return ResponseEntity.ok(chainRepository.findAll());
    }

    @PostMapping
    public ResponseEntity<?> createChain(@RequestBody ApprovalChain chain) {
        String error = validate(chain);
        if (error != null) {
            return ResponseEntity.badRequest().body(Collections.singletonMap("error", error));
        }
        chain.setId(null);
        return ResponseEntity.ok(chainRepository.save(chain));
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> updateChain(@PathVariable Long id, @RequestBody ApprovalChain chain) {
        if (!chainRepository.existsById(id)) {
            return ResponseEntity.notFound().build();
        }
        String error = validate(chain);
        if (error != null) {
            return ResponseEntity.badRequest().body(Collections.singletonMap("error", error));
        }
        chain.setId(id);
        return ResponseEntity.ok(chainRepository.save(chain));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> deleteChain(@PathVariable Long id) {
        if (!chainRepository.existsById(id)) {
            return ResponseEntity.notFound().build();
        }
        // Requests already routed to this chain keep their chainId/currentStage —
        // deleting the definition does not retroactively change a request in
        // flight; it only stops NEW requests from being routed to it.
        stageRepository.deleteByChainId(id);
        chainRepository.deleteById(id);
        return ResponseEntity.ok(null);
    }

    @GetMapping("/{id}/stages")
    public ResponseEntity<?> listStages(@PathVariable Long id) {
        if (!chainRepository.existsById(id)) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(stageRepository.findByChainIdOrderByStageOrderAsc(id));
    }

    /**
     * Replaces the full ordered stage list for a chain in one call — stage
     * order is assigned from array position (1-based), not trusted from the
     * client, so there is no way to submit duplicate/gapped ordering.
     */
    @PutMapping("/{id}/stages")
    public ResponseEntity<?> replaceStages(@PathVariable Long id, @RequestBody List<ApprovalStage> stages) {
        if (!chainRepository.existsById(id)) {
            return ResponseEntity.notFound().build();
        }
        if (stages == null || stages.isEmpty()) {
            return ResponseEntity.badRequest().body(Collections.singletonMap("error",
                    "A chain needs at least one stage — an empty chain is never routed to (see matchChain)."));
        }
        for (ApprovalStage stage : stages) {
            String error = validateStage(stage);
            if (error != null) {
                return ResponseEntity.badRequest().body(Collections.singletonMap("error", error));
            }
        }
        stageRepository.deleteByChainId(id);
        int order = 1;
        for (ApprovalStage stage : stages) {
            stage.setId(null);
            stage.setChainId(id);
            stage.setStageOrder(order++);
            stageRepository.save(stage);
        }
        return ResponseEntity.ok(stageRepository.findByChainIdOrderByStageOrderAsc(id));
    }

    private String validate(ApprovalChain chain) {
        if (chain.getName() == null || chain.getName().isBlank()) {
            return "name must not be blank";
        }
        boolean hasCriteria = notBlank(chain.getAppPattern()) || notBlank(chain.getGroupName());
        if (!hasCriteria) {
            return "chain must set at least one of appPattern/groupName — a chain with neither "
                    + "matches nothing (same as an empty MATCH auto-rule), so it would never route anything";
        }
        return null;
    }

    private String validateStage(ApprovalStage stage) {
        if (stage.getApproverType() == null
                || !(stage.getApproverType().equalsIgnoreCase("ROLE")
                     || stage.getApproverType().equalsIgnoreCase("GROUP"))) {
            return "approverType must be \"ROLE\" or \"GROUP\"";
        }
        if (stage.getApproverValue() == null || stage.getApproverValue().isBlank()) {
            return "approverValue must not be blank";
        }
        return null;
    }

    private boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }
}
