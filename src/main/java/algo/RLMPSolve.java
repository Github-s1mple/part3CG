package algo;

import impl.*;
import com.gurobi.gurobi.GRB;
import com.gurobi.gurobi.GRBConstr;
import com.gurobi.gurobi.GRBEnv;
import com.gurobi.gurobi.GRBException;
import com.gurobi.gurobi.GRBLinExpr;
import com.gurobi.gurobi.GRBModel;
import com.gurobi.gurobi.GRBVar;
import lombok.Setter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Collections;

public class RLMPSolve {
    // 输入参数：列生成的最终结果（所有生成的订单）、问题实例
    private final List<Order> finalColumns;
    private final Instance instance;
    private final Fences fences;
    private GRBEnv env;
    private GRBModel finalModel;
    @Setter
    private int timeLimit = 600; // 默认10分钟

    // 输出结果：最优订单、总收益
    private List<Order> optimalOrders;
    private double totalProfit;

    // 预缓存避免重复查询
    private Map<String, Order> idToOrderMap; // 订单ID→Order对象
    private Map<Integer, List<Order>> fenceToOrdersMap; // 围栏索引→关联订单列表
    private Map<String, List<Order>> carrierToOrdersMap; // 载具索引→关联订单列表

    public RLMPSolve(List<Order> finalColumns, Instance instance) {
        this.finalColumns = finalColumns;
        this.instance = instance;
        this.fences = instance.getFences();
        this.optimalOrders = new ArrayList<>();

        initPreCache();
    }

    /**
     * 预缓存关联数据：避免循环中重复查询
     */
    private void initPreCache() {
        // 1. 订单ID→Order对象映射
        idToOrderMap = new HashMap<>(finalColumns.size());
        for (Order order : finalColumns) {
            String orderId = String.valueOf(order.getOrderId());
            idToOrderMap.putIfAbsent(orderId, order);
        }

        // 2. 围栏索引→关联订单列表（只保留有负载的订单）
        fenceToOrdersMap = new HashMap<>();
        for (Order order : finalColumns) {
            HashMap<Integer, Double> loads = order.getLoads();
            for (Integer fenceIndex : loads.keySet()) {
                fenceToOrdersMap.computeIfAbsent(fenceIndex, k -> new ArrayList<>()).add(order);
            }
        }

        // 3. 载具索引→关联订单列表
        carrierToOrdersMap = new HashMap<>();
        for (Order order : finalColumns) {
            Carrier carrier = order.getCarrier();
            if (carrier != null) {
                String carrierIndex = String.valueOf(carrier.getIndex());
                carrierToOrdersMap.computeIfAbsent(carrierIndex, k -> new ArrayList<>()).add(order);
            }
        }
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

        finalModel.set(GRB.DoubleParam.TimeLimit, timeLimit);
        finalModel.set(GRB.DoubleParam.MIPGap, 0.01);
        // 设置目标函数为最大化
        finalModel.set(GRB.IntAttr.ModelSense, GRB.MAXIMIZE);
    }

