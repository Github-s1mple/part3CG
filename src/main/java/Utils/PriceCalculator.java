package Utils;

import impl.Carrier;
import impl.Fence;
import impl.Fences;
import impl.Order;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class PriceCalculator {
    /**
     * 计算路径检验数（RC）：新增距离约束的对偶值影响
     * RC = 路径原始收益 - （围栏对偶值影响 + 载具资源对偶值影响 + 载具距离对偶值影响）
     */
    public static double calculateRC(Order order, HashMap<String, Double> dualsOfRLMP) {
        if (order == null) {
            throw new IllegalArgumentException("Order cannot be null");
        }

        Carrier carrier = order.getCarrier();
        if (carrier == null) {
            throw new IllegalArgumentException("Order must be bound to a carrier");
        }

        // 1. 路径原始收益
        double price = order.getDualPrice();

        // 2. 围栏需求约束的对偶值影响：sum(装载量 × 围栏对偶值)
        double sumFenceDual = 0.0;
        Fences fences = order.getFences(); // 获取围栏集合
        for (Map.Entry<Integer, Double> entry : order.getLoads().entrySet()) {
            Integer fenceId = entry.getKey();
            double load = entry.getValue();
            if (load < 0) {
                System.out.println("警告：围栏" + fenceId + "的装载量为负数（" + load + "），已忽略");
                continue;
            }

            Fence fence = fences.getFence(fenceId);
            if (fence == null) {
                System.out.println("警告：未找到围栏编号" + fenceId + "的信息，已忽略");
                continue;
            }

            sumFenceDual += load * dualsOfRLMP.getOrDefault(fence.getConstName(), 0.0);
        }

        // 3. 载具使用次数的对偶值影响：使用次数 × 资源对偶值
        double carrierResourceDual = 1.0 * dualsOfRLMP.getOrDefault(carrier.getConstName(), 0.0);

        // 4. 计算RC：原始收益 - 所有约束的边际成本总和
        return price - (sumFenceDual + carrierResourceDual);
    }

    public static double calculatePrimalObj(Order order){
        double totalValue = 0.0;

        // 遍历每个围栏的装载量，累加总价值
        for (Map.Entry<Integer, Double> entry : order.getLoads().entrySet()) {
            int fenceId = entry.getKey();       // 围栏编号
            double load = entry.getValue();     // 该围栏的装载量
            Fence fence = order.getFences().getFence(fenceId);

            // 累加当前围栏的价值（装载量 × 单位价值）
            totalValue += load * fence.getOriginalFenceValue();
        }

        return totalValue - order.getCarrierCost();
    }

    public static double calculateDualObj(Order order){
        double totalValue = 0.0;

        // 遍历每个围栏的装载量，累加总价值
        for (Map.Entry<Integer, Double> entry : order.getLoads().entrySet()) {
            int fenceId = entry.getKey();       // 围栏编号
            double load = entry.getValue();     // 该围栏的装载量
            Fence fence = order.getFences().getFence(fenceId);

            // 累加当前围栏的价值（装载量 × 单位价值）
            totalValue += load * fence.getFenceValue();
        }

        return totalValue - order.getCarrierCost();
    }

    public static double calculateIndividualSimilarity(Map<Integer, Integer> ind1, Map<Integer, Integer> ind2) {
        Set<Integer> keys1 = ind1.keySet();
        Set<Integer> keys2 = ind2.keySet();

        // 计算交集大小
        Set<Integer> intersection = new HashSet<>(keys1);
        intersection.retainAll(keys2);
        int interSize = intersection.size();

        // 计算并集大小
        Set<Integer> union = new HashSet<>(keys1);
        union.addAll(keys2);
        int unionSize = union.size();

        // 避免除0（两个空个体相似度为1）
        return unionSize == 0 ? 1.0 : (double) interSize / unionSize;
    }

    public static double calculatePopulationAvgFitness(Map<Map<Integer, Integer>, Double> fitnessMap) {
        if (fitnessMap.isEmpty()) return 0.0;
        double totalFitness = fitnessMap.values().stream().mapToDouble(Double::doubleValue).sum();
        return totalFitness / fitnessMap.size();
    }
}
