import algo.GurobiSolve;
import com.gurobi.gurobi.GRBException;
import impl.Instance;

public class BaselineTest {
    public static void main(String[] args) {
        try {
            // 1. 初始化问题实例（根据你的Instance构造逻辑调整）
            Instance instance = new Instance();
            // （此处省略instance的初始化代码，需根据实际情况补充）

            // 2. 创建求解器并初始化
            GurobiSolve solver = new GurobiSolve(instance);
            solver.setOutputFlag(true);  // 启用日志输出

            // 3. 构建模型
            solver.createVariables();
            solver.setObjectiveFunction();
            solver.addConstraints();

            // 4. 求解并输出结果
            solver.solve();

        } catch (GRBException e) {
            System.err.println("Gurobi错误：" + e.getMessage());
            e.printStackTrace();
        }
    }
}
