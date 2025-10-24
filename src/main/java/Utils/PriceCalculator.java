package Utils;

import impl.Carrier;
import impl.Order;
import baseinfo.Constants;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

public class PriceCalculator {
    public static double calculateDualObj(Order order) {
        return 1.0;
    }

    public void calculateDeliverPrice(Order order){
        double distance = order.getDistance();
        order.setPrice(distance * Constants.DELIVERCOSTPERMETER);
    }

    public static double calculatePrimalObj(Order order){
        return 1.0;
    }

    public static double calculateDualObj(HashMap<Integer, Double> loads, Carrier carrier, Double dispatchNum, List<Integer> fenceIndexList) {
        return 1.0;
    }
}
