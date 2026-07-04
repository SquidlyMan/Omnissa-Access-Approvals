package com.omnissa.access.approval.interfaces;

import com.omnissa.access.approval.model.CalloutRequest;
import com.omnissa.access.approval.model.CalloutResponse;
import com.omnissa.access.approval.model.DecisionOutcome;

public interface ApprovalsInterface {

    CalloutRequest[] getPendingApprovals();
    DecisionOutcome requestResponse(CalloutResponse response);
    void deleteRemoteCallouts();
}
