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
    private int fenceNumber;
    private HashMap<Integer, Double> loads;
    private ArrayList<Integer> fenceList;
    private Carrier carrier;
    private double price;
    private Integer depot;
    private double dispatchNum;
    private double reducedCost;
    private double carrierCost;

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

    public void calculateCarrierPrice(){
        carrierCost = distance * Constants.DELIVERCOSTPERMETER;
    }
}
