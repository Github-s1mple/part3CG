package Stages;

import algoCG.CGSolve;
import algoCG.OrderColumnGeneration;
import algoCG.RLMPSolve;
import impl.*;
import com.gurobi.gurobi.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static baseinfo.Constants.GAP_THRESHOLD;
import static baseinfo.Constants.MAX_ITER;

/**
 * 基于固定初始解的L形迭代流程
 * 核心：从固定初始解出发，迭代优化O_i并更新fixedOValues
 */
public class LShapeIteration {
    private Scenarios scenarios;
    private double s_c = 100000;
    private FirstStageLocationModel firstStage;
    private double finalCost;
    private Map<Integer, Integer> finalSolution;

    public LShapeIteration(Scenarios scenarios) {
        this.scenarios = scenarios;
    }

    public void solve() {
        try {
            // ===================== 1. 初始化输入与初始解 =====================
            // 1.1 第一阶段输入（含固定初始解）
            InputData inputData = new InputData(true); // 你的第一阶段输入

            // ===================== 2. 初始化第一阶段模型 =====================
            this.firstStage = new FirstStageLocationModel(inputData);
            firstStage.defineVariables();       // 定义变量（含θ，锁定初始解）
            firstStage.setObjective();          // 目标：min 固定成本+θ
            firstStage.addCoreConstraints();    // 添加核心约束
            firstStage.setOutputFlag(true);     // 输出详细日志

            // ===================== 3. 迭代优化 =====================
            double gap = Double.MAX_VALUE;
            int iter = 0;

            while (gap > GAP_THRESHOLD && iter < MAX_ITER) {
                iter++;
                System.out.println("\n========================================");
                System.out.println("L形迭代第" + iter + "次");
                System.out.println("========================================");

                // -------------------- 步骤1：求解当前第一阶段模型（基于固定解） --------------------
                LocationResult firstStageResult = firstStage.solveIter();
                if (firstStageResult == null) {
                    System.err.println("第" + iter + "次迭代求解失败，终止");
                    break;
                }

                // -------------------- 步骤2：提取当前最优O_i解 --------------------
                Map<Integer, Integer> currentOSol = firstStage.getCurrentOSolution();
                double currentSol = firstStage.getTotalCost();
                System.out.println("当前最优值：" + currentSol);
                //System.out.println("当前最优O_i解：" + currentOSol);

                // -------------------- 步骤4：生成并添加割平面 --------------------
                CutGenerator cutGenerator = new CutGenerator(firstStage, scenarios);
                for (Scenario scenario : scenarios.getScenarioList()){
                    Instance instance = new Instance(firstStageResult, scenario); // 你的第二阶段输入
                    CGSolve cg = new CGSolve(instance);
                    List<Order> allColumns = cg.solve(); // 生成的所有列
                    cutGenerator.addRLMP(cg.getFinalSolver(), scenario.getProbability());
                }
                boolean hasValidCut = cutGenerator.generateAndAddCut();

                // -------------------- 步骤5：计算收敛间隙 --------------------
                gap = (this.s_c - currentSol) / this.s_c;
                System.out.println("当前迭代间隙：" + String.format("%.6f", gap));

                // -------------------- 步骤6：判断是否继续迭代 --------------------
                if (!hasValidCut || gap < GAP_THRESHOLD) {
                    System.out.println("迭代收敛（无有效割平面/间隙达标），终止");
                    break;
                }
                this.s_c = currentSol;
                firstStage.setFixedOValues(null);
            }

            // ===================== 4. 输出最终结果 =====================
            this.finalCost = firstStage.getModel().get(GRB.DoubleAttr.ObjVal);
            this.finalSolution = firstStage.getCurrentOSolution();
            System.out.println("\n========================================");
            System.out.println("迭代结束（总迭代次数：" + iter + "）");
            System.out.println("最终间隙：" + String.format("%.6f", gap));
            System.out.println("最终固定O_i解：" + this.finalSolution);
            System.out.println("最终总成本：" + this.finalCost);
            System.out.println("========================================");

            // ===================== 5. 释放资源 =====================
            firstStage.releaseResources();

        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}