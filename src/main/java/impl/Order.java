package impl;

import baseinfo.Constants;

import java.util.ArrayList;

public class Order {
    private double distance;
    private int fenceNumber;
    private ArrayList<Integer> fenceList;
    private Carrier carrier;
    private double price;
    private Fence depot;

    public Order(Route route) {
        this.distance = route.getDistance();
        this.fenceNumber = route.getVisitNumber();
        this.fenceList = route.getFenceList();
        this.carrier = route.getCarrier();
        this.depot = route.getStartFence();
        this.price = 0;
        calculatePrice();
    }

    public double getDistance() {
        return distance;
    }

    public void setDistance(double distance) {
        this.distance = distance;
    }

    public void setPrice(double price) {
        this.price = price;
    }

    public void calculatePrice() {

    }
}
