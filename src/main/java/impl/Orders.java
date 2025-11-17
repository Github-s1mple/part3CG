package impl;

import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Setter
@Getter
public class Orders {
    private List<Order> orderList;
    private int orderNumber;
    private double totalDeliverPrice;
    private double totalDispatchNum;
    private double totalCarrierCost;
    private double totalDistance;
    private int totalFenceNum;

    public Orders(List<Order> orderList) {
        this.orderList = orderList;
        orderNumber = 0;
        totalCarrierCost = 0.0;
        totalDistance = 0.0;
        totalFenceNum = 0;
        totalDispatchNum = 0.0;
        totalDeliverPrice = 0.0;
        calOrderDetail();
    }

    public void calOrderDetail() {
        if (orderList == null) {
            return;
        }
        for (Order order : orderList) {
            totalDeliverPrice += order.getOriginalPrice();
            totalCarrierCost += order.getCarrierCost();
            totalDistance += order.getDistance();
            totalFenceNum += order.getFenceNumber();
            totalDispatchNum += order.getDispatchNum();
        }
        orderNumber = orderList.size();
    }
}
