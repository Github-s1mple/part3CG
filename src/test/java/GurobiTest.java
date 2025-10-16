import com.gurobi.gurobi.*;

public class GurobiTest {
    public static void main(String[] args) {
        GRBEnv env = null;
        GRBModel model = null;

        try {
            // 初始化Gurobi环境
            env = new GRBEnv();
            env.set(GRB.IntParam.LogToConsole, 0); // 关闭控制台日志

            // 创建模型
            model = new GRBModel(env);

            // 添加决策变量 x (0 ≤ x ≤ 10)
            GRBVar x = model.addVar(0.0, 10.0, 0.0, GRB.CONTINUOUS, "x");
            model.update(); // 更新模型，使变量生效

            // 设置目标函数：max x
            GRBLinExpr objExpr = new GRBLinExpr();  // 使用GRBLinExpr替代GRBExpr
            objExpr.addTerm(1.0, x); // 目标函数表达式：1.0 * x
            model.setObjective(objExpr, GRB.MAXIMIZE);

            // 创建第一个约束：x ≤ 5
            GRBLinExpr expr1 = new GRBLinExpr();
            expr1.addTerm(1.0, x); // 表达式：1.0 * x
            model.addConstr(expr1, GRB.LESS_EQUAL, 5.0, "c1"); // 添加约束

            // 创建第二个约束：2x ≤ 10
            GRBLinExpr expr2 = new GRBLinExpr();
            expr2.addTerm(2.0, x); // 表达式：2.0 * x
            model.addConstr(expr2, GRB.LESS_EQUAL, 10.0, "c2"); // 添加约束

            // 求解模型
            model.optimize();

            // 输出结果
            if (model.get(GRB.IntAttr.Status) == GRB.OPTIMAL) {
                System.out.println("最优目标值: " + model.get(GRB.DoubleAttr.ObjVal));
                System.out.println("x的最优解: " + x.get(GRB.DoubleAttr.X));
            } else {
                System.out.println("未找到可行解");
            }

        } catch (GRBException e) {
            System.err.println("Gurobi错误代码: " + e.getErrorCode());
            System.err.println("错误信息: " + e.getMessage());
            e.printStackTrace();
        } finally {
            // 释放资源
            if (model != null) {
                model.dispose();
            }
            if (env != null) {
                try {
                    env.dispose();
                } catch (GRBException e) {
                    e.printStackTrace();
                }
            }
        }
    }
}
