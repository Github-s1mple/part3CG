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
    private double minDispatchNum;
    private double maxDispatchNum;
    private ArrayList<Integer> vaildArcFence;
    private double nearestDiffLabelDist;
    private String constName;
    private double originalFenceValue;

    public Fence(Integer index, Double totalDemand, Double selfDemand, Double depotDemand, Double deliverDemand) {
        this.index = index;
        this.totalDemand = totalDemand;
        this.selfDemand = selfDemand;
        this.depotDemand = depotDemand;
        this.deliverDemand = deliverDemand;
        this.distanceMap = new HashMap<>();
        this.constName = null;
    }
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

    public Double getDistance(Fence endFence) {
        return this.distanceMap.get(endFence.getIndex());
    }

}
