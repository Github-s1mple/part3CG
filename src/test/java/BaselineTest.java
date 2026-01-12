import Utils.ResultPersistenceUtil;
import algoCG.ResultProcess;
import algoCG.GurobiSolve;
import baseinfo.Constants;
import com.gurobi.gurobi.GRBException;
import impl.*;

import java.util.List;

import static Utils.Initializer.scenarioInitializer;

public class BaselineTest {
    public static void main(String[] args) {
        try {
            // 1. 初始化问题实例
            Constants.ALGO_MODE = "CG";
            List<Scenario> scenarioList = scenarioInitializer();
            Scenarios scenarios = new Scenarios(scenarioList);
            LocationResult firstStageResult = ResultPersistenceUtil.loadFirstStageResult();
            Instance instance = new Instance(firstStageResult, scenarios.getScenarioList().get(0));
            //Instance instance = new Instance();
            // 2. 创建求解器并初始化
            GurobiSolve solver = new GurobiSolve(instance);
            solver.setOutputFlag(false);
            solver.defineVariables(); // 定义变量
            solver.setObjective(); // 定义目标函数
            solver.addCoreConstraints(); // 添加约束
            List<Order> optimalOrders = solver.solve(); // 求解并输出完整结果
            Orders orders = new Orders(optimalOrders);
//            ResultProcess resultProcess = new ResultProcess(orders);
//            resultProcess.showOrderDetail();
        } catch (GRBException e) {
            System.err.println("Gurobi错误：" + e.getMessage());
            e.printStackTrace();
        }
    }
}
