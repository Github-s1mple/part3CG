package algo;

import impl.Instance;
import impl.Order;

import java.util.HashMap;
import java.util.List;
import com.gurobi.gurobi.GRBException;

public class OriginalSolve {
    public void solve(Instance instance) {
        try {
            // 1. 初始化列生成算法
            OrderColumnGeneration cg = new OrderColumnGeneration(instance);
            cg.setOutputFlag(true);
            List<Order> allColumns = cg.solve(); // 列生成所有列

            // 2. 调用最终主问题求解器（注意类名与之前定义一致，这里假设是RLMPFinalSolver）
            RLMPSolve finalSolver = new RLMPSolve(allColumns, instance);
            List<Order> optimalOrders = finalSolver.solveRLMP(); // 最优订单组合

            // 输出结果（示例）
            System.out.println("选中的最优订单数：" + optimalOrders.size());

        } catch (GRBException e) {
            // 处理Gurobi相关异常
            System.err.println("Gurobi求解异常：错误码=" + e.getErrorCode() + "，信息=" + e.getMessage());
            e.printStackTrace();
        } catch (Exception e) {
            // 处理其他可能的异常
            System.err.println("其他异常：" + e.getMessage());
            e.printStackTrace();
        }
    }
}