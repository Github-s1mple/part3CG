package impl;

import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;

@Setter
@Getter
public class Carriers {
    private Integer index;
    private Double capacity;
    private Double price;
    private Double maxDistance;
    private Integer depot;
    private Integer minRatioCapacity;
    private ArrayList<Carrier> carrierList;

}
