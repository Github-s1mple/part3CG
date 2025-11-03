package algo;

import Utils.CommonUtils;
import impl.*;
import lombok.Getter;
import lombok.Setter;
import baseinfo.Constants;
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

@Setter
@Getter
public class OrderColumnGeneration {
    private final Instance instance;
    private final Fences fences;
    private final Depots depots;
    private Boolean outputFlag = false;
    private Integer iterationTimeLimit = Integer.MAX_VALUE;
    private Integer startTime;
    private Integer iterationCnt;

    final HashMap<String, Double> dualsOfRLMP;
    final GRBModel RLMPSolver;
    final BidLabeling bidLabeling;
    HashMap<String, GRBVar> RLMPVariables;  // 订单ID → 变量
    private GRBEnv env;

    // 缓存订单ID→订单映射（优化查询效率）
    private HashMap<String, Order> orderIdMap;
    // 存储约束对象：约束名称 → 约束对象
    private HashMap<String, GRBConstr> constraintsMap;


    public OrderColumnGeneration(Instance instance) throws GRBException {
        this.instance = instance;
        this.fences = instance.getFences();
        this.depots = instance.getDepots();
        this.dualsOfRLMP = new HashMap<>();
        this.RLMPVariables = new HashMap<>();
        this.orderIdMap = new HashMap<>(); // 初始化订单映射
        this.constraintsMap = new HashMap<>();
        this.bidLabeling = new BidLabeling(this.instance);
        this.bidLabeling.setOutputFlag(false);
        this.bidLabeling.setOrderLimit(Constants.ITERATIONCOLUMNNUM);

        // 初始化环境和模型
        this.env = new GRBEnv();
        this.RLMPSolver = new GRBModel(env);

        // 设置模型参数（收益型场景：默认最大化目标，无需额外设置）
        this.RLMPSolver.set(GRB.IntParam.OutputFlag, 0);
        this.RLMPSolver.set(GRB.DoubleParam.FeasibilityTol, 1e-5);
        this.RLMPSolver.set(GRB.IntParam.Presolve, 1);

        // 初始化约束（空约束框架）
        initializeEmptyConstraints();

        // 初始化对偶值
        for (Fence fence : fences.getFenceList()) {
            this.dualsOfRLMP.put(fence.getConstName(), 0.0);
        }

        for (Depot depot : depots.getDepotList()) {
            this.dualsOfRLMP.put(depot.getConstName(), 0.0);
        }

        for (Carrier carrier : instance.getCarrierList()) {
            this.dualsOfRLMP.put(carrier.getConstName(), 0.0);
        }
    }


    /**
     * 初始化空约束（围栏容量约束+承运人资源约束）
     */
    private void initializeEmptyConstraints() throws GRBException {
        // 1. 围栏容量约束：sum(x_i * load_{i,f}) ≤ 围栏f的最大容量（fence.getDeliverDemand()）
        for (Fence fence : fences.getFenceList()) {
            String constName = fence.getConstName();
            GRBLinExpr emptyExpr = new GRBLinExpr();
            GRBConstr constr = RLMPSolver.addConstr(
                    emptyExpr, GRB.LESS_EQUAL, fence.getDeliverDemand(), constName
            );
            constraintsMap.put(constName, constr);
        }

        // 2. 承运人资源约束：sum(x_i * 1) ≤ 承运人k的最大资源（每个订单消耗1个承运人）
        for (Carrier carrier : instance.getCarrierList()) {
            String constName = carrier.getConstName();
            GRBLinExpr emptyExpr = new GRBLinExpr();
            GRBConstr constr = RLMPSolver.addConstr(
                    emptyExpr, GRB.LESS_EQUAL, carrier.getMaxUseTimes(), constName
            );
            constraintsMap.put(constName, constr);
        }
    }


