package algo;

import impl.Fences;
import impl.Order;
import impl.Route;
import baseinfo.Constants;
import impl.Carrier;
import impl.Fence;
import java.util.HashMap;
import java.util.List;
import java.util.stream.Collectors;

import static java.lang.Math.min;

public class LoadingAlgorithm {
    // 通用
    private final IObjectiveCalculator objCal;
    private final Fences fences;
    private final Regions regions;
    private int orderCnt;
    //每次求解重新赋值
    private int minConsDist1; // 载重约束距离
    private int minConsDist2; // 满载率约束距离
    private Carrier minConstDist1_carrier;
    private Carrier minConstDist2_carrier;
    //    private double bestValue;
    private HashMap<Integer, Integer> bestLoads;
    private Region region;
    private Route route;
    private List<Integer> fenceIndexList;
    private List<Integer> loadIndexes;
    private List<Integer> unloadIndexes;
    private int bestDispatchNum;
    private HashMap<Integer, Integer> bestDispatchNumLoads;
    private Carrier orderCarrier;

    private static class ConsDist {
        //装载量是否小于容量，0为满足容量约束，正数为超过容量的调度量
        int consDist1;
        //装载量是否小于满载率，0为满足满载率，正数为达到满载率还需的调度量
        int consDist2;

        ConsDist(int bestDispatchNum, Carrier carrier) {
            consDist1 = Math.max(0, bestDispatchNum - carrier.getCapacity());
            consDist2 = Math.max(0, carrier.getMinRatioCapacity() - bestDispatchNum);
        }
    }

    public LoadingAlgorithm(BidLabeling bidLabeling) {
        this.objCal = bidLabeling.getObjCal();
        this.fences = bidLabeling.getFences();
        this.regions = bidLabeling.getRegions();

        this.orderCnt = 0;
    }

    public Order solve(Route rRoute) {
        // 获取基本信息

        route = rRoute;
        boolean isLongDistance = route.isLongDistance();
        fenceIndexList = route.getFenceIndexList();
        loadIndexes = route.getLoadIndex().stream().sorted((o1, o2) -> fences.getFenceValue(o1).compareTo(fences.getFenceValue(o2)))
                .collect(Collectors.toList());
        unloadIndexes = route.getUnloadIndex().stream().sorted((o1, o2) -> fences.getFenceValue(o2).compareTo(fences.getFenceValue(o1)))
                .collect(Collectors.toList());
        // 计算最优调度量
        bestDispatchNum = route.getMaxDispatchNum();
        bestDispatchNumLoads = dispatchNum2loads(route.getMaxDispatchNum(), isLongDistance);

        // 选择满足约束的载具
        minConsDist1 = Integer.MAX_VALUE; // 载重约束距离
        minConsDist2 = Integer.MAX_VALUE; // 满载率约束距离
        minConstDist1_carrier = null;
        minConstDist2_carrier = null;

//        bestValue = 0.0;
        bestLoads = new HashMap<>();
        region = regions.get(route.getRfId());

        generateConsDist();
        if (minConstDist1_carrier == null && minConstDist2_carrier == null) {
            return null;
        }
        // 根据调度量分配装卸量
        if (minConsDist1 == 0 && minConsDist2 == 0) {
            bestLoads = bestDispatchNumLoads;
            orderCarrier = minConstDist1_carrier;
        } else if (minConstDist1_carrier == null) {
            bestLoads = this.dispatchNum2loads(minConstDist2_carrier.getMinRatioCapacity(), isLongDistance);

            orderCarrier = minConstDist2_carrier;
        } else if (minConstDist2_carrier == null) {
            bestLoads = this.dispatchNum2loads(minConstDist1_carrier.getCapacity(), isLongDistance);

            orderCarrier = minConstDist1_carrier;
        } else {
            chooseBetterCarrierFromConstDist();
        }

        if (orderCarrier == null) {
            return null;
        } else {
            orderCnt = orderCnt + 1;
            if (!isLongDistance) return new Order(route, bestLoads, orderCarrier, orderCnt, 1);
            else return new Order(route, bestLoads, orderCarrier, orderCnt, 2);
        }
    }

    private HashMap<Integer, Integer> dispatchNum2loads(int dispatchNum, boolean streetsweep) {
        HashMap<Integer, Integer> loads = new HashMap<>();
        generateNumWithType(loadIndexes, dispatchNum, NameConstants.LOAD, loads, streetsweep);
        generateNumWithType(unloadIndexes, dispatchNum, NameConstants.UN_LOAD, loads, streetsweep);
        return loads;
    }

    private void generateNumWithType(List<Integer> indexes, int dispatchNum, String loadType, HashMap<Integer, Integer> loads, boolean streetsweep) {
        int coefficient = 1;
        int loadCnt = dispatchNum;
        if (streetsweep && loadType.equals(NameConstants.LOAD)) {
            for (int fenceIndex : indexes) {
                Fence fence = fences.getFence(fenceIndex);
                int actualDispatchNum = min(1, Math.abs(fence.getDemand()));
                loads.put(fenceIndex, actualDispatchNum);
                loadCnt -= actualDispatchNum;
            }
            for (int i : indexes) {
                int lack = Math.abs(fences.getFence(i).getDemand()) - loads.get(i);
                if (lack >= loadCnt) {
                    loads.put(i, loads.get(i) + loadCnt);
                    break;
                }
                loads.put(i, loads.get(i) + lack);
                loadCnt -= lack;
            }
        } else {
            if (loadType.equals(NameConstants.UN_LOAD)) {
                coefficient = -1;
            }
            for (int fenceIndex : indexes) {
                Fence fence = fences.getFence(fenceIndex);
                loads.put(fenceIndex, coefficient * fence.getMinDispatchNum());
                loadCnt -= fence.getMinDispatchNum();
            }
            for (int i : indexes) {
                int lack = Math.abs(fences.getFence(i).getDemand()) - Math.abs(loads.get(i));
                if (lack >= loadCnt) {
                    loads.put(i, loads.get(i) + coefficient * loadCnt);
                    break;
                }
                loads.put(i, loads.get(i) + coefficient * lack);
                loadCnt -= lack;
            }
        }
    }

    private void generateConsDist() {
        for (Carrier carrier : region.getCarrierList()) {
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
        HashMap<Integer, Integer> loads1 = this.dispatchNum2loads(minConstDist1_carrier.getCapacity(), route.isLongDistance());
        HashMap<Integer, Integer> loads2 = this.dispatchNum2loads(minConstDist2_carrier.getMinRatioCapacity(), route.isLongDistance());
        double value1 = objCal.calculateDualObj(loads1, minConstDist1_carrier, minConstDist1_carrier.getCapacity(), fenceIndexList);
        double value2 = objCal.calculateDualObj(loads2, minConstDist2_carrier, minConstDist2_carrier.getMinRatioCapacity(), fenceIndexList);
        if (value1 >= value2) {
            bestLoads = loads1;
            orderCarrier = minConstDist1_carrier;
        } else {
            bestLoads = loads2;
            orderCarrier = minConstDist2_carrier;
        }
    }

    public void setConstraint1(int value, Carrier carrier) {
        minConsDist1 = value;
        minConstDist1_carrier = carrier;
    }

    public void setConstraint2(int value, Carrier carrier) {
        minConsDist2 = value;
        minConstDist2_carrier = carrier;
    }
}