package impl;

import baseinfo.Map;
import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.HashMap;
@Setter
@Getter
public class Fence {
    private Integer index;
    private Double totalDemand;
    private Double selfDemand;
    private Double depotDemand;
    private Double deliverDemand;
    private HashMap<Integer, Double> distanceMap;
    private double fenceValue;
    private boolean isDepot;
    private int minDispatchNum;
    private int maxDispatchNum;
    public Fence(Integer index, HashMap<Integer, Integer> fence) {}

    public Fence(Integer index) {}
    public Integer getIndex() {
        return index;
    }

    public void createDistanceMap(Map map) {
        ArrayList<Double> distanceList = map.getMapList().get(index);
        for (int i = 0; i < distanceList.size(); i++) {
            this.distanceMap.put(i,  distanceList.get(i));
        }
    }

    public Double getDistance(Integer endFence) {
        return this.distanceMap.get(endFence);
    }
}
