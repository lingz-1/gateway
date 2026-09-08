package com.lingshu.core.billing;

import java.util.List;

public interface BudgetOutboxStore {

    List<BudgetOutboxEvent> claimUnpublished(int limit);

    boolean markPublished(BudgetOutboxEvent event);
}
