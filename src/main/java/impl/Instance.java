package impl;

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
    private final Integer index;
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
    private double scenarioProbability;

    public Instance() {
        index = 0;
        distanceMatrix = MapDistance.initialDistanceMatrix(null);
        List<double []> depotMap = MapDistance.initialDepotMap();
        fences = new Fences();
        depots = new Depots();
        carriers = new Carriers();
        initializer = new Initializer();
        depots.setDepotList(initializer.depotInitializer(depotMap, null));
        fences.setFenceList(initializer.fenceInitializer(distanceMatrix, null, null));
        fences.generateFenceIndexList();
        depots.generateDepotIndexList();
        depotDistanceMatrix = depots.generateDepotDistanceMatrix();
        carrierList = initializer.carrierInitializer();
        carriers.setCarrierList(carrierList);
        orderList = null;
    }

    public Instance(LocationResult result) {
        index = 0;
        distanceMatrix = MapDistance.initialDistanceMatrix(null);
        List<double []> depotMap = MapDistance.initialDepotMap();
        fences = new Fences();
        depots = new Depots();
        carriers = new Carriers();
        initializer = new Initializer();
        depots.setDepotList(initializer.depotInitializer(depotMap, result));
        fences.setFenceList(initializer.fenceInitializer(distanceMatrix, result, null));
        fences.generateFenceIndexList();
        depots.generateDepotIndexList();
        depotDistanceMatrix = depots.generateDepotDistanceMatrix();

        carrierList = initializer.carrierInitializer();
        carriers.setCarrierList(carrierList);
        orderList = null;
    }

    public Instance(LocationResult result, Scenario scenario) {
        this.index = scenario.getId();
        distanceMatrix = MapDistance.initialDistanceMatrix(scenario);
        List<double []> depotMap = MapDistance.initialDepotMap();
        fences = new Fences();
        depots = new Depots();
        carriers = new Carriers();
        initializer = new Initializer();
        depots.setDepotList(initializer.depotInitializer(depotMap, result));
        fences.setFenceList(initializer.fenceInitializer(distanceMatrix, result, scenario));
        fences.generateFenceIndexList();
        depots.generateDepotIndexList();
        depotDistanceMatrix = depots.generateDepotDistanceMatrix();
        carrierList = initializer.carrierInitializer();
        carriers.setCarrierList(carrierList);
        orderList = null;
        this.scenario = scenario;
        this.scenarioProbability = scenario.getProbability();
    }
}
