package impl;

import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Setter
@Getter
public class Orders {
    private Fences fences;
    private List<Order> orderList;
    private int orderNumber;
    private double totalDeliverPrice;
    private double totalDispatchNum;
    private double totalCarrierCost;
    private double totalDistance;
    private int totalFenceNum;
    private double time;
    private List<Fence> visitedFences;
    private Set<Fence> visitedFenceSet; // 用于去重的Set（避免重复添加同一围栏）

    public Orders(List<Order> orderList) {
        this.orderList = orderList;
        orderNumber = 0;
        totalCarrierCost = 0.0;
        totalDistance = 0.0;
        totalFenceNum = 0;
        totalDispatchNum = 0.0;
        totalDeliverPrice = 0.0;
        calOrderDetail();
    }

    public void calOrderDetail() {
        if (orderList == null) {
            return;
        }
        for (Order order : orderList) {
            totalDeliverPrice += order.getOriginalPrice();
            totalCarrierCost += order.getCarrierCost();
            totalDistance += order.getDistance();
            totalFenceNum += order.getFenceNumber();
            totalDispatchNum += order.getDispatchNum();
        }
        orderNumber = orderList.size();
    }

    /**
     * 生成所有订单关联的唯一围栏列表（核心修复：收集所有订单的围栏，去重）
     */
    public void generateFenceList() {
        // 1. 入参/前置校验：订单列表为空直接返回
        if (orderList == null || orderList.isEmpty()) {
            System.err.println("订单列表为空，无法生成围栏列表");
            return;
        }
        if (fences == null) {
            System.err.println("围栏容器未初始化，无法生成围栏列表");
            return;
        }

        // 2. 初始化容器：Set用于去重，List用于最终返回
        visitedFenceSet = new HashSet<>();
        visitedFences = new ArrayList<>();

        // 3. 遍历所有订单，收集关联的围栏（去重）
        for (Order order : orderList) {
            // 跳过空订单
            if (order == null) {
                continue;
            }
            // 获取当前订单的围栏编号列表
            ArrayList<Integer> fenceIndexList = order.getFenceList();
            // 跳过空的围栏编号列表
            if (fenceIndexList == null || fenceIndexList.isEmpty()) {
                continue;
            }

            // 4. 通过编号获取对应的围栏对象列表
            List<Fence> orderRelatedFences = fences.getFencesByIndexList(fenceIndexList);
            // 跳过无效的围栏列表
            if (orderRelatedFences == null || orderRelatedFences.isEmpty()) {
                continue;
            }

            // 5. 核心：将当前订单的围栏添加到Set中（自动去重）
            visitedFenceSet.addAll(orderRelatedFences);
        }

        // 6. 将去重后的Set转为List（便于后续使用）
        visitedFences.addAll(visitedFenceSet);
    }
}
