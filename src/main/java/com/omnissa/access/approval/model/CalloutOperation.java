package com.omnissa.access.approval.model;

public enum CalloutOperation implements NumericalEnum {

    activation(0),
    deactivation(1);

    final private int id;

    CalloutOperation(int id) {
        this.id = id;
    }

    public int getIntValue() {
        return id;
    }
}
