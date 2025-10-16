package impl;

import java.util.ArrayList;

public class Orders {
    private int orderNumber;
    private ArrayList<Order> orderList;
    private double deliverPrice;

    public Orders() {
        this.orderNumber = 0;
        this.orderList = new ArrayList<>();
        this.deliverPrice = 0.0;
    }

    public void calPrice() {
        for (Order order : orderList) {
            this.deliverPrice += order.getPrice();
        }
    }

    public ArrayList<Order> getOrderList() {
        return orderList;
    }
}
