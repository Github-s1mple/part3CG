import Utils.Initializer;
import baseinfo.MapDistance;
import impl.Depot;
import impl.Fence;

import java.util.ArrayList;
import java.util.List;

import static baseinfo.MapDistance.initialDistanceMatrix;

public class FenceInitialTest {
    public static void main(String[] args) {
        List<List<Double>> distanceMatrix;
        Initializer initializer = new Initializer();
        distanceMatrix = initialDistanceMatrix();
        List<double[]> depotMap;
        depotMap = MapDistance.initialDepotMap();
        ArrayList<Fence> fenceList;
        fenceList = initializer.fenceInitializer(distanceMatrix);
        ArrayList<Depot> depotList;
        depotList = initializer.depotInitializer(depotMap);
        System.out.println("测试完毕");
    }
}