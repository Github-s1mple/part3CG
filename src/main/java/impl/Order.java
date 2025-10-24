package impl;

import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.HashMap;

@Setter
@Getter
public class Order {
    private int orderCnt;
    private double distance;
    private int fenceNumber;
    private HashMap<Integer, Double> loads;
    private ArrayList<Integer> fenceList;
    private Carrier carrier;
    private double price;
    private Integer depot;
    private double dispatchNum;
    private double dualValue;
    private double totalValue;
    private double dualObj;

    public Order(Route route, HashMap<Integer, Double> loads, Carrier carrier, int orderCnt) {
        this.orderCnt = orderCnt;
        this.loads = loads;
        this.distance = route.getDistance();
        this.fenceNumber = route.getVisitNumber();
        this.fenceList = route.getFenceList();
        this.carrier = carrier;
        this.depot = route.getDepot();
        this.price = 0;
        calculatePrice();
    }

    public void calculatePrice() {

    }
}
