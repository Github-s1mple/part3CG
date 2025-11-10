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
    private Boolean isFakeFence;

    public Fence(Integer index, Double Lon, Double Lat, Double totalDemand, Double selfDemand, Double depotDemand, Double deliverDemand, Double unitPrice, Boolean isFakeFence) {
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
        this.nearestDiffLabelDist = 9999.0;
        this.isFakeFence = isFakeFence;
    }

    public void generateDistanceMap(List<List<Double>> distanceMatrix){
        List<Double> distances = distanceMatrix.get(index);
        for (int targetIndex = 0; targetIndex < distances.size(); targetIndex++) {
            Double distance = distances.get(targetIndex);
            distanceMap.put(targetIndex, distance);
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
