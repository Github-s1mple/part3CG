package impl;

import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;

@Setter
@Getter
public class Depots {
    private ArrayList<Depot> depotList;
    private ArrayList<Integer> depotIndexList;
    private int depotNum;

    public Depots(){
        depotList = new ArrayList<Depot>();
        depotIndexList = new ArrayList<Integer>();
        depotNum = 0;
    }

    public Depot getDepot(Integer depotIndex) {
        for (Depot depot : depotList) {
            if (Objects.equals(depot.getIndex(), depotIndex)) {
                return depot;
            }
        }
        return null;
    }

    public List<Integer> getDepotIndexes() {
        return depotIndexList;
    }

    public void generateDepotIndexList() {
        if (depotList != null) {
            for (Depot depot : depotList) {
                depotIndexList.add(depot.getIndex());
                depotNum += 1;
            }
        }
    }

    public List<HashMap<Integer, Double>> generateDepotDistanceMatrix() {
        List<HashMap<Integer, Double>> depotDistanceMatrix = new ArrayList<>();
        if (depotList != null) {
            for (Depot depot : depotList) {
                depotDistanceMatrix.add(depot.getDepotMap());
            }
        }
        return depotDistanceMatrix;
    }
}
