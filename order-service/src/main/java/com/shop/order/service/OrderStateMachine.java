package com.shop.order.service;

import java.util.*;

/**
 * 订单状态机 — 定义合法的状态转换
 * 
 * 当前支持的状态转换：
 * CREATED → PAYING (发起支付)
 * PAYING → PAID (支付成功)
 * PAYING → CANCELLED (用户取消)
 * PAID → SHIPPED (发货)
 * SHIPPED → COMPLETED (确认收货)
 * 
 * 注意：缺少 PAYING → CANCELLED 的超时转换！
 * PAYING 状态没有超时机制，会永远卡住
 */
public class OrderStateMachine {

    // 状态常量
    public static final String CREATED = "CREATED";
    public static final String PAYING = "PAYING";
    public static final String PAID = "PAID";
    public static final String SHIPPED = "SHIPPED";
    public static final String COMPLETED = "COMPLETED";
    public static final String CANCELLED = "CANCELLED";

    // 合法的状态转换映射
    private static final Map<String, Set<String>> TRANSITIONS = new HashMap<>();

    static {
        TRANSITIONS.put(CREATED, new HashSet<>(Arrays.asList(PAYING, CANCELLED)));
        TRANSITIONS.put(PAYING, new HashSet<>(Arrays.asList(PAID, CANCELLED)));
        // 注意：虽然 PAYING → CANCELLED 在转换表中存在，
        // 但缺少自动触发这个转换的超时机制
        TRANSITIONS.put(PAID, new HashSet<>(Arrays.asList(SHIPPED)));
        TRANSITIONS.put(SHIPPED, new HashSet<>(Arrays.asList(COMPLETED)));
        // CANCELLED 和 COMPLETED 是终态
        TRANSITIONS.put(CANCELLED, Collections.emptySet());
        TRANSITIONS.put(COMPLETED, Collections.emptySet());
    }

    /**
     * 检查状态转换是否合法
     */
    public static boolean canTransit(String fromStatus, String toStatus) {
        Set<String> allowed = TRANSITIONS.get(fromStatus);
        return allowed != null && allowed.contains(toStatus);
    }

    /**
     * 检查是否为终态
     */
    public static boolean isTerminalState(String status) {
        Set<String> allowed = TRANSITIONS.get(status);
        return allowed != null && allowed.isEmpty();
    }

    // 缺少：getPayTimeoutSeconds() 配置
    // 缺少：超时自动取消的定时任务
}
