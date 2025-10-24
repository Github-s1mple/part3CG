package impl;

import baseinfo.Map;
import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.HashMap;

@Setter
@Getter
public class Depot {
    private Integer index;
    private Double capacity;
    private HashMap<Integer, Double> distanceMap;
    private double fenceValue;
    private int minDispatchNum;
    private int maxDispatchNum;
    public Depot(Integer index, HashMap<Integer, Integer> fence) {}

    public Depot(Integer index) {}
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
