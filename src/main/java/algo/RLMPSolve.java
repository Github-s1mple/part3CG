package algo;

import impl.*;
import com.gurobi.gurobi.GRB;
import com.gurobi.gurobi.GRBConstr;
import com.gurobi.gurobi.GRBEnv;
import com.gurobi.gurobi.GRBException;
import com.gurobi.gurobi.GRBLinExpr;
import com.gurobi.gurobi.GRBModel;
import com.gurobi.gurobi.GRBVar;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class RLMPSolve {
    // 输入参数：列生成的最终结果（所有生成的订单）、问题实例
    private final List<Order> finalColumns;
    private final Instance instance;
    private final Fences fences;
    private GRBEnv env;
    private GRBModel finalModel;

    // 输出结果：最优订单、总收益、约束对偶值
    private List<Order> optimalOrders;
    private double totalProfit;
    private HashMap<String, Double> finalDuals;

    public RLMPSolve(List<Order> finalColumns, Instance instance) {
        this.finalColumns = finalColumns;
        this.instance = instance;
        this.fences = instance.getFences();
        this.optimalOrders = new ArrayList<>();
        this.finalDuals = new HashMap<>();
    }

    /**
     * 核心方法：求解最终主问题
     * @return 最优订单组合（含选择比例）
     */
    public List<Order> solveRLMP() throws GRBException {
        // 1. 初始化模型环境
        initModel();

        // 2. 构建最终模型（变量+约束）
        buildFinalModel();

        // 3. 求解模型
        optimizeModel();

        // 4. 解析结果（最优订单、总收益、对偶值）
        parseResult();

        // 5. 释放资源
        releaseResource();

        return optimalOrders;
    }

    /**
     * 初始化Gurobi环境和模型
     */
    private void initModel() throws GRBException {
        env = new GRBEnv();
        finalModel = new GRBModel(env);

        // 设置求解参数（最终求解用更严格的精度）
        finalModel.set(GRB.IntParam.OutputFlag, 1); // 显示求解日志
        finalModel.set(GRB.DoubleParam.FeasibilityTol, 1e-6);
        finalModel.set(GRB.DoubleParam.OptimalityTol, 1e-6);
        finalModel.set(GRB.IntParam.Presolve, 2); // 启用强预处理
        finalModel.set(GRB.StringAttr.ModelName, "Final_RLMP");

        // 设置目标函数为最大化
        finalModel.set(GRB.IntAttr.ModelSense, GRB.MAXIMIZE);
    }


    /**
     * 构建最终模型：添加所有列生成的订单变量 + 完整约束
     */
    private void buildFinalModel() throws GRBException {
        // 缓存：订单ID→变量映射
        HashMap<String, GRBVar> orderVarMap = new HashMap<>();

        // 1. 添加所有订单变量（与列生成逻辑一致）
        for (Order order : finalColumns) {
            String orderId = String.valueOf(order.getOrderId());
            if (orderVarMap.containsKey(orderId)) continue;

            // 变量：下界0、上界1、目标系数=订单收益（getPrice()）
            GRBVar var = finalModel.addVar(
                    0.0,
                    1.0,
                    order.getPrice(),
                    GRB.BINARY,
                    "Final_Order_" + orderId
            );
            orderVarMap.put(orderId, var);
        }
        System.out.println("最终模型添加 " + orderVarMap.size() + " 个订单变量");

        // 2. 添加围栏容量约束（sum(x_i * load_{i,f}) ≤ 围栏最大容量）
        for (Fence fence : fences.getFenceList()) {
            String constName = fence.getConstName();
            GRBLinExpr expr = new GRBLinExpr();

            for (Map.Entry<String, GRBVar> entry : orderVarMap.entrySet()) {
                String orderId = entry.getKey();
                GRBVar var = entry.getValue();
                Order order = findOrderById(orderId);
                if (order == null) continue;

                // 从订单loads中获取该围栏的负载
                HashMap<Integer, Double> loads = order.getLoads();
                int fenceIndex = fence.getIndex();
                if (loads.containsKey(fenceIndex)) {
                    double load = loads.get(fenceIndex);
                    expr.addTerm(load, var);
                }
            }

            // 添加约束（右边界=围栏最大容量）
            finalModel.addConstr(expr, GRB.LESS_EQUAL, fence.getDeliverDemand(), constName);
        }
        System.out.println("最终模型添加 " + fences.getFenceList().size() + " 个围栏约束");

        // 3. 添加承运人资源约束（sum(x_i * 1) ≤ 承运人最大资源）
        for (Carrier carrier : instance.getCarrierList()) {
            String constName = carrier.getConstName();
            GRBLinExpr expr = new GRBLinExpr();

            for (Map.Entry<String, GRBVar> entry : orderVarMap.entrySet()) {
                GRBVar var = entry.getValue();
                Order order = findOrderById(entry.getKey());
                if (order == null) continue;

                // 订单绑定当前承运人则系数=1
                if (order.getCarrier() != null && order.getCarrier().getIndex().equals(carrier.getIndex())) {
                    expr.addTerm(1.0, var);
                }
            }

            // 添加约束（右边界=承运人最大资源）
            finalModel.addConstr(expr, GRB.LESS_EQUAL, carrier.getMaxUseTimes(), constName);
        }
        System.out.println("最终模型添加 " + instance.getCarrierList().size() + " 个载具约束");

        GRBLinExpr objExpr = new GRBLinExpr();
        for (Map.Entry<String, GRBVar> entry : orderVarMap.entrySet()) {
            Order order = findOrderById(entry.getKey());
            if (order != null) {
                objExpr.addTerm(order.getPrice(), entry.getValue()); // 变量+系数
            }
        }
        finalModel.setObjective(objExpr, GRB.MAXIMIZE); // 同时设置表达式和方向

        // 更新模型使变量和约束生效
        finalModel.update();
    }

    /**
     * 求解最终模型
     */
    private void optimizeModel() throws GRBException {
        System.out.println("\n开始求解最终主问题...");
        finalModel.optimize();

        // 检查求解状态
        int status = finalModel.get(GRB.IntAttr.Status);
        switch (status) {
            case GRB.Status.OPTIMAL:
                System.out.println("求解状态：找到最优解");
                break;
            case GRB.Status.SUBOPTIMAL:
                System.out.println("求解状态：找到次优解（可能因时间限制）");
                break;
            default:
                throw new GRBException("最终主问题求解失败，状态码：" + status, status);
        }
    }

    /**
     * 解析求解结果
     */
    private void parseResult() throws GRBException {
        // 1. 解析总收益
        totalProfit = finalModel.get(GRB.DoubleAttr.ObjVal);
        System.out.println("\n===== 最终主问题求解结果 =====");
        System.out.println("总收益：" + String.format("%.2f", totalProfit));
        System.out.println("订单总数：" + finalColumns.size());

        // 2. 解析最优订单（变量值>1e-6视为选中）
        double epsilon = 1e-6;
        for (GRBVar var : finalModel.getVars()) {
            String varName = var.get(GRB.StringAttr.VarName);
            String orderId = varName.replace("Final_Order_", "");
            double varValue = var.get(GRB.DoubleAttr.X);

            if (varValue > epsilon) {
                Order order = findOrderById(orderId);
                if (order != null) {
                    optimalOrders.add(order);
                    System.out.println("订单ID：" + orderId + "，选择比例：" + String.format("%.4f", varValue) + "，收益：" + order.getPrice());
                }
            }
        }
        System.out.println("选中的最优订单数：" + optimalOrders.size());
        // 4. 验证约束满足情况
        // verifyConstraints();
    }

    /**
     * 验证约束满足情况
     */
    private void verifyConstraints() throws GRBException {
        System.out.println("\n===== 约束满足情况验证 =====");
        double epsilon = 1e-6;

        // 验证围栏约束
        for (Fence fence : fences.getFenceList()) {
            String constName = fence.getConstName();
            GRBConstr constr = finalModel.getConstrByName(constName);
            double actualLoad = constr.get(GRB.DoubleAttr.Slack); // 松弛变量（=最大容量-实际负载）
            double maxCapacity = fence.getDeliverDemand();
            double usedCapacity = maxCapacity - actualLoad;
            System.out.println("围栏[" + constName + "]：实际占用=" + String.format("%.2f", usedCapacity) + "，最大容量=" + maxCapacity + "，剩余容量=" + String.format("%.2f", actualLoad));
        }

        // 验证承运人约束
        for (Carrier carrier : instance.getCarrierList()) {
            String constName = carrier.getConstName();
            GRBConstr constr = finalModel.getConstrByName(constName);
            double slack = constr.get(GRB.DoubleAttr.Slack);
            double maxResource = carrier.getMaxUseTimes();
            double usedResource = maxResource - slack;
            System.out.println("承运人[" + constName + "]：实际占用=" + String.format("%.2f", usedResource) + "，最大资源=" + maxResource + "，剩余资源=" + String.format("%.2f", slack));
        }

        // 验证承运人距离约束（添加到verifyConstraints()方法中）
        for (Carrier carrier : instance.getCarrierList()) {
            String distanceConstName = carrier.getConstName() + "_distance";
            GRBConstr distanceConstr = finalModel.getConstrByName(distanceConstName);
            if (distanceConstr == null) continue;

            double slack = distanceConstr.get(GRB.DoubleAttr.Slack); // 松弛量=最大距离-实际距离
            double maxDistance = carrier.getMaxDistance();
            double usedDistance = maxDistance - slack;
            System.out.println("承运人[" + carrier.getConstName() + "]距离：实际使用=" + String.format("%.2f", usedDistance) + "，最大允许=" + maxDistance + "，剩余=" + String.format("%.2f", slack));
        }
    }


    /**
     * 释放Gurobi资源
     */
    private void releaseResource() throws GRBException {
        finalModel.dispose();
        env.dispose();
    }


    /**
     * 辅助方法：通过订单ID查找订单
     */
    private Order findOrderById(String orderId) {
        for (Order order : finalColumns) {
            if (String.valueOf(order.getOrderId()).equals(orderId)) {
                return order;
            }
        }
        return null;
    }
}