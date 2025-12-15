package Stages;

import algoCG.CGSolve;
import algoCG.RLMPSolve;
import baseinfo.Constants;
import com.gurobi.gurobi.GRB;
import com.gurobi.gurobi.GRBConstr;
import com.gurobi.gurobi.GRBException;
import com.gurobi.gurobi.GRBLinExpr;
import com.gurobi.gurobi.GRBVar;
import impl.*;
import Stages.FirstStageLocationModel;
import Utils.CommonUtils;

import java.io.IOException;
import java.util.*;

public class LShapeMain {
    // 迭代控制参数
    private static final int MAX_ITERATIONS = 50;         // 最大迭代次数
    private static final double EPSILON = 1e-4;           // 收敛精度（Gap阈值）
    private static final double BASE_LOAD = 1.0;          // 基础负载系数（根据业务调整）
    private static final List<Scenario> scenarios;        // 随机场景列表

    // 静态初始化：生成随机场景（示例场景，需根据实际业务扩展）
    static {
        scenarios = new ArrayList<>();
        // 场景1：需求系数1.0，概率0.3
        scenarios.add(new Scenario(1, 0.3, createDemandFactors(1.0)));
        // 场景2：需求系数1.2，概率0.5
        scenarios.add(new Scenario(2, 0.5, createDemandFactors(1.2)));
        // 场景3：需求系数0.8，概率0.2
        scenarios.add(new Scenario(3, 0.2, createDemandFactors(0.8)));
    }

    public static void main(String[] args) throws GRBException, IOException {
        System.out.println("===== 启动L形法求解两阶段随机规划问题 =====");
        InputData input = new InputData();  // 初始化输入数据
        FirstStageLocationModel masterProblem = null;
        double upperBound = Double.POSITIVE_INFINITY;
        double lowerBound = Double.NEGATIVE_INFINITY;
        int iteration = 0;

        try {
            // 初始化第一阶段主问题（含L形法的θ变量）
            masterProblem = new FirstStageLocationModel(input);
            masterProblem.setOutputFlag(true);
            masterProblem.defineVariablesWithTheta();  // 定义含θ的变量
            masterProblem.setObjective();     // 目标函数：固定成本 + θ
            masterProblem.addCoreConstraints();        // 添加核心约束

            // L形法主迭代循环
            while (iteration < MAX_ITERATIONS) {
                iteration++;
                System.out.println("\n======================================");
                System.out.println("迭代次数：" + iteration);
                System.out.println("======================================");

                // 步骤1：求解当前主问题，获取第一阶段解x^k
                masterProblem.resetModel();
                LocationResult currentResult = masterProblem.solve();
                if (currentResult == null) {
                    System.err.println("主问题无可行解，终止迭代");
                    break;
                }
                Map<String, Double> currentSolution = masterProblem.getCurrentSolution();
                lowerBound = masterProblem.getModel().get(GRB.DoubleAttr.ObjVal);
                System.out.printf("主问题下界（c^T x + θ）：%.2f%n", lowerBound);

                // 步骤2：求解所有场景的子问题（列生成）
                upperBound = 0.0;
                List<GRBLinExpr> optimalityCuts = new ArrayList<>();
                boolean hasFeasibilityIssue = false;

                for (Scenario scenario : scenarios) {
                    System.out.println("\n处理场景：ID=" + scenario.getId() + "，概率=" + scenario.getProbability());

                    // 构建子问题实例（含当前第一阶段解和场景参数）
                    Instance subProblemInstance = new Instance(currentResult);
                    subProblemInstance.setScenario(scenario);  // 传入场景参数

                    // 调用列生成求解子问题
                    CGSolve cgSolver = new CGSolve();
                    List<Order> optimalOrders = cgSolver.solve(subProblemInstance);

                    // 获取列生成最终主问题（RLMP）的求解结果
                    RLMPSolve rlmpSolver = cgSolver.getFinalSolver();  // 需CGSolve支持返回RLMPSolve
                    if (rlmpSolver == null) {
                        System.err.println("场景" + scenario.getId() + "列生成求解失败");
                        hasFeasibilityIssue = true;
                        break;
                    }

                    // 检查子问题可行性
                    int subProblemStatus = rlmpSolver.getFinalModel().get(GRB.IntAttr.Status);
                    if (subProblemStatus == GRB.Status.INFEASIBLE) {
                        System.out.println("场景" + scenario.getId() + "子问题不可行，生成可行性割平面");
                        GRBLinExpr feasibilityCut = generateFeasibilityCut(
                                rlmpSolver, masterProblem, scenario, currentSolution
                        );
                        masterProblem.addFeasibilityCut(feasibilityCut, 0.0);
                        hasFeasibilityIssue = true;
                        break;
                    } else if (subProblemStatus != GRB.Status.OPTIMAL && subProblemStatus != GRB.Status.SUBOPTIMAL) {
                        System.err.println("场景" + scenario.getId() + "子问题求解状态异常：" + subProblemStatus);
                        hasFeasibilityIssue = true;
                        break;
                    }

                    // 子问题可行：提取目标值和对偶解
                    double secondStageCost = -rlmpSolver.getTotalProfit();  // 若列生成是最大化收益，需取负为成本
                    upperBound += scenario.getProbability() * secondStageCost;
                    Map<String, Double> duals = rlmpSolver.getDualVariables();  // 列生成RLMP的对偶解

                    // 生成最优性割平面
                    GRBLinExpr optimalityCut = generateOptimalityCut(
                            duals, masterProblem, currentSolution, secondStageCost, scenario
                    );
                    optimalityCuts.add(optimalityCut);
                    System.out.printf("场景%d处理完成，第二阶段成本：%.2f，已生成最优性割平面%n",
                            scenario.getId(), secondStageCost);
                }

                // 若存在可行性问题，跳过本轮割平面添加
                if (hasFeasibilityIssue) {
                    System.out.println("存在不可行场景，已添加可行性割平面，进入下一轮迭代");
                    continue;
                }

                // 步骤3：计算总上界（第一阶段成本 + 第二阶段期望成本）
                double firstStageCost = calculateFirstStageCost(currentSolution, masterProblem);
                upperBound += firstStageCost;
                System.out.printf("当前总上界（第一阶段成本 + 期望第二阶段成本）：%.2f%n", upperBound);

                // 步骤4：收敛判断
                double gap = (upperBound - lowerBound) / upperBound;
                System.out.printf("Gap：%.4f%%%n", gap * 100);
                if (gap < EPSILON) {
                    System.out.println("迭代收敛！Gap小于阈值" + EPSILON);
                    break;
                }

                // 步骤5：添加所有最优性割平面，更新主问题
                for (GRBLinExpr cut : optimalityCuts) {
                    masterProblem.addOptimalityCut(cut);
                }
                System.out.println("已添加" + optimalityCuts.size() + "个最优性割平面，进入下一轮迭代");
            }

            // 输出最终结果
            System.out.println("\n======================================");
            System.out.println("L形法迭代结束（总迭代次数：" + iteration + "）");
            System.out.println("最优目标值：" + upperBound);
            LocationResult finalResult = masterProblem.solve();
            System.out.println("第一阶段最优决策：");
            System.out.println("选中的候选点：" + finalResult.getSelectedCandidates());
            System.out.println("额外需求分配：" + finalResult.getExtraAllocation());

        } finally {
            // 释放资源
            if (masterProblem != null) {
                masterProblem.getModel().dispose();
                masterProblem.getEnv().dispose();
            }
            System.out.println("===== L形法求解结束 =====");
        }
    }

