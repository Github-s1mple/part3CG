package impl;

import baseinfo.Constants;
import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

@Setter
@Getter

public class Route {
    private Fences fences;
    private double distance;
    private int visitNumber;
    private Double maxDistance;
    private Integer maxVisitNumber;
    private ArrayList<Integer> fenceList;
    private Integer depot;
    private Double MaxDispatchNum;

    public Route(Fences fences, double total_dist, int total_visit_num, ArrayList<Integer> fenceIndexList, Integer depot, double MaxDispatchNum) {
        this.fences = fences;
        this.distance = total_dist;
        this.visitNumber = total_visit_num;
        this.fenceList = fenceIndexList;
        this.maxDistance = Constants.MAX_DISTANCE;
        this.maxVisitNumber = Constants.MAX_VISIT_NUM;
        this.depot = depot;
        this.MaxDispatchNum = MaxDispatchNum;
    }

    public static Route generate(Fences fences, double total_dist, int total_visit_num, ArrayList<Integer> fenceIndexList, Integer depot, double MaxDispatchNum) {
        return new Route(fences, total_dist, total_visit_num, fenceIndexList, depot, MaxDispatchNum);
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

    public String getRouteVitedString() {
        List<Integer> allNodesInPath = new ArrayList<>(this.fenceList);

        // 去重
        List<Integer> uniqueNodes = allNodesInPath.stream()
                .distinct().sorted((node1, node2) -> {
                    if (Objects.equals(node1, this.depot)) return -1;
                    if (Objects.equals(node2, this.depot)) return 1;
                    return Integer.compare(node1, node2);
                }).toList();

        return uniqueNodes.stream()
                .map(String::valueOf)
                .collect(Collectors.joining(","));
    }
}
