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
    private Double minRatioCapacity;
    private double carrierValue;
    private String constName;
    private Integer maxResource;
}