    /**
     * 生成最优性割平面：θ ≥ Σp_s [Q_s(x^k) + π_s^T T_s (x - x^k)]
     * 基于列生成RLMP的对偶解和第一阶段变量关联
     */
    /**
     * 生成最优性割平面（修正版）：适配"O_j=1对应1辆载具"的业务逻辑
     */
    private static GRBLinExpr generateOptimalityCut(Map<String, Double> duals,
                                                    FirstStageLocationModel master,
                                                    Map<String, Double> currentSolution,
                                                    double qs, Scenario scenario) throws GRBException {
        GRBLinExpr cut = new GRBLinExpr();
        double probability = scenario.getProbability();
        double constant = qs;  // 初始常数项：Q_s(x^k)

        // 1. 处理围栏容量约束的对偶解（关联Xs_ij变量）
        // （这部分逻辑不变，因为Xs_ij与围栏负载的关系不受载具关联影响）
        for (Fence fence : master.getFences().getFenceList()) {
            String constrName = fence.getConstName();
            double pi = duals.getOrDefault(constrName, 0.0);  // 围栏约束的对偶值
            int fenceIndex = fence.getIndex();

            for (int j : master.getC()) {
                String xVarName = String.format("Xs_%d_%d", fenceIndex, j);
                GRBVar xVar = master.getVarMap().get(xVarName);
                if (xVar == null) continue;

                // T_s系数：Xs_ij对围栏容量的影响 = 基础负载 * 场景需求系数
                double demandFactor = scenario.getDemandFactors().getOrDefault(fenceIndex, 1.0);
                double tCoeff = BASE_LOAD * demandFactor;

                // 割平面中x的线性项：p_s * pi * tCoeff * Xs_ij
                cut.addTerm(probability * pi * tCoeff, xVar);

                // 常数项调整：减去 p_s * pi * tCoeff * x^k_ij（当前解）
                double xk = currentSolution.getOrDefault(xVarName, 0.0);
                constant -= probability * pi * tCoeff * xk;
            }
        }

        // 2. 处理载具约束的对偶解（修正部分：关联O_j变量，适配"O_j=1对应1辆载具"）
        for (int j : master.getC()) {  // 遍历候选点j
            // 候选点j对应的载具约束名（假设命名规范："carrier_j"）
            String carrierConstrName = "carrier_" + j;  // 关键：载具与候选点j同名/同索引
            double pi = duals.getOrDefault(carrierConstrName, 0.0);  // 载具约束的对偶值

            // O_j变量：候选点j是否开放
            String oVarName = String.format("O_%d", j);
            GRBVar oVar = master.getVarMap().get(oVarName);
            if (oVar == null) continue;

            // T_s系数：O_j=1提供1辆载具，故系数为1.0
            double tCoeff = 1.0;

            // 割平面中x的线性项：p_s * pi * tCoeff * O_j
            cut.addTerm(probability * pi * tCoeff, oVar);

            // 常数项调整：减去 p_s * pi * tCoeff * x^k_j（当前O_j的取值）
            double xk = currentSolution.getOrDefault(oVarName, 0.0);
            constant -= probability * pi * tCoeff * xk;
        }

        // 添加常数项到割平面
        cut.addConstant(probability * constant);
        return cut;
    }

