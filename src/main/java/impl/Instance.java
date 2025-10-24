package impl;

import baseinfo.Map;
import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

@Setter
@Getter
public class Instance {
    private ArrayList<Order> orderList;
    private Fences fences;
    private Carriers carriers;
    private Depots depots;
    private Map fenceMap;
    private ArrayList<Carrier> carrierList;
    private

    public IObjectiveCalculator getObjCal() {
    }
}
