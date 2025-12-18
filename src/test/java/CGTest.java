import algoCG.ResultProcess;
import algoCG.CGSolve;
import baseinfo.Constants;
import impl.Instance;
import impl.Order;
import impl.Orders;

import java.util.List;

public class CGTest {
    public static void main(String[] args) {
        Constants.ALGO_MODE = "CG";
        Instance instance = new Instance();
        CGSolve cgSolve = new CGSolve(instance);
        List<Order> optimalOrders = cgSolve.solve();
        Orders orders = new Orders(optimalOrders);
        ResultProcess resultProcess = new ResultProcess(orders);
        resultProcess.showOrderDetail();
    }
}
