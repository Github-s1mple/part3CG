package algo;

import Utils.CommonUtils;
import impl.*;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import baseinfo.Constants;
import gurobi.*; // Gurobi 核心类包
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

@Setter
@Getter
@Slf4j // 用于日志输出
public class OrderColumnGeneration {
    final Instance instance; // 算例数据
    final Fences fences;
    public Boolean outputFlag = false; // 是否输出过程信息

    @Setter
    private Integer iterationTimeLimit = Integer.MAX_VALUE; // 迭代环节时间限制
    private Integer startTime; // 算法开始时间

    @Getter
    private Integer iterationCnt; // 记录迭代次数

    final HashMap<String, Double> dualsOfRLMP; // 主问题对偶值
    final GRBModel RLMPSolver; // 松弛主问题求解器（Gurobi 模型）
    final BidLabeling bidLabeling; // 双向标号法
    HashMap<String, GRBVar> RLMPVariables; // Gurobi 变量映射

    public OrderColumnGeneration(Instance instance) throws GRBException {
        this.instance = instance;
        this.fences = instance.getFences();
        this.dualsOfRLMP = new HashMap<>();
        this.RLMPVariables = new HashMap<>();
        this.bidLabeling = new BidLabeling(this.instance);
        this.bidLabeling.setOutputFlag(false);
        this.bidLabeling.setOrderLimit(Constants.ITERATIONCOLUMNNUM);

        // 初始化 Gurobi 求解器
        this.RLMPSolver = new GRBModel("RLMP"); // 创建 Gurobi 模型
        this.RLMPSolver.set(GRB.Param.OutputFlag, 0); // 关闭求解器控制台输出（按需调整）
        this.RLMPSolver.set(GRB.Param.FeasibilityTol, 1e-5); // 可行性 tolerance
        this.RLMPSolver.set(GRB.Param.Presolve, 1); // 启用预处理（默认值，可根据需要调整）

        // 初始化对偶值（与原逻辑一致）
        for (Fence fence : fences.getFenceList()) {
            this.dualsOfRLMP.put(fence.getConstName(), 0.0);
        }
        for (Carrier carrier : instance.getCarrierList()) {
            this.dualsOfRLMP.put(carrier.getConstName(), 0.0);
        }
    }

    public List<Order> solve() throws GRBException {
        this.startTime = CommonUtils.currentTimeInSecond();
        this.iterationCnt = 0;
        int lastIterationTime = 0;
        List<Order> orders = new ArrayList<>();

        while (getIterationTimeLimitLeft() > lastIterationTime && orders.size() <= Constants.MAXRMLPCOLUMNS) {
            int sTime = CommonUtils.currentTimeInSecond();
            // 根据对偶值生成列（与原逻辑一致）
            List<Order> newOrders = this.generateOrders();
            orders.addAll(newOrders);

            if (this.outputFlag) {
                this.displayIterationInformation();
            }
            this.iterationCnt += 1;

            // 若找不到更优列则退出
            if (newOrders.size() == 0) {
                break;
            }

            // 找到更优列则加入 RLMP 继续迭代
            this.addRLMPColumns(newOrders);
            // 求解 RLMP 并更新对偶值
            this.solveRLMPAndUpdateDuals();

            lastIterationTime = CommonUtils.currentTimeInSecond() - sTime;
            if (getIterationTimeLimitLeft() < lastIterationTime) {
                log.info("超时退出迭代");
            }
            if (orders.size() > Constants.MAXRMLPCOLUMNS) {
                log.info("超过最大列数退出迭代");
            }
        }

        // 释放 Gurobi 模型资源（重要：避免内存泄漏）
        RLMPSolver.dispose();
        return orders;
    }

    private List<Order> generateOrders() {
        // 设置时间约束、工单数约束（与原逻辑一致）
        this.bidLabeling.setTimeLimit(this.getIterationTimeLimitLeft());
        boolean streetSweep = false; // 原逻辑保留（未使用，可根据实际需求处理）
        return this.bidLabeling.solve(dualsOfRLMP);
    }

    private void displayIterationInformation() throws GRBException {
        // Gurobi 获取目标函数值（需判断模型是否已求解）
        double rlmpObj = RLMPSolver.get(GRB.DoubleAttr.ObjVal);
        System.out.println("CG Iteration " + this.iterationCnt
                + ": RLMPObj = " + rlmpObj
                + ", ColumnNum = " + this.RLMPVariables.size()
                + ", bestSPObj = " + this.bidLabeling.getBestObj()
                + ", TimeCost = " + this.getTimeSinceStartTime() + "s"
        );
    }

    private int getIterationTimeLimitLeft() {
        return this.iterationTimeLimit - this.getTimeSinceStartTime();
    }

    private int getTimeSinceStartTime() {
        return CommonUtils.currentTimeInSecond() - this.startTime;
    }

    /**
     * 向 RLMP 添加新列（需适配 Gurobi 的变量和约束添加方式）
     * 注意：原 Utils.addColumns 需同步修改为 Gurobi 语法（此处假设已调整，或参考下方说明）
     */
    private void addRLMPColumns(List<Order> newOrders) throws GRBException {
        // 假设 Utils.addColumns 已修改为接收 Gurobi 模型和变量映射
        // 核心逻辑：为每个新订单创建 Gurobi 变量（GRBVar），并添加到约束中
        Utils.addColumns(newOrders, this.RLMPSolver, RLMPVariables, false, fences);
    }

    /**
     * 求解 RLMP 并更新对偶值（Gurobi 版本）
     */
    private void solveRLMPAndUpdateDuals() throws GRBException {
        int timeLimit = this.getIterationTimeLimitLeft();
        // Gurobi 设置时间限制（单位：秒）
        RLMPSolver.set(GRB.Param.TimeLimit, timeLimit);

        // 求解模型
        RLMPSolver.optimize();

        // 获取求解状态
        int status = RLMPSolver.get(GRB.IntAttr.Status);
        if (status != GRB.OPTIMAL && status != GRB.FEASIBLE) {
            if (outputFlag) {
                log.info("The problem does not have an optimal solution! Status: " + status);
            }
            return;
        }

        // 更新对偶值（Gurobi 通过约束的 Pi 属性获取对偶变量）
        for (GRBConstr constraint : RLMPSolver.getConstrs()) {
            dualsOfRLMP.put(constraint.get(GRB.StringAttr.ConstrName), constraint.get(GRB.DoubleAttr.Pi));
        }
    }
}