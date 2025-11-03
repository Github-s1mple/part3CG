import baseinfo.MapDistance;
import impl.Depot;
import impl.Fence;

import java.util.ArrayList;
import java.util.List;

import static Utils.Initializer.depotInitializer;
import static Utils.Initializer.fenceInitializer;
import static baseinfo.MapDistance.initialDistanceMatrix;

public class FenceInitialTest {
    public static void main(String[] args) {
        List<List<Double>> distanceMatrix;
        distanceMatrix = initialDistanceMatrix();
        List<double[]> depotMap;
        depotMap = MapDistance.initialDepotMap();
        ArrayList<Fence> fenceList;
        fenceList = fenceInitializer(distanceMatrix);
        ArrayList<Depot> depotList;
        depotList = depotInitializer(depotMap);
        System.out.println("测试完毕");
    }
}