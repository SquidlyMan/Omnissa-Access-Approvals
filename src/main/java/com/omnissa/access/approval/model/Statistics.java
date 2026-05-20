package com.omnissa.access.approval.model;

import java.util.HashMap;
import java.util.List;

public class Statistics {

    private HashMap<String, Integer> approvalStates;
    private List<AppRequests> requestPerApp;

    public Statistics(HashMap<String, Integer> approvalStates, List<AppRequests> requestPerApp) {
        this.approvalStates = approvalStates;
        this.requestPerApp = requestPerApp;
    }

    public Statistics() {}

    public HashMap<String, Integer> getApprovalStates() { return approvalStates; }
    public void setApprovalStates(HashMap<String, Integer> approvalStates) { this.approvalStates = approvalStates; }

    public List<AppRequests> getRequestPerApp() { return requestPerApp; }
    public void setRequestPerApp(List<AppRequests> requestPerApp) { this.requestPerApp = requestPerApp; }
}