    public List<Order> solve() throws GRBException {
        this.startTime = CommonUtils.currentTimeInSecond();
        this.iterationCnt = 0;
        int lastIterationTime = 0;
        List<Order> orders = new ArrayList<>();

        while (getIterationTimeLimitLeft() > lastIterationTime && orders.size() <= Constants.MAXRMLPCOLUMNS) {
            int sTime = CommonUtils.currentTimeInSecond();
            List<Order> newOrders = this.generateOrders();
            orders.addAll(newOrders);

            if (this.outputFlag) {
                displayIterationInformation();
            }
            this.iterationCnt++;

            if (newOrders.size() == 0) {
                System.out.println("无新订单生成，退出迭代");
                break;
            }

            // 添加新列（适配新业务逻辑）
            addRLMPColumns(newOrders);
            // 求解并更新对偶值
            solveRLMPAndUpdateDuals();

            lastIterationTime = CommonUtils.currentTimeInSecond() - sTime;
            if (getIterationTimeLimitLeft() < lastIterationTime) {
                System.out.println("超时退出迭代");
            }
            if (orders.size() > Constants.MAXRMLPCOLUMNS) {
                System.out.println("超过最大列数退出迭代");
            }
        }

        // 释放资源
        RLMPSolver.dispose();
        env.dispose();
        return orders;
    }

    private List<Order> generateOrders() {
        this.bidLabeling.setTimeLimit(this.getIterationTimeLimitLeft());
        return this.bidLabeling.solve(dualsOfRLMP);
    }

    private void displayIterationInformation() throws GRBException {
        if (RLMPSolver.get(GRB.IntAttr.SolCount) == 0) {
            System.out.println("CG Iteration " + iterationCnt + ": 无可行解");
            return;
        }
        double rlmpObj = RLMPSolver.get(GRB.DoubleAttr.ObjVal);
        System.out.println("CG Iteration " + iterationCnt
                + ": 总收益 = " + rlmpObj  // 收益型场景，显示总收益
                + ", 订单总数 = " + RLMPVariables.size()
                + ", 子问题最优订单收益 = " + bidLabeling.getBestObj()
                + ", 耗时 = " + getTimeSinceStartTime() + "s"
        );
    }


    private int getIterationTimeLimitLeft() {
        return iterationTimeLimit - getTimeSinceStartTime();
    }


    private int getTimeSinceStartTime() {
        return CommonUtils.currentTimeInSecond() - startTime;
    }


    /**
     * 添加新列（适配核心逻辑修改）
     * 1. 订单负载从 order.getLoads() 获取（围栏编号→占用资源量）
     * 2. 承运人消耗系数固定为1
     * 3. 收益型场景：目标系数直接用订单收益
     */
    private void addRLMPColumns(List<Order> newOrders) throws GRBException {
        for (Order order : newOrders) {
            String orderId = String.valueOf(order.getOrderId());
            if (RLMPVariables.containsKey(orderId)) {
                System.out.println("订单已存在，跳过：" + orderId);
                continue;
            }

            // 1. 创建订单变量（收益型场景：目标系数=订单收益）
            double orderProfit = order.getPrice(); // 假设Order有getProfit()获取收益
            GRBVar orderVar = RLMPSolver.addVar(
                    0.0,                // 下界
                    1.0,                // 上界
                    orderProfit,         // 目标系数=订单收益（最大化总收益）
                    GRB.CONTINUOUS,     // 连续变量（列生成经典设置）
                    "Order_" + orderId  // 变量名
            );
            RLMPVariables.put(orderId, orderVar);
            orderIdMap.put(orderId, order); // 缓存订单映射
            System.out.println("添加新订单：" + orderId + "，收益：" + orderProfit);

            // 2. 处理围栏约束（负载从order.getLoads()获取）
            HashMap<Integer, Double> orderLoads = order.getLoads(); // 订单的围栏→负载映射
            for (int fenceIndex : orderLoads.keySet()) {
                Fence fence = fences.getFence(fenceIndex);
                if (fence == null) {
                    System.out.println("警告：围栏索引" + fenceIndex + "不存在，跳过该围栏约束");
                    continue;
                }
                String constName = fence.getConstName();
                // 重建围栏约束（添加当前订单的负载）
                rebuildFenceConstraint(constName, fence, orderVar, order);
            }

            // 3. 处理承运人约束（固定消耗系数=1）
            Carrier carrier = order.getCarrier();
            if (carrier == null) {
                System.out.println("警告：订单" + orderId + "未绑定承运人，跳过承运人约束");
                continue;
            }
            String carrierConstName = carrier.getConstName();
            // 重建承运人约束（消耗系数固定为1）
            rebuildCarrierConstraint(carrierConstName, carrier, orderVar, order);
        }

        // 更新模型使变更生效
        RLMPSolver.update();
        System.out.println("本次添加" + newOrders.size() + "个新订单，当前总订单数：" + RLMPVariables.size());
    }


