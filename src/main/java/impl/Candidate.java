package impl;

import baseinfo.Constants;
import baseinfo.MapDistance;
import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

@Setter
@Getter
public class Candidate {
    private Integer index; // 仓库索引为负
    private Double capacity;
    private final double longitude; // 经度
    private final double latitude;  // 纬度
    private final HashMap<Integer, Double> candidateMap; // 围栏index→距离映射
    private double buildCost;
    private double dClass;

    // 构造方法
    public Candidate(Integer index, double longitude, double latitude, double buildCost, double dClass) {
        this.index = index;
        this.longitude = longitude;
        this.latitude = latitude;
        this.candidateMap = new HashMap<>();
        this.buildCost = buildCost;
        this.dClass = dClass;
    }

    public void generateDistanceMap(List<double[]> fenceCoordinates){
        for (Integer index = 0; index < fenceCoordinates.size(); index++) {
            double[] fence = fenceCoordinates.get(index);
            double distance = MapDistance.calculateSphericalDistance(
                    latitude, longitude,
                    fence[1], fence[0]
            );
            candidateMap.put(index + 1, distance);
        }
    }

    public Double getDistance(Integer endFence) {
        return this.candidateMap.get(endFence);
    }

    public Double getDistance(Fence endFence) {
        return this.candidateMap.get(endFence.getIndex());
    }
}