    /**
     * 构建最终模型：添加所有列生成的订单变量 + 完整约束（优化后）
     */
    private void buildFinalModel() throws GRBException {
        // 缓存：订单ID→变量映射
        HashMap<String, GRBVar> orderVarMap = new HashMap<>(idToOrderMap.size());

        // 1. 添加所有订单变量
        for (Order order : finalColumns) {
            String orderId = String.valueOf(order.getOrderId());
            if (orderVarMap.containsKey(orderId)) continue;

            // 变量：下界0、上界1、目标系数=订单收益
            GRBVar var = finalModel.addVar(
                    0.0,
                    1.0,
                    order.getOriginalPrice(),
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
            int fenceIndex = fence.getIndex();

            // 只遍历当前围栏的关联订单
            List<Order> relevantOrders = fenceToOrdersMap.getOrDefault(fenceIndex, Collections.emptyList());
            for (Order order : relevantOrders) {
                String orderId = String.valueOf(order.getOrderId());
                GRBVar var = orderVarMap.get(orderId);
                if (var == null) continue;

                // 直接获取预存的负载
                double load = order.getLoads().get(fenceIndex);
                expr.addTerm(load, var);
            }

            // 添加约束
            finalModel.addConstr(expr, GRB.LESS_EQUAL, fence.getDeliverDemand(), constName);
        }
        System.out.println("最终模型添加 " + fences.getFenceList().size() + " 个围栏约束");

        // 3. 添加载具资源约束（sum(x_i * 1) ≤ 载具最大资源）
        for (Carrier carrier : instance.getCarrierList()) {
            String constName = carrier.getConstName();
            GRBLinExpr expr = new GRBLinExpr();
            String carrierIndex = String.valueOf(carrier.getIndex());

            // 只遍历当前载具的关联订单
            List<Order> relevantOrders = carrierToOrdersMap.getOrDefault(carrierIndex, Collections.emptyList());
            for (Order order : relevantOrders) {
                String orderId = String.valueOf(order.getOrderId());
                GRBVar var = orderVarMap.get(orderId);
                if (var == null) continue;

                // 订单已绑定当前载具，直接添加系数1.0
                expr.addTerm(1.0, var);
            }

            // 添加约束
            finalModel.addConstr(expr, GRB.LESS_EQUAL, carrier.getMaxUseTimes(), constName);
        }
        System.out.println("最终模型添加 " + instance.getCarrierList().size() + " 个载具约束");

        // 4. 设置目标函数
        GRBLinExpr objExpr = new GRBLinExpr();
        for (Map.Entry<String, GRBVar> entry : orderVarMap.entrySet()) {
            String orderId = entry.getKey();
            Order order = idToOrderMap.get(orderId);
            if (order != null) {
                objExpr.addTerm(order.getOriginalPrice(), entry.getValue());
            }
        }
        finalModel.setObjective(objExpr, GRB.MAXIMIZE);

        // 更新模型使变量和约束生效
        finalModel.update();
    }

    /**
     * 求解最终模型
     */
    private void optimizeModel() throws GRBException {
        System.out.println("\n开始求解最终主问题（时间限制：" + timeLimit + "秒）...");
        finalModel.optimize();

        // 检查求解状态（重点处理时间限制）
        int status = finalModel.get(GRB.IntAttr.Status);
        switch (status) {
            case GRB.Status.OPTIMAL:
                System.out.println("求解状态：找到最优解");
                break;
            case GRB.Status.SUBOPTIMAL:
                System.out.println("求解状态：找到次优解（可能因时间限制）");
                break;
            case GRB.Status.TIME_LIMIT:
                System.out.println("求解状态：达到时间限制，返回当前最佳解");
                break;
            case GRB.Status.INFEASIBLE:
                throw new GRBException("模型不可行", status);
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
        System.out.println("总列数：" + finalColumns.size());

        // 2. 解析最优订单（变量值>1e-6视为选中）
        double epsilon = 1e-6;
        for (GRBVar var : finalModel.getVars()) {
            String varName = var.get(GRB.StringAttr.VarName);
            String orderId = varName.replace("Final_Order_", "");
            double varValue = var.get(GRB.DoubleAttr.X);

            if (varValue > epsilon) {
                Order order = idToOrderMap.get(orderId);
                if (order != null) {
                    optimalOrders.add(order);
                }
            }
        }
        System.out.println("选中的最优路径数：" + optimalOrders.size());
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

        // 验证载具约束
        for (Carrier carrier : instance.getCarrierList()) {
            String constName = carrier.getConstName();
            GRBConstr constr = finalModel.getConstrByName(constName);
            double slack = constr.get(GRB.DoubleAttr.Slack);
            double maxResource = carrier.getMaxUseTimes();
            double usedResource = maxResource - slack;
            System.out.println("载具[" + constName + "]：实际占用=" + String.format("%.2f", usedResource) + "，最大资源=" + maxResource + "，剩余资源=" + String.format("%.2f", slack));
        }

        // 验证载具距离约束（添加到verifyConstraints()方法中）
        for (Carrier carrier : instance.getCarrierList()) {
            String distanceConstName = carrier.getConstName() + "_distance";
            GRBConstr distanceConstr = finalModel.getConstrByName(distanceConstName);
            if (distanceConstr == null) continue;

            double slack = distanceConstr.get(GRB.DoubleAttr.Slack); // 松弛量=最大距离-实际距离
            double maxDistance = carrier.getMaxDistance();
            double usedDistance = maxDistance - slack;
            System.out.println("载具[" + carrier.getConstName() + "]距离：实际使用=" + String.format("%.2f", usedDistance) + "，最大允许=" + maxDistance + "，剩余=" + String.format("%.2f", slack));
        }
    }

    /**
     * 释放Gurobi资源
     */
    private void releaseResource() throws GRBException {
        finalModel.dispose();
        env.dispose();
    }
}