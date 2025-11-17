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
public class Depot {
    private Integer index; // 仓库索引为负
    private Double capacity;
    private final double longitude; // 经度
    private final double latitude;  // 纬度
    private ArrayList<Integer> validArcFence;
    private double nearestDiffLabelDist;
    private String constName;
    private double originalDepotValue;
    private final HashMap<Integer, Double> depotMap; // 围栏index→距离映射
    private int minDispatchNum;
    private int maxDispatchNum;

    // 构造方法
    public Depot(Integer index, double longitude, double latitude) {
        this.index = index;
        this.longitude = longitude;
        this.latitude = latitude;
        this.depotMap = new HashMap<>();
        this.validArcFence = new ArrayList<>();
        this.constName = "D" + index;
        this.nearestDiffLabelDist = 9999.0;
    }

    public void generateDistanceMap(List<double[]> fenceCoordinates){
        for (Integer index = 0; index < fenceCoordinates.size(); index++) {
            double[] fence = fenceCoordinates.get(index);
            // 调用MapDistance的球面距离计算方法
            double distance = MapDistance.calculateSphericalDistance(
                    latitude, longitude,  // Depot的纬度、经度
                    fence[1], fence[0]   // 围栏的纬度（fence[1]）、经度（fence[0]）
            );
            depotMap.put(index + 1, distance);
            if (distance <= Constants.MAX_DISTANCE / 2){
                validArcFence.add(index + 1);
            }
        }
    }

    public Double getDistance(Integer endFence) {
        return this.depotMap.get(endFence);
    }

    public Double getDistance(Fence endFence) {
        return this.depotMap.get(endFence.getIndex());
    }

    public Fence depot2Fence(Integer index){
        Fence fence = new Fence(index, longitude, latitude, 0.0, 0.0, 0.0, 0.0, 0.0, true);
        fence.setDistanceMap(depotMap);
        fence.setFenceValue(0);
        fence.setMinDispatchNum(0.0);
        fence.setMaxDispatchNum(0.0);
        fence.setVaildArcFence(validArcFence);
        fence.setNearestDiffLabelDist(nearestDiffLabelDist);
        fence.setConstName("ND" + index);
        return fence;
    }
}
