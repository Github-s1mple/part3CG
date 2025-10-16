package impl;

import baseinfo.Constants;
import baseinfo.Map;
import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
@Setter
@Getter

public class Route {
    private double distance;
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
