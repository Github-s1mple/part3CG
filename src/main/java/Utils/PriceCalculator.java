package Utils;

import impl.Carrier;
import impl.Order;
import baseinfo.Constants;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

public class PriceCalculator {
    public static double calculateDualObj(Order order) {
        if (order == null) {
            throw new IllegalArgumentException("Order cannot be null");
        }

        // 2. 获取路径的净收益（price）
        double price = order.getPrice();

        // 3. 获取路径覆盖的客户对偶变量之和（dualValue）
        double sumDualValues = order.getDualValue();

        // 4. 获取车辆约束的对偶变量σ（根据业务场景，此处假设从Carrier中获取，或可改为固定参数）
        double sigma = order.getCarrier().getCarrierValue();

        // 5. 计算缩减收益：dualObj = price - (sumDualValues + sigma)
        double dualObj = price - (sumDualValues + sigma);

        return dualObj;
    }

    public void calculateDeliverPrice(Order order){
        double distance = order.getDistance();
        order.setPrice(distance * Constants.DELIVERCOSTPERMETER);
    }

    public static double calculatePrimalObj(Order order){
        return 1.0;
    }

    public static double calculateDualObj(HashMap<Integer, Double> loads, Carrier carrier, Double dispatchNum, List<Integer> fenceIndexList) {
        return 1.0;
    }
}
