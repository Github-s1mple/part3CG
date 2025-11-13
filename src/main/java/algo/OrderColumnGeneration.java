package algo;

import Utils.CommonUtils;
import Utils.GurobiUtils;
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
import java.util.Map;
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
    HashMap<String, GRBVar> RLMPVariables;  // 订单ID → 路径变量（x_r）
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
        this.bidLabeling.setOrderLimit(Constants.ITERATION_COLUMN_NUM);

        // 初始化环境和模型
        this.env = new GRBEnv();
        this.RLMPSolver = new GRBModel(env);

        // 设置模型参数（收益型场景：默认最大化目标，无需额外设置）
        this.RLMPSolver.set(GRB.IntParam.OutputFlag, 0); // 关闭Gurobi默认日志
        this.RLMPSolver.set(GRB.DoubleParam.FeasibilityTol, 1e-5);
        this.RLMPSolver.set(GRB.IntParam.Presolve, 1); // 启用预处理，提升求解效率

        // 初始化空约束框架（围栏+承运人）
        initializeEmptyConstraints();

        // 初始化对偶值（避免后续空指针，初始设为0）
        initDualValues();
        this.bidLabeling.setDualsOfRLMP(dualsOfRLMP);
    }


    /**
     * 初始化空约束框架（仅保留2类约束）
     * 1. 围栏容量约束：sum(x_r * load_{r,f}) ≤ 围栏f的最大容量
     * 2. 承运人名额约束：sum(x_r * 1) ≤ 1（每个载具最终仅选1条路径）
     */
    private void initializeEmptyConstraints() throws GRBException {
        // 1. 围栏容量约束（保留不变）
        for (Fence fence : fences.getFenceList()) {
            String constName = fence.getConstName();
            GRBLinExpr emptyExpr = new GRBLinExpr(); // 空表达式（无变量）
            GRBConstr constr = RLMPSolver.addConstr(
                    emptyExpr, GRB.LESS_EQUAL, fence.getDeliverDemand(), constName
            );
            constraintsMap.put(constName, constr);
            if (outputFlag) {
                System.out.println("初始化空约束：围栏容量约束-" + constName + "，最大容量=" + fence.getDeliverDemand());
            }
        }

        // 2. 承运人名额约束（保留不变，已设置maxUseTimes=1）
        for (Carrier carrier : instance.getCarrierList()) {
            String constName = carrier.getConstName();
            GRBLinExpr emptyExpr = new GRBLinExpr(); // 空表达式（无变量）
            GRBConstr constr = RLMPSolver.addConstr(
                    emptyExpr, GRB.LESS_EQUAL, carrier.getMaxUseTimes(), constName
            );
            constraintsMap.put(constName, constr);
            if (outputFlag) {
                System.out.println("初始化空约束：承运人名额约束-" + constName + "，最大使用次数=" + carrier.getMaxUseTimes());
            }
        }
    }


    /**
     * 初始化对偶值（围栏+承运人资源+承运人距离，初始为0）
     */
    private void initDualValues() {
        // 1. 围栏约束对偶值（保留不变）
        for (Fence fence : fences.getFenceList()) {
            this.dualsOfRLMP.put(fence.getConstName(), 0.0);
        }

        // 2. 承运人名额约束对偶值（保留不变）
        for (Carrier carrier : instance.getCarrierList()) {
            this.dualsOfRLMP.put(carrier.getConstName(), 0.0);
        }
    }


    /**
     * 列生成主迭代逻辑
     */
    public List<Order> solve() throws GRBException {
        this.startTime = CommonUtils.currentTimeInSecond();
        this.iterationCnt = 0;
        List<Order> allOrders = new ArrayList<>();

        // 总时间限制
        int totalTimeLimit = Constants.ITERATION_TIME_LIMIT;

        // 循环条件：剩余时间>0 且 未超过最大列数
        while (true) {
            // 1. 检查是否已超时或达到最大列数，提前退出
            int elapsedTime = CommonUtils.currentTimeInSecond() - startTime;
            int remainingTime = totalTimeLimit - elapsedTime;
            if (remainingTime <= 0) {
                System.out.println("总时间已超限（" + elapsedTime + "/" + totalTimeLimit + "秒），退出迭代");
                break;
            }
            if (allOrders.size() >= Constants.MAX_RLMP_COLUMNS) {
                System.out.println("路径数超过最大限制" + Constants.MAX_RLMP_COLUMNS + "，退出迭代");
                break;
            }

            iterationCnt++;
            int iterStartTime = CommonUtils.currentTimeInSecond();

            // 2. 子问题生成新路径
            List<Order> newOrders;
            try {
                newOrders = generateOrders();
            } catch (Exception e) {
                System.out.println("迭代" + iterationCnt + "生成路径失败：" + e.getMessage());
                break;
            }
            if (newOrders.isEmpty()) {
                System.out.println("迭代" + iterationCnt + "：无新路径生成，退出迭代");
                break;
            }

            // 4. 添加新列到主问题
            addRLMPColumns(newOrders);
            allOrders.addAll(newOrders);

            // 5. 求解主问题并更新对偶值（耗时操作，建议增加超时检查）
            solveRLMPAndUpdateDuals();

            // 6. 检查主问题后是否超时
            if (CommonUtils.currentTimeInSecond() - startTime >= totalTimeLimit) {
                System.out.println("迭代" + iterationCnt + "：主问题求解超时");
                break;
            }

            // 7. 输出迭代信息
            if (outputFlag) {
                displayIterationInformation();
            }

            System.out.println("当前池中列数：" + allOrders.size() + "累计耗时：" + (CommonUtils.currentTimeInSecond() - startTime) + "秒");
        }

        // 释放资源
        env.dispose();
        System.out.println("进入RMP的总列数：" + allOrders.size());
        return allOrders;
    }


    /**
     * 调用子问题（标签算法）生成新路径
     */
    private List<Order> generateOrders() {
        this.bidLabeling.setTimeLimit(this.getIterationTimeLimitLeft());
        return this.bidLabeling.solve(dualsOfRLMP); // 传入对偶值用于定价
    }


    /**
     * 输出迭代关键信息
     */
    private void displayIterationInformation() throws GRBException {
        if (RLMPSolver.get(GRB.IntAttr.SolCount) == 0) {
            System.out.println("迭代" + iterationCnt + "：主问题无可行解");
            return;
        }
        double totalProfit = RLMPSolver.get(GRB.DoubleAttr.ObjVal); // 总收益
        double bestPathProfit = bidLabeling.getBestObj(); // 子问题生成的最优路径收益
        int usedTime = getTimeSinceStartTime();
        System.out.printf("迭代%d：总收益=%.2f，路径总数=%d，最优新路径收益=%.2f，已耗时=%ds，剩余时间=%ds%n",
                iterationCnt, totalProfit, RLMPVariables.size(), bestPathProfit, usedTime, getIterationTimeLimitLeft());
    }


    /**
     * 计算剩余迭代时间
     */
    private int getIterationTimeLimitLeft() {
        return Math.max(0, iterationTimeLimit - getTimeSinceStartTime()); // 避免负时间
    }


    /**
     * 计算从迭代开始到现在的耗时
     */
    private int getTimeSinceStartTime() {
        return CommonUtils.currentTimeInSecond() - startTime;
    }


    /**
     * 添加新列（路径变量）到主问题
     * 核心：创建路径变量 + 更新约束系数（不重建约束）
     */
    private void addRLMPColumns(List<Order> newOrders) throws GRBException {
        int addedCount = 0;
        for (Order order : newOrders) {
            String orderId = String.valueOf(order.getOrderId());

            // 1. 跳过已存在的路径（原有代码不变）
            if (RLMPVariables.containsKey(orderId)) {
                if (outputFlag) {
                    System.out.println("路径" + orderId + "已存在，跳过");
                }
                continue;
            }

            // 2. 创建路径变量（原有代码不变）
            double pathProfit = order.getPrice(); // 目标函数系数=路径收益（最大化）
            GRBVar pathVar = RLMPSolver.addVar(
                    0.0,                // 下界：不选该路径
                    1.0,                // 上界：仅允许一次访问
                    pathProfit,         // 目标系数：路径收益
                    GRB.CONTINUOUS,     // 列生成松弛：连续变量；整数解时改GRB.BINARY
                    "path_" + orderId   // 变量名：明确为路径变量，便于调试
            );

            // 3. 缓存变量和订单映射
            RLMPVariables.put(orderId, pathVar);
            orderIdMap.put(orderId, order);
            addedCount++;

            // 4. 更新围栏约束系数（保留不变）
            updateFenceConstraintCoeff(order, pathVar);

            // 5. 更新承运人名额约束系数（保留不变）
            updateCarrierConstraintCoeff(order, pathVar);

            if (outputFlag) {
                System.out.printf("添加新路径：ID=%s，收益=%.2f，负载围栏数=%d，承运人=%s，距离=%.2f%n",
                        orderId, pathProfit, order.getLoads().size(),
                        (order.getCarrier() != null ? order.getCarrier().getIndex() : "无"),
                        order.getDistance());
            }
        }

        // 7. 批量更新模型（生效所有变量和系数变更）
        RLMPSolver.update();
        System.out.printf("当前总路径数：%d%n", RLMPVariables.size());
    }


    /**
     * 更新围栏约束系数
     */
    private void updateFenceConstraintCoeff(Order order, GRBVar orderVar) throws GRBException {
        String orderId = String.valueOf(order.getOrderId());
        HashMap<Integer, Double> orderLoads = order.getLoads();
        if (orderLoads == null || orderLoads.isEmpty()) {
            if (outputFlag) {
                System.out.println("路径" + orderId + "无围栏负载，跳过围栏约束更新");
            }
            return;
        }

        for (Map.Entry<Integer, Double> loadEntry : orderLoads.entrySet()) {
            int fenceIndex = loadEntry.getKey();
            double load = loadEntry.getValue();
            Fence fence = fences.getFence(fenceIndex);

            if (fence == null) {
                System.out.printf("警告：路径%s涉及的围栏%d不存在，跳过该围栏约束%n", orderId, fenceIndex);
                continue;
            }

            String constName = fence.getConstName();
            GRBConstr constr = constraintsMap.get(constName);

            if (constr == null) {
                System.out.printf("警告：围栏约束%s未初始化，自动补全空约束%n", constName);
                GRBLinExpr emptyExpr = new GRBLinExpr();
                constr = RLMPSolver.addConstr(emptyExpr, GRB.LESS_EQUAL, fence.getDeliverDemand(), constName);
                constraintsMap.put(constName, constr);
            }

            // 通过模型设置约束中变量的系数
            RLMPSolver.chgCoeff(constr, orderVar, load);

            if (outputFlag) {
                System.out.printf("路径%s：围栏%d 负载=%.2f，已添加到约束%s%n",
                        orderId, fenceIndex, load, constName);
            }
        }
    }


    /**
     * 更新承运人约束系数
     */
    private void updateCarrierConstraintCoeff(Order order, GRBVar orderVar) throws GRBException {
        String orderId = String.valueOf(order.getOrderId());
        Carrier carrier = order.getCarrier();

        if (carrier == null) {
            System.out.printf("警告：路径%s未绑定承运人，跳过承运人约束更新%n", orderId);
            return;
        }

        String constName = carrier.getConstName();
        GRBConstr constr = constraintsMap.get(constName);

        if (constr == null) {
            System.out.printf("警告：承运人约束%s未初始化，自动补全空约束%n", constName);
            GRBLinExpr emptyExpr = new GRBLinExpr();
            constr = RLMPSolver.addConstr(emptyExpr, GRB.LESS_EQUAL, carrier.getMaxUseTimes(), constName);
            constraintsMap.put(constName, constr);
        }

        // 通过模型设置约束中变量的系数
        RLMPSolver.chgCoeff(constr, orderVar, 1.0);  // 核心修改：用模型对象调用chgCoeff

        if (outputFlag) {
            System.out.printf("路径%s：绑定承运人%d，已添加到约束%s%n",
                    orderId, carrier.getIndex(), constName);
        }
    }

    private void solveRLMPAndUpdateDuals() {
        try {
            // 设置求解时间限制
            int timeLimit = getIterationTimeLimitLeft();
            RLMPSolver.set(GRB.DoubleParam.TimeLimit, timeLimit);
            if (outputFlag) {
                System.out.println("迭代" + iterationCnt + "：主问题求解时间限制=" + timeLimit + "秒");
            }

            // 求解主问题
            RLMPSolver.optimize();
            int status = RLMPSolver.get(GRB.IntAttr.Status);
            String statusDesc = GurobiUtils.getStatusDescription(status);
            if (outputFlag) {
                System.out.println("迭代" + iterationCnt + "：主问题状态=" + statusDesc);
            }

            // 仅在最优/次优状态下更新对偶值
            if (status != GRB.Status.OPTIMAL && status != GRB.Status.SUBOPTIMAL) {
                System.out.println("迭代" + iterationCnt + "：无有效解，不更新对偶值");
                return;
            }

            // 处理次优解
            if (status == GRB.Status.SUBOPTIMAL && outputFlag) {
                double gap = RLMPSolver.get(GRB.DoubleAttr.MIPGap);
                System.out.printf("迭代%d：次优解，偏差=%.4f%n", iterationCnt, gap);
            }

            // 提取对偶值到dualsOfRLMP（供下一轮标签算法使用）
            extractDualValuesToMap();

        } catch (GRBException e) {
            System.err.printf("迭代%d：主问题求解异常 - 错误码=%d，信息=%s%n",
                    iterationCnt, e.getErrorCode(), e.getMessage());
        }
    }

    /**
     * 提取对偶值到dualsOfRLMP（供标签算法使用）
     */
    private void extractDualValuesToMap() throws GRBException {
        dualsOfRLMP.clear();
        int successCnt = 0, failCnt = 0;

        for (Map.Entry<String, GRBConstr> entry : constraintsMap.entrySet()) {
            String constName = entry.getKey();
            GRBConstr constr = entry.getValue();
            try {
                // 提取约束的对偶值（Pi属性）
                double dualValue = constr.get(GRB.DoubleAttr.Pi);
                dualsOfRLMP.put(constName, dualValue);
                successCnt++;
            } catch (GRBException e) {
                failCnt++;
                if (outputFlag) {
                    System.out.printf("提取约束%s对偶值失败：%s%n", constName, e.getMessage());
                }
            }
        }

        if (outputFlag) {
            System.out.printf("迭代%d：对偶值提取完成（成功%d，失败%d）%n",
                    iterationCnt, successCnt, failCnt);
        }
    }
}