package algo;

import impl.*;
import lombok.Getter;
import lombok.Setter;

import java.util.HashMap;
import java.util.List;
import java.util.Objects;

import static java.lang.Math.min;
@Setter
@Getter
public class LoadingAlgorithm {
    // 通用
    private final Fences fences;
    private final Carriers carriers;
    private int orderCnt;
    private Carrier chosenCarrier;
    private double bestDispatchNum;

    public LoadingAlgorithm(BidLabeling bidLabeling) {
        this.fences = bidLabeling.getFences();
        this.carriers = bidLabeling.getCarriers();
        this.orderCnt = 0;
    }

    public Order solve(Route route) {
        // 计算最优调度量
        bestDispatchNum = route.getMaxDispatchNum();
        HashMap<Integer, Double> loads = new HashMap<>();
        generateNumWithType(route.getFenceList(), bestDispatchNum, loads);

        // 选择满足约束的载具
        chosenCarrier = null;
        chooseCarrier(route);

        if (chosenCarrier == null) {
            return null;
        } else {
            orderCnt = orderCnt + 1;
            return new Order(route, loads, chosenCarrier, orderCnt);
        }
    }

    private void generateNumWithType(List<Integer> indexes, double dispatchNum, HashMap<Integer, Double> loads) {
        double loadCnt = dispatchNum;
        for (Integer fenceIndex : indexes) {
            Fence fence = fences.getFence(fenceIndex);
            double actualDispatchNum = min(fence.getMinDispatchNum(), fence.getDeliverDemand());
            loads.put(fenceIndex, actualDispatchNum);
            loadCnt -= actualDispatchNum;
        }
        for (Integer i : indexes) {
            double lack = fences.getFence(i).getDeliverDemand() - loads.get(i);
            if (lack >= loadCnt) {
                loads.put(i, loads.get(i) + loadCnt);
                break;
            }
            loads.put(i, loads.get(i) + lack);
            loadCnt -= lack;
        }
    }

    private void chooseCarrier(Route route) {
        Integer depotIndex = route.getDepot();
        for (Carrier carrier : carriers.getCarrierList()) {
            if (Objects.equals(carrier.getDepot(), -depotIndex)){
                chosenCarrier = carrier;
                return;
            }
        }
    }
}