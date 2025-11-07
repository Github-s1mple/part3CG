package impl;

import lombok.Getter;
import lombok.Setter;

@Setter
@Getter
public class Carrier {
    private Integer index;
    private Double capacity;
    private Double maxDistance;
    private Integer depot;
    private Double minRatioCapacity;
    private double carrierValue;
    private String constName;
    private Integer maxUseTimes;

    public Carrier(Integer index, Double capacity, Double maxDistance, Integer depot, Double minRatioCapacity) {
        this.index = index;
        this.capacity = capacity;
        this.maxDistance = maxDistance;
        this.depot = depot;
        this.minRatioCapacity = minRatioCapacity;
        this.carrierValue = 0.0;
        this.constName = "C" + index;
        this.maxUseTimes = 1;
    }
}
