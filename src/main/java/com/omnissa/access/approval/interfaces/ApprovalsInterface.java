package com.omnissa.access.approval.interfaces;

import com.omnissa.access.approval.model.CalloutRequest;
import com.omnissa.access.approval.model.CalloutResponse;

public interface ApprovalsInterface {

    CalloutRequest[] getPendingApprovals();
    void requestResponse(CalloutResponse response);
    void deleteRemoteCallouts();
}
