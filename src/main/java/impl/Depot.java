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
    private Integer index;
    private Double capacity;
    private final double longitude; // 经度
    private final double latitude;  // 纬度
    private ArrayList<Integer> vaildArcFence;
    private double nearestDiffLabelDist;
    private String constName;
    private double originalDepotValue;
    private double depotValue;
    private final HashMap<Integer, Double> depotMap; // 围栏index→距离映射
    private int minDispatchNum;
    private int maxDispatchNum;

    // 构造方法
    public Depot(Integer index, double longitude, double latitude) {
        this.index = index;
        this.longitude = longitude;
        this.latitude = latitude;
        this.depotMap = new HashMap<>();
        this.vaildArcFence = new ArrayList<>();
        this.constName = "D" + index;
    }

    public void generateDistanceMap(List<double[]> fenceCoordinates){
        for (int fenceIndex = 0; fenceIndex < fenceCoordinates.size(); fenceIndex++) {
            double[] fence = fenceCoordinates.get(fenceIndex);
            // 调用MapDistance的球面距离计算方法
            double distance = MapDistance.calculateSphericalDistance(
                    latitude, longitude,  // Depot的纬度、经度
                    fence[1], fence[0]   // 围栏的纬度（fence[1]）、经度（fence[0]）
            );
            depotMap.put(fenceIndex, distance);
            if (distance <= Constants.MAXDISTANCE / 2){
                vaildArcFence.add(fenceIndex);
            }
        }
    }

    public Double getDistance(Integer endFence) {
        return this.depotMap.get(endFence);
    }

    public Double getDistance(Fence endFence) {
        return this.depotMap.get(endFence.getIndex());
    }

    public Fence depot2Fence(){
        Fence fence = new Fence(999, longitude, latitude, 0.0, 0.0, 0.0, 0.0, 0.0);
        fence.setDistanceMap(depotMap);
        fence.setFenceValue(depotValue);
        fence.setMinDispatchNum(0.0);
        fence.setMaxDispatchNum(0.0);
        fence.setVaildArcFence(vaildArcFence);
        fence.setNearestDiffLabelDist(nearestDiffLabelDist);
        fence.setConstName("ND" + index);
        return fence;
    }
}
