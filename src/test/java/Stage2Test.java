import algoCG.GurobiSolve;
import algoCG.ResultProcess;
import com.gurobi.gurobi.GRBException;
import impl.*;
import Stages.FirstStageLocationModel;

import java.io.IOException;
import java.util.List;

public class Stage2Test {
    public static void main(String[] args) throws GRBException, IOException {

        InputData input = new InputData();
        FirstStageLocationModel model = new FirstStageLocationModel(input);
        model.setOutputFlag(true);  // 开启详细输出
        model.defineVariables();    // 定义变量
        model.setObjective();       // 设置目标函数
        model.addCoreConstraints(); // 添加约束
        LocationResult result = model.solve();

        // 2阶段求解
        Instance instance = new Instance(result);
        GurobiSolve solver = new GurobiSolve(instance);
        solver.setOutputFlag(false);
        solver.defineVariables(); // 定义变量
        solver.setObjective(); // 定义目标函数
        solver.addCoreConstraints(); // 添加约束
        List<Order> optimalOrders = solver.solve(); // 求解并输出完整结果
        Orders orders = new Orders(optimalOrders);
        ResultProcess resultProcess = new ResultProcess(orders);
        resultProcess.showOrderDetail();
    }
}
