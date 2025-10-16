package impl;

import baseinfo.Constants;
import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
@Setter
@Getter
public class Order {
    private double distance;
    private int fenceNumber;
    private ArrayList<Integer> fenceList;
    private Carrier carrier;
    private double price;
    private Fence depot;
    private double dualValue;
    private double totalValue;
    private double dualObj;

    public Order(Route route) {
        this.distance = route.getDistance();
        this.fenceNumber = route.getVisitNumber();
        this.fenceList = route.getFenceList();
        this.carrier = route.getCarrier();
        this.depot = route.getStartFence();
        this.price = 0;
        calculatePrice();
    }

    public void calculatePrice() {

    }
}
