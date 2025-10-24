package impl;

import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

@Setter
@Getter
public class Depots {
    private ArrayList<Depot> depotList;
    private ArrayList<Integer> depotIndexList;
    private ArrayList<Fence> fenceList;
    private int depotNum;
    public Depot getDepot(Integer depotIndex) {
        for (Depot depot : depotList) {
            if (depot.getIndex() == depotIndex) {
                return depot;
            }
        }
        return null;
    }

    public void addDepot(Depot depot) {
        this.depotList.add(depot);
    }

    public List<Integer> getDepotIndexes() {
        return depotIndexList;
    }

    public boolean isValidDepot(int depotIdx) {
        if (depotIdx >= depotNum) {
            return false;
        }
        return  true;
    }
}
