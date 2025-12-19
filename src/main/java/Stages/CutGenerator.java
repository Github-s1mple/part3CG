package Stages;

import algoCG.RLMPSolve;
import com.gurobi.gurobi.*;
import impl.Carrier;
import impl.Instance;
import impl.Scenarios;
import lombok.Getter;
import lombok.Setter;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Benders割平面生成器（适配第一阶段最小化目标）
 * 核心：基于第二阶段列生成的载具约束对偶值，生成θ ≥ Q + Σπ_j·(O_j - O_j^k)
 */
@Setter
@Getter
public class CutGenerator {
    // 第一阶段模型实例
    private FirstStageLocationModel firstStageModel;
    // 第二阶段模型实例
    private List<RLMPSolve> rlmpSolves;
    // 候选点/仓库索引映射（候选点ID → 载具索引）
    private Map<Integer, Integer> candidateToCarrierMap;
    private Map<Integer, Double> rlmpToProbabilityMap;
    private Scenarios scenarios;
    // 精度阈值
    private static final double EPS = 1e-6;

    public CutGenerator(FirstStageLocationModel firstStageModel, Scenarios scenarios) {
        this.firstStageModel = firstStageModel;
        this.scenarios = scenarios;
    }

    public void addRLMP(RLMPSolve rlmpSolve, double scenarioProbability){
        this.rlmpSolves.add(rlmpSolve);
        rlmpToProbabilityMap.put(rlmpSolve.getIndex(), scenarioProbability);
    }

    /**
     * 核心方法：生成Benders割平面并添加到第一阶段模型
     * @return 是否成功添加割平面
     * @throws GRBException Gurobi异常
     */
    public boolean generateAndAddCut() throws GRBException {
        // 1. 校验前置条件
        if (firstStageModel.getTheta() == null) {
            System.out.println("第一阶段未初始化θ变量，跳过割平面生成");
            return false;
        }

        double dualSum = 0.0;
        // 3. 提取载具约束的对偶值
        for (RLMPSolve rlmpSolve : this.rlmpSolves){
            double curProbability = this.rlmpToProbabilityMap.get(rlmpSolve.getIndex());
            double curDual = extractCarrierDualValues(rlmpSolve);
            dualSum += curProbability * curDual;
        }

        // 4. 构建割平面表达式：θ ≥ Σscenario * Σπ_j
        GRBLinExpr cutExpr = new GRBLinExpr();
        cutExpr.addConstant(dualSum);

        // 6. 生成唯一割平面名称
        String cutName = String.format("BendersCut_%d", System.currentTimeMillis());

        // 7. 添加割平面到第一阶段模型：θ ≥ cutExpr
        firstStageModel.addBendersCut(cutExpr, cutName);

        // 输出割平面信息（调试）
        System.out.println("========================================");
        System.out.println("生成Benders割平面：" + cutName);
        System.out.println("割平面表达式：θ ≥ " + cutExpr.toString());
        System.out.println("========================================");

        return true;
    }

    /**
     * 提取载具约束的对偶值（适配第一阶段最小化目标）
     * @return key=载具索引，value=修正后的对偶值
     */
    private double extractCarrierDualValues(RLMPSolve rlmpSolve) {
        Map<Integer, Double> carrierDualMap = new HashMap<>();
        Map<String, Double> dualVars = rlmpSolve.getDualVariables();
        Instance instance = rlmpSolve.getInstance();
        double dualSum = 0.0;
        // 遍历所有载具，匹配约束名提取对偶值
        for (Carrier carrier : instance.getCarrierList()) {
            String carrierConstName = carrier.getConstName();

            // 从列生成对偶值中获取
            if (dualVars.containsKey(carrierConstName)) {
                double dualValue = dualVars.get(carrierConstName);
                dualSum += dualValue;
                carrierDualMap.put(carrier.getIndex(), dualValue);
            } else {
                System.out.println("未找到载具" + carrier.getIndex() + "约束[" + carrierConstName + "]的对偶值");
                carrierDualMap.put(carrier.getIndex(), 0.0);
            }
        }

        return dualSum;
    }
}