    /**
     * 生成可行性割平面（子问题不可行时）
     */
    private static GRBLinExpr generateFeasibilityCut(RLMPSolve rlmp,
                                                     FirstStageLocationModel master,
                                                     Scenario scenario,
                                                     Map<String, Double> currentSolution) throws GRBException {
        GRBLinExpr cut = new GRBLinExpr();

        // 分析不可行约束（IIS）
        List<GRBConstr> iisConstraints = new ArrayList<>();
        for (GRBConstr constr : rlmp.getFinalModel().getConstrs()) {
            if (constr.get(GRB.IntAttr.IISConstr) == 1) {
                iisConstraints.add(constr);
            }
        }

        // 基于IIS约束生成割平面
        for (GRBConstr constr : iisConstraints) {
            String constrName = constr.get(GRB.StringAttr.ConstrName);

            // 1. 围栏容量约束导致的不可行（逻辑不变）
            if (constrName.startsWith("fence_")) {
                int fenceIndex = extractIndexFromConstrName(constrName, "fence_");
                double demandFactor = scenario.getDemandFactors().getOrDefault(fenceIndex, 1.0);

                for (int j : master.getC()) {
                    String xVarName = String.format("Xs_%d_%d", fenceIndex, j);
                    GRBVar xVar = master.getVarMap().get(xVarName);
                    if (xVar == null) continue;
                    cut.addTerm(demandFactor, xVar);  // 确保负载不超过容量
                }
            }

            // 2. 载具约束导致的不可行（修正部分：关联O_j）
            if (constrName.startsWith("carrier_")) {
                // 载具约束名"carrier_j"对应候选点j
                int j = extractIndexFromConstrName(constrName, "carrier_");
                String oVarName = String.format("O_%d", j);
                GRBVar oVar = master.getVarMap().get(oVarName);
                if (oVar == null) continue;

                // 约束：O_j必须为1（开放候选点j以提供载具）
                cut.addTerm(1.0, oVar);
            }
        }

        return cut;
    }

    /**
     * 辅助方法：计算第一阶段成本（固定成本）
     */
    private static double calculateFirstStageCost(Map<String, Double> solution, FirstStageLocationModel master) {
        double cost = 0.0;
        for (int j : master.getC()) {
            String varName = String.format("O_%d", j);
            double oVal = solution.getOrDefault(varName, 0.0);
            cost += oVal * master.getCandidates().getCandidate(j).getBuildCost();
        }
        return cost;
    }

    /**
     * 辅助方法：从约束名中提取索引（如"fence_5"→5）
     */
    private static int extractIndexFromConstrName(String constrName, String prefix) {
        return Integer.parseInt(constrName.replace(prefix, ""));
    }


    /**
     * 辅助方法：创建场景的需求系数映射（示例）
     */
    private static Map<Integer, Double> createDemandFactors(double factor) {
        Map<Integer, Double> demandFactors = new HashMap<>();
        // 假设栅格ID为0~9，需求系数均为factor（实际需根据业务设置）
        for (int i = 0; i < 10; i++) {
            demandFactors.put(i, factor);
        }
        return demandFactors;
    }
}