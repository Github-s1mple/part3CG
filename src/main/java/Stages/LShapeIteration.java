package Stages;

import algoCG.CGSolve;
import impl.*;

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
    private Map<Integer, Integer> Solution;

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
                // ---------- 记录单次迭代的开始时间 ----------
                long iterationStartTime = System.currentTimeMillis();
                System.out.println("========================================");
                System.out.println("L-Shaped 迭代第" + iter + "次");

                // -------------------- 步骤1：求解当前第一阶段模型（基于固定解） --------------------
                long firstStageSolveStartTime = System.currentTimeMillis();
                LocationResult firstStageResult = firstStage.solveIter();
                long firstStageSolveEndTime = System.currentTimeMillis();
                if (firstStageResult == null) {
                    System.err.println("第" + iter + "次迭代求解失败，终止");
                    break;
                }
                System.out.println("第一阶段模型求解耗时：" +
                        String.format("%.2f", (firstStageSolveEndTime - firstStageSolveStartTime) / 1000.0) + " 秒");

                // -------------------- 步骤2：提取当前最优O_i解 --------------------
                this.Solution = firstStage.getCurrentOSolution();
                double currentSol = firstStage.getTotalCost();
                System.out.println("当前最优值：" + currentSol);

                // -------------------- 步骤3：生成并添加割平面 --------------------
                int scenarioCount = 1;
                CutGenerator cutGenerator = new CutGenerator(firstStage, scenarios);
                for (Scenario scenario : scenarios.getScenarioList()){
                    // ---------- 记录单个场景的开始时间 ----------
                    long scenarioStartTime = System.currentTimeMillis();
                    System.out.println("\n正在计算第" + iter + "次迭代的第" + scenarioCount + " / " + scenarios.getScenarioList().size() + "个场景（ID：" + scenario.getId() + "）");

                    Instance instance = new Instance(firstStageResult, scenario);
                    CGSolve stage2Model = new CGSolve(instance);
                    List<Order> allColumns = stage2Model.solve(); // 生成的所有列
                    cutGenerator.addRLMP(stage2Model.getFinalSolver(), scenario.getProbability());

                    // ---------- 计算并输出单个场景的耗时 ----------
                    long scenarioEndTime = System.currentTimeMillis();
                    double scenarioCostTime = (scenarioEndTime - scenarioStartTime) / 1000.0;
                    System.out.println("第" + scenarioCount + "个场景计算耗时：" +
                            String.format("%.2f", scenarioCostTime) + " 秒");
                    scenarioCount++;
                }

                // ---------- 记录割平面生成耗时 ----------
                long cutGenerateStartTime = System.currentTimeMillis();
                boolean hasValidCut = cutGenerator.generateAndAddCut();
                long cutGenerateEndTime = System.currentTimeMillis();
                if (hasValidCut){
                    System.out.println("\n割平面添加成功");
                    System.out.println("\n割平面耗时：" + String.format("%.2f", (cutGenerateEndTime - cutGenerateStartTime) / 1000.0) + " 秒");
                }


                // -------------------- 步骤4：计算收敛gap --------------------
                gap = (this.s_c - currentSol) / this.s_c;
                System.out.println("当前迭代gap：" + String.format("%.6f", gap));

                // -------------------- 步骤5：判断是否继续迭代 --------------------
                if (!hasValidCut || gap < GAP_THRESHOLD) {
                    System.out.println("迭代收敛（无有效割平面/gap达标），终止");
                    break;
                }
                this.s_c = currentSol;
                firstStage.setFixedOValues(null);

                // ---------- 计算并输出单次迭代的总耗时 ----------
                long iterationEndTime = System.currentTimeMillis();
                double iterationTotalTime = (iterationEndTime - iterationStartTime) / 1000.0;
                System.out.println("\n第" + iter + "次迭代总耗时：" +
                        String.format("%.2f", iterationTotalTime) + " 秒");
            }

            this.Solution.entrySet().removeIf(entry -> entry.getValue() != 1);
            // ===================== 4. 输出最终结果 =====================
            System.out.println("\n========================================");
            System.out.println("迭代结束（总迭代次数：" + iter + "）");
            System.out.println("最终gap：" + String.format("%.6f", gap));
            System.out.println("最终O_i解：" + this.Solution);
            System.out.println("最终总成本：" + this.s_c);
            System.out.println("========================================");

            // ===================== 5. 释放资源 =====================
            firstStage.releaseResources();

        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}