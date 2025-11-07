package impl;

import baseinfo.Constants;
import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

@Setter
@Getter
public class Fence {
    private Integer index;
    private Double lon;
    private Double lat;
    private Double totalDemand;
    private Double selfDemand;
    private Double depotDemand;
    private Double deliverDemand;
    private Double originalFenceValue;
    private HashMap<Integer, Double> distanceMap;
    private double fenceValue;
    private double minDispatchNum;
    private double maxDispatchNum;
    private ArrayList<Integer> vaildArcFence;
    private double nearestDiffLabelDist;
    private String constName;

    public Fence(Integer index, Double Lon, Double Lat, Double totalDemand, Double selfDemand, Double depotDemand, Double deliverDemand, Double unitPrice) {
        this.index = index;
        this.lon = Lon;
        this.lat = Lat;
        this.totalDemand = totalDemand;
        this.selfDemand = selfDemand;
        this.depotDemand = depotDemand;
        this.deliverDemand = deliverDemand;
        this.distanceMap = new HashMap<>();
        this.constName = "F" + index;
        this.vaildArcFence = new ArrayList<>();
        this.originalFenceValue = unitPrice;
    }

    public void generateDistanceMap(List<List<Double>> distanceMatrix){
        List<Double> distances = distanceMatrix.get(index);
        for (int targetIndex = 0; targetIndex < distances.size(); targetIndex++) {
            Double distance = distances.get(targetIndex);
            distanceMap.put(targetIndex, distance); // 或根据业务逻辑处理
            if (distance <= Constants.MAXDISTANCE / 2){
                vaildArcFence.add(targetIndex);
            }
        }
        vaildArcFence.add(999);
    }

    public Double getDistance(Integer endFence) {
        return this.distanceMap.get(endFence);
    }

    public Double getDistance(Fence endFence) {
        return this.distanceMap.get(endFence.getIndex());
    }

}
