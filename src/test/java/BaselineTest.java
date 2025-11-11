import algo.GurobiSolve;
import com.gurobi.gurobi.GRBException;
import impl.Instance;
import impl.Order;

import java.util.List;

public class BaselineTest {
    public static void main(String[] args) {
        try {
            // 1. 初始化问题实例（根据你的Instance构造逻辑调整）
            Instance instance = new Instance();
            // （此处省略instance的初始化代码，需根据实际情况补充）

            // 2. 创建求解器并初始化

            GurobiSolve solver = new GurobiSolve(instance);
            solver.defineVariables(); // 定义变量
            solver.setObjective(); // 定义目标函数
            solver.addCoreConstraints(); // 添加约束
            List<Order> optimalOrders = solver.solve(); // 求解并输出完整结果
        } catch (GRBException e) {
            System.err.println("Gurobi错误：" + e.getMessage());
            e.printStackTrace();
        }
    }
}
