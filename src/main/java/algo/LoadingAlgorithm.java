package algo;

import Utils.ConstraintsManager;
import Utils.PriceCalculator;
import impl.*;
import baseinfo.Constants;
import lombok.Getter;
import lombok.Setter;

import java.util.HashMap;
import java.util.List;
import java.util.stream.Collectors;

import static java.lang.Math.min;
@Setter
@Getter
public class LoadingAlgorithm {
    // 通用
    private final Fences fences;
    private final Carriers carriers;
    private int orderCnt;
    //每次求解重新赋值
    private double minConsDist1; // 载重约束距离
    private double minConsDist2; // 满载率约束距离
    private Carrier minConstDist1_carrier;
    private Carrier minConstDist2_carrier;
    //    private double bestValue;
    private HashMap<Integer, Double> bestLoads;
    private Route route;
    private List<Integer> fenceIndexList;
    private double bestDispatchNum;
    private HashMap<Integer, Double> bestDispatchNumLoads;
    private Carrier orderCarrier;

    private static class ConsDist {
        //装载量是否小于容量，0为满足容量约束，正数为超过容量的调度量
        double consDist1;
        //装载量是否小于满载率，0为满足满载率，正数为达到满载率还需的调度量
        double consDist2;

        ConsDist(double bestDispatchNum, Carrier carrier) {
            consDist1 = Math.max(0.0, bestDispatchNum - carrier.getCapacity());
            consDist2 = Math.max(0.0, carrier.getMinRatioCapacity() - bestDispatchNum);
        }
    }

    public LoadingAlgorithm(BidLabeling bidLabeling) {
        this.fences = bidLabeling.getFences();
        this.carriers = bidLabeling.getCarriers();
        this.orderCnt = 0;
    }

    public Order solve(Route rRoute) {
        // 获取基本信息

        route = rRoute;
        fenceIndexList = route.getFenceList();
        // 计算最优调度量
        bestDispatchNum = route.getMaxDispatchNum();
        bestDispatchNumLoads = dispatchNum2loads(route.getMaxDispatchNum());

        // 选择满足约束的载具
        minConsDist1 = Integer.MAX_VALUE; // 载重约束距离
        minConsDist2 = Integer.MAX_VALUE; // 满载率约束距离
        minConstDist1_carrier = null;
        minConstDist2_carrier = null;

//        bestValue = 0.0;
        bestLoads = new HashMap<>();
        generateConsDist();
        if (minConstDist1_carrier == null && minConstDist2_carrier == null) {
            return null;
        }
        // 根据调度量分配装卸量
        if (minConsDist1 == 0 && minConsDist2 == 0) {
            bestLoads = bestDispatchNumLoads;
            orderCarrier = minConstDist1_carrier;
        } else if (minConstDist1_carrier == null) {
            bestLoads = this.dispatchNum2loads(minConstDist2_carrier.getMinRatioCapacity());

            orderCarrier = minConstDist2_carrier;
        } else if (minConstDist2_carrier == null) {
            bestLoads = this.dispatchNum2loads(minConstDist1_carrier.getCapacity());

            orderCarrier = minConstDist1_carrier;
        } else {
            chooseBetterCarrierFromConstDist();
        }

        if (orderCarrier == null) {
            return null;
        } else {
            orderCnt = orderCnt + 1;
            return new Order(route, bestLoads, orderCarrier, orderCnt);
        }
    }

    private HashMap<Integer, Double> dispatchNum2loads(double dispatchNum) {
        HashMap<Integer, Double> loads = new HashMap<>();
        generateNumWithType(fenceIndexList, dispatchNum, loads);
        return loads;
    }

    private void generateNumWithType(List<Integer> indexes, double dispatchNum, HashMap<Integer, Double> loads) {
        double loadCnt = dispatchNum;
        for (int fenceIndex : indexes) {
            Fence fence = fences.getFence(fenceIndex);
            double actualDispatchNum = min(fence.getMinDispatchNum(), fence.getDeliverDemand());
            loads.put(fenceIndex, actualDispatchNum);
            loadCnt -= actualDispatchNum;
        }
        for (int i : indexes) {
            double lack = fences.getFence(i).getDeliverDemand() - loads.get(i);
            if (lack >= loadCnt) {
                loads.put(i, loads.get(i) + loadCnt);
                break;
            }
            loads.put(i, loads.get(i) + lack);
            loadCnt -= lack;
        }
    }

