package impl;

import lombok.Getter;
import lombok.Setter;

@Setter
@Getter
public class Carrier {
    private Integer index;
    private Double capacity;
    private Double price;
    private Double maxDistance;
    private Integer depot;
    private Integer minRatioCapacity;
}
