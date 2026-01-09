package algoCG;

import baseinfo.Constants;
import impl.Instance;
import impl.Order;

import java.util.List;
import com.gurobi.gurobi.GRBException;
import lombok.Getter;

public class CGSolve {
    @Getter
    private RLMPSolve finalSolver;
    private final Instance instance;
    public CGSolve(Instance instance){
        this.instance = instance;
    }
    public List<Order> solve() {
        try {
            // 1. 初始化列生成算法
            OrderColumnGeneration cg = new OrderColumnGeneration(instance);
            //cg.setOutputFlag(true);
            System.out.println("仓库编号" + cg.getDepots().getDepotIndexes());
            List<Order> allColumns = cg.solve(); // 生成的所有列
            // 2. 调用最终主问题求解器
            finalSolver = new RLMPSolve(allColumns, instance);
            finalSolver.setTimeLimit((int) (Constants.ITERATION_TIME_LIMIT * Constants.RMPSOLVE_PROPORTION));
            // 最优订单组合
            return finalSolver.solveRLMP();

        } catch (GRBException e) {
            // 处理Gurobi相关异常
            System.err.println("Gurobi求解异常：错误码=" + e.getErrorCode() + "，信息=" + e.getMessage());
            e.printStackTrace();
        } catch (Exception e) {
            // 处理其他可能的异常
            System.err.println("其他异常：" + e.getMessage());
            e.printStackTrace();
        }
        return null;
    }
}