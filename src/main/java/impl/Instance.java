package impl;

import Stages.Scenario;
import baseinfo.Constants;
import baseinfo.MapDistance;
import Utils.Initializer;
import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

@Setter
@Getter
public class Instance {
    private ArrayList<Order> orderList;
    private Fences fences;
    private Carriers carriers;
    private Depots depots;
    private MapDistance fenceMapDistance;
    private ArrayList<Carrier> carrierList;
    private List<List<Double>> distanceMatrix;
    private List<HashMap<Integer, Double>> depotDistanceMatrix;
    private Initializer initializer;
    private Scenario scenario;

    public Instance() {
        distanceMatrix = MapDistance.initialDistanceMatrix();
        List<double []> depotMap = MapDistance.initialDepotMap();
        fences = new Fences();
        depots = new Depots();
        carriers = new Carriers();
        initializer = new Initializer();
        depots.setDepotList(initializer.depotInitializer(depotMap, null));
        fences.setFenceList(initializer.fenceInitializer(distanceMatrix, null));
        fences.generateFenceIndexList();
        depots.generateDepotIndexList();
        depotDistanceMatrix = depots.generateDepotDistanceMatrix();
        carrierList = initializer.carrierInitializer(Constants.IS_DIFFERENT_CARRIER);
        carriers.setCarrierList(carrierList);
        orderList = null;
    }

    public Instance(LocationResult result) {
        distanceMatrix = MapDistance.initialDistanceMatrix();
        List<double []> depotMap = MapDistance.initialDepotMap();
        fences = new Fences();
        depots = new Depots();
        carriers = new Carriers();
        initializer = new Initializer();
        depots.setDepotList(initializer.depotInitializer(depotMap, result));
        fences.setFenceList(initializer.fenceInitializer(distanceMatrix, result));
        fences.generateFenceIndexList();
        depots.generateDepotIndexList();
        depotDistanceMatrix = depots.generateDepotDistanceMatrix();

        carrierList = initializer.carrierInitializer(Constants.IS_DIFFERENT_CARRIER);
        carriers.setCarrierList(carrierList);
        orderList = null;
    }
}
