package Stages;

import lombok.Getter;
import lombok.Setter;

import java.util.Map;
@Setter
@Getter
public class Scenario {
    private final int id;
    private final double probability;
    private final Map<Integer, Double> demandFactors;  // 栅格ID→需求系数

    public Scenario(int id, double probability, Map<Integer, Double> demandFactors) {
        this.id = id;
        this.probability = probability;
        this.demandFactors = demandFactors;
    }
}
