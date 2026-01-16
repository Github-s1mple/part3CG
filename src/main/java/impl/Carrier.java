package impl;

import baseinfo.Constants;
import lombok.Getter;
import lombok.Setter;

@Setter
@Getter
public class Carrier {
    private Integer index;
    private Double capacity;
    private Double maxDistance;
    private Integer depot;
    private String constName;
    private Integer maxUseTimes;

    public Carrier(Integer index, Double capacity, Double maxDistance, Integer depot) {
        this.index = index;
        this.capacity = capacity;
        this.maxDistance = maxDistance;
        this.depot = depot;
        this.constName = "C" + index;
        this.maxUseTimes = Constants.CARRY_MAX_USE_TIMES;
    }
}
