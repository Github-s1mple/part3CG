package Utils;

import impl.Carrier;
import impl.Fence;
import impl.Order;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class PriceCalculator {
    public static double calculateRC(Order order, HashMap<String, Double> dualsOfRLMP) {
        if (order == null) {
            throw new IllegalArgumentException("Order cannot be null");
        }

        // 2. 获取路径的净收益（price）
        double price = order.getPrice();

        // 3. 获取路径覆盖的客户对偶变量之和（dualValue）
        double sumFenceDualValues = 0.0;
        // 遍历每个围栏的装载量，累加总对偶值
        for (Map.Entry<Integer, Double> entry : order.getLoads().entrySet()) {
            Integer fenceId = entry.getKey();       // 围栏编号
            double load = entry.getValue();     // 该围栏的装载量

            // 校验装载量有效性（忽略负数装载量，避免异常值影响）
            if (load < 0) {
                System.out.println("警告：围栏" + fenceId + "的装载量为负数（" + load + "），已忽略");
                continue;
            }

            // 获取围栏对象，检查是否存在
            Fence fence = order.getFences().getFence(fenceId);
            if (fence == null) {
                System.out.println("警告：未找到围栏编号" + fenceId + "的信息，已忽略");
                continue;
            }

            sumFenceDualValues += load * dualsOfRLMP.get(fence.getConstName());
        }

        // 4. 获取车辆约束的对偶变量σ
        double sigma = order.getCarrier().getCarrierValue();

        // 5. 计算RC
        double dualObj = price - (sumFenceDualValues + sigma * order.getDispatchNum()) - order.getCarrierCost();

        return dualObj;
    }

    public static double calculatePrimalObj(Order order){
        double totalValue = 0.0;

        // 遍历每个围栏的装载量，累加总价值
        for (Map.Entry<Integer, Double> entry : order.getLoads().entrySet()) {
            int fenceId = entry.getKey();       // 围栏编号
            double load = entry.getValue();     // 该围栏的装载量

            // 校验装载量有效性（忽略负数装载量，避免异常值影响）
            if (load < 0) {
                System.out.println("警告：围栏" + fenceId + "的装载量为负数（" + load + "），已忽略");
                continue;
            }

            // 获取围栏对象，检查是否存在
            Fence fence = order.getFences().getFence(fenceId);
            if (fence == null) {
                System.out.println("警告：未找到围栏编号" + fenceId + "的信息，已忽略");
                continue;
            }

            // 累加当前围栏的价值（装载量 × 单位价值）
            totalValue += load * fence.getFenceValue();
        }

        return totalValue - order.getCarrierCost();
    }

    public static double calculateDualObj(HashMap<Integer, Double> loads, Carrier carrier, Double dispatchNum, List<Integer> fenceIndexList) {
        return 1.0;
    }
}
