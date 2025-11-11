package impl;

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

    public Instance() {
        distanceMatrix = MapDistance.initialDistanceMatrix();
        List<double []> depotMap = MapDistance.initialDepotMap();
        fences = new Fences();
        depots = new Depots();
        carriers = new Carriers();
        initializer = new Initializer();
        depots.setDepotList(initializer.depotInitializer(depotMap));
        fences.setFenceList(initializer.fenceInitializer(distanceMatrix));
        fences.generateFenceIndexList();
        depots.generateDepotIndexList();
        depotDistanceMatrix = depots.generateDepotDistanceMatrix();
        carrierList = initializer.carrierInitializer(Constants.ISDIFFERENTCARRIER);
        carriers.setCarrierList(carrierList);
        orderList = null;
    }

}