    /**
     * 重建围栏约束（核心逻辑修改：负载从订单loads获取）
     * 约束逻辑：sum(x_i * load_{i,f}) ≤ 围栏f的最大容量（fence.getDeliverDemand()）
     */
    private void rebuildFenceConstraint(String constName, Fence fence, GRBVar newVar, Order newOrder) throws GRBException {
        // 1. 删除旧约束
        GRBConstr oldConstr = constraintsMap.get(constName);
        if (oldConstr != null) {
            RLMPSolver.remove(oldConstr);
        }

        // 2. 构建新约束表达式
        GRBLinExpr expr = new GRBLinExpr();
        for (String orderId : RLMPVariables.keySet()) {
            GRBVar var = RLMPVariables.get(orderId);
            Order order = orderIdMap.get(orderId);
            HashMap<Integer, Double> loads = order.getLoads();

            // 获取当前订单对该围栏的负载（无负载则系数为0，跳过）
            int fenceIndex = fence.getIndex();
            if (!loads.containsKey(fenceIndex)) {
                continue;
            }
            double load = loads.get(fenceIndex); // 订单对该围栏的占用资源量
            expr.addTerm(load, var); // 添加变量项：x_i * load_{i,f}
        }

        // 3. 添加新约束（右边界=围栏最大容量：fence.getDeliverDemand()）
        GRBConstr newConstr = RLMPSolver.addConstr(
                expr, GRB.LESS_EQUAL, fence.getDeliverDemand(), constName
        );
        constraintsMap.put(constName, newConstr);
        System.out.println("重建围栏约束：" + constName + "，最大容量：" + fence.getDeliverDemand());
    }


    /**
     * 重建承运人约束（核心逻辑修改：消耗系数固定为1）
     * 约束逻辑：sum(x_i * 1) ≤ 承运人最大资源（每个订单消耗1个承运人）
     */
    private void rebuildCarrierConstraint(String constName, Carrier carrier, GRBVar newVar, Order newOrder) throws GRBException {
        // 1. 删除旧约束
        GRBConstr oldConstr = constraintsMap.get(constName);
        if (oldConstr != null) {
            RLMPSolver.remove(oldConstr);
        }

        // 2. 构建新约束表达式（消耗系数固定为1）
        GRBLinExpr expr = new GRBLinExpr();
        for (String orderId : RLMPVariables.keySet()) {
            GRBVar var = RLMPVariables.get(orderId);
            Order order = orderIdMap.get(orderId);

            // 检查订单是否绑定当前承运人（是则系数=1，否则=0）
            if (order.getCarrier() != null && order.getCarrier().getIndex().equals(carrier.getIndex())) {
                expr.addTerm(1.0, var); // 固定消耗系数=1
            }
        }

        // 3. 添加新约束（右边界=承运人最大资源）
        GRBConstr newConstr = RLMPSolver.addConstr(
                expr, GRB.LESS_EQUAL, carrier.getMaxUseTimes(), constName
        );
        constraintsMap.put(constName, newConstr);
        System.out.println("重建承运人约束：" + constName + "，最大资源：" + carrier.getMaxUseTimes());
    }


    private void solveRLMPAndUpdateDuals() throws GRBException {
        int timeLimit = getIterationTimeLimitLeft();
        RLMPSolver.set(GRB.DoubleParam.TimeLimit, timeLimit);

        // 求解模型（收益型场景：最大化目标，Gurobi默认支持）
        RLMPSolver.optimize();

        // 检查求解状态
        int status = RLMPSolver.get(GRB.IntAttr.Status);
        if (status != GRB.Status.OPTIMAL && status != GRB.Status.SUBOPTIMAL) {
            if (outputFlag) {
                System.out.println("主问题无可行解/最优解，状态码：" + status);
            }
            return;
        }

        // 更新对偶值（指导子问题生成更优收益订单）
        for (GRBConstr constr : constraintsMap.values()) {
            String constName = constr.get(GRB.StringAttr.ConstrName);
            double dualValue = constr.get(GRB.DoubleAttr.Pi);
            dualsOfRLMP.put(constName, dualValue);
        }
        System.out.println("迭代" + iterationCnt + "：对偶值更新完成（用于子问题生成高收益订单）");
    }
}