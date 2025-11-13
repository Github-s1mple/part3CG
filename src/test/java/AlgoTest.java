import Utils.ResultProcess;
import algo.CGSolve;
import baseinfo.Constants;
import impl.Instance;
import impl.Order;
import impl.Orders;

import java.util.List;

public class AlgoTest {
    public static void main(String[] args) {
        Constants.ALGO_MODE = "1";
        Instance instance = new Instance();
        CGSolve cgSolve = new CGSolve();
        List<Order> optimalOrders = cgSolve.solve(instance);
        Orders orders = new Orders(optimalOrders);
        ResultProcess resultProcess = new ResultProcess(orders);
        resultProcess.showOrderDetail();
    }
}
