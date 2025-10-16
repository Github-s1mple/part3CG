package impl;

import baseinfo.Constants;
import baseinfo.Map;
import java.util.ArrayList;

public class Route {
    private double distance;

    public double getDistance() {
        return distance;
    }

    public void setDistance(double distance) {
        this.distance = distance;
    }

    public int getVisitNumber() {
        return visitNumber;
    }

    public void setVisitNumber(int visitNumber) {
        this.visitNumber = visitNumber;
    }

    public Double getMaxDistance() {
        return maxDistance;
    }

    public void setMaxDistance(Double maxDistance) {
        this.maxDistance = maxDistance;
    }

    public Integer getMaxVisitNumber() {
        return maxVisitNumber;
    }

    public void setMaxVisitNumber(Integer maxVisitNumber) {
        this.maxVisitNumber = maxVisitNumber;
    }

    public ArrayList<Integer> getFenceList() {
        return fenceList;
    }

    public void setFenceList(ArrayList<Integer> fenceList) {
        this.fenceList = fenceList;
    }

    public Carrier getCarrier() {
        return carrier;
    }

    public void setCarrier(Carrier carrier) {
        this.carrier = carrier;
    }

    public Fence getStartFence() {
        return startFence;
    }

    public void setStartFence(Fence startFence) {
        this.startFence = startFence;
    }

    private int visitNumber;
    private Double maxDistance;
    private Integer maxVisitNumber;
    private ArrayList<Integer> fenceList;
    private Carrier carrier;
    private Fence startFence;
    private Fences fences;

    public Route(Fence startFence) {
        this.distance = 0.0;
        this.visitNumber = 0;
        this.maxDistance = Constants.MAXDISTANCE;
        this.maxVisitNumber = Constants.MAXVISITNUMBER;
        this.fenceList = new ArrayList<>();
        this.carrier = null;
        this.startFence = startFence;
    }

    public void addFence(Fence newFence) {
        Integer lastNode = fenceList.getLast();
        Fence lastFence = fences.getFence(lastNode);
        double distance = lastFence.getDistance(newFence.getIndex());
        this.fenceList.add(newFence.getIndex());
        this.visitNumber++;
        this.distance += distance;
    }

    public void addFence(Integer newFence) {
        Integer lastNode = fenceList.getLast();
        Fence lastFence = fences.getFence(lastNode);
        double distance = lastFence.getDistance(newFence);
        this.fenceList.add(newFence);
        this.visitNumber++;
        this.distance += distance;
    }
}