    private void generateConsDist() {
        for (Carrier carrier : carriers.getCarrierList()) {
            if (ConstraintsManager.isCarrierFeasible(carrier, route)) {
                if (carrier.getCapacity() >= bestDispatchNum && carrier.getMinRatioCapacity() <= bestDispatchNum) {
                    setConstraint1(0, carrier);
                    setConstraint2(0, carrier);
                    break;
                } else {
                    ConsDist constDist = new ConsDist(bestDispatchNum, carrier);
                    if (constDist.consDist2 == 0 && constDist.consDist1 < minConsDist1) {
                        setConstraint1(constDist.consDist1, carrier);
                    } else if (constDist.consDist1 == 0 && constDist.consDist2 < minConsDist2) {
                        setConstraint2(constDist.consDist2, carrier);
                    }
                }
            }
        }

//        if (minConstDist1_carrier == null && minConstDist2_carrier==null) {
//            orderCarrier = null;
//        }
//        // 根据调度量分配装卸量
//        if (minConsDist1 == 0 && minConsDist2 == 0) {
//            orderCarrier = minConstDist1_carrier;
//        } else if (minConstDist1_carrier==null) {
//            orderCarrier = minConstDist2_carrier;
//        } else if (minConstDist2_carrier==null) {
//            orderCarrier = minConstDist1_carrier;
//        } else {
//            chooseBetterCarrierFromConstDist();
//        }
    }

//    void generateBestValueConsDist() {
//        for (Carrier carrier : region.getCarrierList()) {
//            if (!ConstraintsManager.isCarrierFeasible(carrier, route)) {
//                continue;
//            }
//            if (carrier.getCapacity() >= bestDispatchNum && carrier.getMinRatioCapacity() <= bestDispatchNum) {
//                double value = bidLabeling.getObjCal().calculateDualObj(bestDispatchNumLoads, carrier, bestDispatchNum);
//                if (value > bestValue) {
//                    setConstraint1(0, carrier);
//                    setConstraint2(0, carrier);
//                    bestValue = value;
//                    bestLoads = bestDispatchNumLoads;
//                }
//            } else {
//                ConsDist constDist = new ConsDist(bestDispatchNum, carrier);
//                if (constDist.consDist2 == 0 && constDist.consDist1 < minConsDist1) {
//                    HashMap<Integer, Integer> loads = this.dispatchNum2loads(carrier.getCapacity());
//                    double value = bidLabeling.getObjCal().calculateDualObj(loads, carrier, carrier.getCapacity());
//                    if (value > bestValue) {
//                        setConstraint1(constDist.consDist1, carrier);
//                        bestValue = value;
//                        bestLoads = loads;
//                    }
//                } else if (constDist.consDist1 == 0 && constDist.consDist2 < minConsDist2) {
//                    HashMap<Integer, Integer> loads = this.dispatchNum2loads(carrier.getMinRatioCapacity());
//                    double value = bidLabeling.getObjCal().calculateDualObj(loads, carrier, carrier.getMinRatioCapacity());
//                    if (value > bestValue) {
//                        setConstraint2(constDist.consDist2, carrier);
//                        bestValue = value;
//                        bestLoads = loads;
//                    }
//                }
//            }
//        }
//

    /// /        if (minConstDist1_carrier == null && minConstDist2_carrier==null) {
    /// /            orderCarrier = null;
    /// /        }
    /// /        // 根据调度量分配装卸量
    /// /        if (minConsDist1 == 0 && minConsDist2 == 0) {
    /// /            orderCarrier = minConstDist1_carrier;
    /// /        } else if (minConstDist1_carrier==null) {
    /// /            orderCarrier = minConstDist2_carrier;
    /// /        } else if (minConstDist2_carrier==null) {
    /// /            orderCarrier = minConstDist1_carrier;
    /// /        } else {
    /// /            chooseBetterCarrierFromConstDist();
    /// /        }
//    }
    public void chooseBetterCarrierFromConstDist() {
        HashMap<Integer, Double> loads1 = this.dispatchNum2loads(minConstDist1_carrier.getCapacity());
        HashMap<Integer, Double> loads2 = this.dispatchNum2loads(minConstDist2_carrier.getMinRatioCapacity());
        double value1 = PriceCalculator.calculateDualObj(loads1, minConstDist1_carrier, minConstDist1_carrier.getCapacity(), fenceIndexList);
        double value2 = PriceCalculator.calculateDualObj(loads2, minConstDist2_carrier, minConstDist2_carrier.getMinRatioCapacity(), fenceIndexList);
        if (value1 >= value2) {
            bestLoads = loads1;
            orderCarrier = minConstDist1_carrier;
        } else {
            bestLoads = loads2;
            orderCarrier = minConstDist2_carrier;
        }
    }

    public void setConstraint1(double value, Carrier carrier) {
        minConsDist1 = value;
        minConstDist1_carrier = carrier;
    }

    public void setConstraint2(double value, Carrier carrier) {
        minConsDist2 = value;
        minConstDist2_carrier = carrier;
    }
}