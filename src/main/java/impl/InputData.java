package impl;

import Utils.Initializer;
import baseinfo.Constants;
import baseinfo.MapDistance;
import lombok.Getter;
import lombok.Setter;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Setter
@Getter
public class InputData {
    private Fences fences;
    private Candidates candidates;
    private MapDistance fenceMapDistance;
    private List<List<Double>> distanceMatrix;
    private List<HashMap<Integer, Double>> candidateDistanceMatrix;
    private Initializer initializer;

    public InputData() {
        distanceMatrix = MapDistance.initialDistanceMatrix();
        List<double []> candidateMap = MapDistance.initialCandidateMap();
        fences = new Fences();
        candidates = new Candidates();
        initializer = new Initializer();
        candidates.setCandidateList(initializer.candidateInitializer(candidateMap));
        fences.setFenceList(initializer.fenceInitializer(distanceMatrix));
        fences.generateFenceIndexList();
        candidates.generateCandidateIndexList();
        candidateDistanceMatrix = candidates.generateCandidateDistanceMatrix();
    }

    // 候选点i的固定成本f_i
    public Map<Integer, Double> fixedCost;
}