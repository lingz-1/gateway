package com.lingshu.core.billing;

public class BudgetExceededException extends RuntimeException {

    public BudgetExceededException(String tenantId) {
        super("Budget exhausted for tenant: " + tenantId);
    }
}
