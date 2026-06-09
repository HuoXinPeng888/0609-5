package com.shop.order.service;

import java.util.*;

/**
 * 订单状态机 — 定义合法的状态转换
 *
 * CREATED → PAYING (发起支付)
 * PAYING → PAID (支付成功)
 * PAYING → CANCELLED (用户取消 / 超时取消)
 * PAID → SHIPPED (发货)
 * SHIPPED → COMPLETED (确认收货)
 */
public class OrderStateMachine {

    public static final String CREATED = "CREATED";
    public static final String PAYING = "PAYING";
    public static final String PAID = "PAID";
    public static final String SHIPPED = "SHIPPED";
    public static final String COMPLETED = "COMPLETED";
    public static final String CANCELLED = "CANCELLED";

    private static final Map<String, Set<String>> TRANSITIONS = new HashMap<>();

    static {
        TRANSITIONS.put(CREATED, new HashSet<>(Arrays.asList(PAYING, CANCELLED)));
        TRANSITIONS.put(PAYING, new HashSet<>(Arrays.asList(PAID, CANCELLED)));
        TRANSITIONS.put(PAID, new HashSet<>(Arrays.asList(SHIPPED)));
        TRANSITIONS.put(SHIPPED, new HashSet<>(Arrays.asList(COMPLETED)));
        TRANSITIONS.put(CANCELLED, Collections.emptySet());
        TRANSITIONS.put(COMPLETED, Collections.emptySet());
    }

    public static boolean canTransit(String fromStatus, String toStatus) {
        Set<String> allowed = TRANSITIONS.get(fromStatus);
        return allowed != null && allowed.contains(toStatus);
    }

    public static boolean isTerminalState(String status) {
        Set<String> allowed = TRANSITIONS.get(status);
        return allowed != null && allowed.isEmpty();
    }

    /**
     * PAYING 状态超时时间（秒），通过配置可覆盖，默认30分钟
     */
    public static final int DEFAULT_PAY_TIMEOUT_SECONDS = 1800;
}
