package impl;

import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;

@Setter
@Getter
public class Candidates {
    private ArrayList<Candidate> candidateList;
    private ArrayList<Integer> candidateIndexList;
    private int candidateNum;

    public Candidates(){
        candidateList = new ArrayList<Candidate>();
        candidateIndexList = new ArrayList<Integer>();
        candidateNum = 0;
    }

    public Candidate getCandidate(Integer candidateIndex) {
        for (Candidate candidate : candidateList) {
            if (Objects.equals(candidate.getIndex(), candidateIndex)) {
                return candidate;
            }
        }
        return null;
    }

    public List<Integer> getCandidateIndexes() {
        return candidateIndexList;
    }

    public void generateCandidateIndexList() {
        if (candidateList != null) {
            for (Candidate candidate : candidateList) {
                candidateIndexList.add(candidate.getIndex());
                candidateNum += 1;
            }
        }
    }

    public List<HashMap<Integer, Double>> generateCandidateDistanceMatrix() {
        List<HashMap<Integer, Double>> candidateDistanceMatrix = new ArrayList<>();
        if (candidateList != null) {
            for (Candidate candidate : candidateList) {
                candidateDistanceMatrix.add(candidate.getCandidateMap());
            }
        }
        return candidateDistanceMatrix;
    }

    public int size() {
        return  this.candidateList.size();
    }
}
