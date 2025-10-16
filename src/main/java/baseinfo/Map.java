package baseinfo;

import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.HashMap;
@Setter
@Getter
public class Map {
    private ArrayList<ArrayList<Double>> mapList;

    public  ArrayList<ArrayList<Double>> getMapList() {
        return mapList;
    }

    public double getDistance(Integer startFence, Integer endFence){
        return 0.0;
    }
}
