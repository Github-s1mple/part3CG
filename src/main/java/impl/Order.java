package impl;

import baseinfo.Constants;
import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.HashMap;

@Setter
@Getter
public class Order {
    private Fences fences;
    private Integer orderId;
    private double distance;
    private HashMap<Integer, Double> loads;
    private ArrayList<Integer> fenceList;
    private Integer depot;
    private int fenceNumber;
    private Carrier carrier;
    private double price;
    private double dispatchNum;
    private double reducedCost;
    private double carrierCost;

    public Order(){
        loads = new HashMap<>();
        fenceList = new ArrayList<>();
    }

    public Order(Route route, HashMap<Integer, Double> loads, Carrier carrier, Integer orderId) {
        this.fences = route.getFences();
        this.orderId = orderId;
        this.loads = loads;
        this.distance = route.getDistance();
        this.fenceNumber = route.getVisitNumber();
        this.fenceList = route.getFenceList();
        this.carrier = carrier;
        this.depot = route.getDepot();
        this.price = 0;
        calculateCarrierPrice();
    }

    public void addLoad(Integer index, Double load){
        loads.put(index, load);
    }

    public void addFence(Integer index){
        fenceList.add(index);
    }

    public void calculateCarrierPrice(){
        carrierCost = distance * Constants.DELIVERCOSTPERMETER;
    }
}
