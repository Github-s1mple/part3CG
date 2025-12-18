package Stages;

import lombok.Getter;
import lombok.Setter;

@Setter
@Getter
public class Scenario {
    private final Integer id;
    private final double probability;
    private final String allPointsPath;

    public Scenario(int id, double probability, String allPointsPath) {
        this.id = id;
        this.probability = probability;
        this.allPointsPath = allPointsPath;
    }
}
