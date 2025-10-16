package Utils;

import impl.Order;
import baseinfo.Constants;

public class PriceCalculator {
    public void calculateDeliverPrice(Order order){
        double distance = order.getDistance();
        order.setPrice(distance * Constants.DELIVERCOSTPERMETER);
    }
}
