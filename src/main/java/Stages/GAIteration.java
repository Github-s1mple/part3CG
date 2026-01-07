package Stages;

import algoCG.CGSolve;
import baseinfo.Constants;
import com.gurobi.gurobi.GRBException;
import impl.*;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 基于遗传算法的两阶段迭代流程
 * 核心：GA生成一阶段初始解 → 一/二阶段求解总收益 → GA迭代优化 → 控制迭代次数获取最终解
 */
public class GAIteration {
    // 原始参数
    private Scenarios scenarios;
    private double finalBestCost; // 最终最优总收益（成本型为最小值，收益型为最大值）
    private Map<Integer, Integer> bestSolution; // 最终最优O_i解

    // GA 核心配置参数
    private int gaPopulationSize = 20; // 种群大小
    private double gaCrossoverRate = 0.8; // 交叉概率
    private double gaMutationRate = 0.1; // 变异概率
    private int gaElitismCount = 4; // 精英保留数量（直接进入下一代种群）

    // 第一阶段模型相关
    private FirstStageLocationModel firstStage;
    private InputData inputData; // 基础输入数据（复用）

    public GAIteration(Scenarios scenarios) {
        this.scenarios = scenarios;
        // 初始化最优解容器
        this.bestSolution = new HashMap<>();
        this.finalBestCost = Double.MAX_VALUE; // 成本型问题初始化为极大值，收益型改为Double.MIN_VALUE
    }

    /**
     * 核心求解流程：GA驱动的两阶段迭代优化
     */
    public void solve() {
        try {
            // ===================== 1. 初始化基础数据与第一阶段模型 =====================
            System.out.println("========================================");
            System.out.println("初始化基础数据与模型");
            this.inputData = new InputData(true); // 基础输入数据
            this.firstStage = new FirstStageLocationModel(inputData);
            // 初始化第一阶段模型（定义变量、设置目标、添加核心约束）
            firstStage.defineVariables();
            firstStage.setObjective();
            firstStage.addCoreConstraints();
            firstStage.setOutputFlag(false); // GA迭代中关闭详细日志，减少输出冗余

            // ===================== 2. GA 初始化：生成初始种群（一阶段初始解集合） =====================
            System.out.println("========================================");
            System.out.println("GA初始化：生成初始种群（大小：" + gaPopulationSize + "）");
            List<Map<Integer, Integer>> population = initGAPopulation();

            // 初始化种群的适应度（总收益/总成本）
            Map<Map<Integer, Integer>, Double> fitnessMap;

            // ===================== 3. GA 迭代优化主循环 =====================
            System.out.println("========================================");
            System.out.println("GA开始迭代（最大迭代次数：" + Constants.MAX_ITER + "）");
            for (int gaIter = 1; gaIter <= Constants.MAX_ITER; gaIter++) {
                long gaIterStartTime = System.currentTimeMillis();
                System.out.println("\n---------------------- GA第" + gaIter + "次迭代 ----------------------");

                // ---------- 步骤3.1：评估当前种群所有个体的适应度（计算总收益/总成本） ----------
                fitnessMap = evaluatePopulationFitness(population);

                // ---------- 步骤3.2：更新全局最优解 ----------
                updateGlobalBestSolution(fitnessMap);

                // ---------- 步骤3.3：GA 选择操作（精英保留 + 轮盘赌选择） ----------
                List<Map<Integer, Integer>> selectedPopulation = selectPopulation(population, fitnessMap);

                // ---------- 步骤3.4：GA 交叉操作 ----------
                List<Map<Integer, Integer>> crossedPopulation = crossoverPopulation(selectedPopulation);

                // ---------- 步骤3.5：GA 变异操作 ----------
                List<Map<Integer, Integer>> mutatedPopulation = mutatePopulation(crossedPopulation);

                // ---------- 步骤3.6：更新下一代种群 ----------
                population = mutatedPopulation;

                // ---------- 步骤3.7：输出当前迭代信息 ----------
                long gaIterEndTime = System.currentTimeMillis();
                double gaIterCostTime = (gaIterEndTime - gaIterStartTime) / 1000.0;
                double currentBestFitness = fitnessMap.get(getCurrentPopulationBest(fitnessMap));
                System.out.println("当前迭代最优适应度（总成本）：" + String.format("%.6f", currentBestFitness));
                System.out.println("当前迭代耗时：" + String.format("%.2f", gaIterCostTime) + " 秒");
            }

            // ===================== 4. 输出最终结果 =====================
            System.out.println("\n========================================");
            System.out.println("GA迭代优化结束（总迭代次数：" + Constants.MAX_ITER + "）");
            System.out.println("最终最优O_i解：" + bestSolution);
            System.out.println("最终最优总成本：" + String.format("%.6f", finalBestCost));
            System.out.println("========================================");

            // ===================== 5. 释放资源 =====================
            firstStage.releaseResources();

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // ===================== GA 核心辅助方法：初始化种群 =====================
    /**
     * 初始化GA种群：生成多个一阶段初始解（O_i的0-1分布）
     * 包含1个原始固定初始解 + 随机生成其他个体
     */
    private List<Map<Integer, Integer>> initGAPopulation() {
        List<Map<Integer, Integer>> population = new ArrayList<>();

        // 1. 添加原始初始解（作为种群的初始个体）
        Map<Integer, Integer> originalSolution = inputData.getInitialOj(); // 从输入数据获取原始初始解
        population.add(new HashMap<>(originalSolution));

        // 2. 随机生成剩余种群个体（保证O_i为0/1分布，符合第一阶段约束）
        Set<Integer> candidateIds = originalSolution.keySet(); // 候选点ID集合
        for (int i = 1; i < gaPopulationSize; i++) {
            Map<Integer, Integer> randomSolution = new HashMap<>();
            for (Integer candidateId : candidateIds) {
                // 随机生成0/1值，模拟初始解（可根据业务添加约束，如选中数量限制）
                randomSolution.put(candidateId, new Random().nextInt(2));
            }
            population.add(randomSolution);
        }

        return population;
    }

    // ===================== GA 核心辅助方法：评估种群适应度 =====================
    /**
     * 评估种群中每个个体的适应度（即两阶段总收益/总成本）
     * 流程：个体（一阶段初始解）→ 一阶段求解 → 多场景二阶段求解 → 计算期望收益 → 总收益=一阶段成本+二阶段期望成本
     */
    private Map<Map<Integer, Integer>, Double> evaluatePopulationFitness(List<Map<Integer, Integer>> population) throws GRBException {
        Map<Map<Integer, Integer>, Double> fitnessMap = new HashMap<>();

        for (Map<Integer, Integer> individual : population) {
            if (fitnessMap.containsKey(individual)) {
                continue; // 避免重复评估
            }

            long individualStartTime = System.currentTimeMillis();
            System.out.println("正在评估个体适应度：" + individual);

            // ---------- 步骤1：将个体作为一阶段初始解，求解一阶段模型 ----------
            firstStage.setFixedOValues(individual); // 传入GA生成的初始解
            LocationResult firstStageResult = firstStage.solveWithFixedO();
            if (firstStageResult == null) {
                System.err.println("个体一阶段求解失败，跳过该个体");
                fitnessMap.put(individual, Double.MAX_VALUE); // 无效个体设为极大成本（被淘汰）
                continue;
            }
            double firstStageCost = firstStage.getTotalCost(); // 一阶段成本

            // ---------- 步骤2：多场景二阶段求解，计算期望成本 ----------
            double expectedSecondStageCost = 0.0;
            int scenarioCount = 1;

            for (Scenario scenario : scenarios.getScenarioList()) {
                Instance instance = new Instance(firstStageResult, scenario);
                CGSolve stage2Model = new CGSolve(instance);
                stage2Model.solve(); // 二阶段单场景求解

                // 累加场景期望成本（场景概率 * 单场景成本）
                double scenarioCost = stage2Model.getFinalSolver().getTotalProfit();
                expectedSecondStageCost += scenarioCost * scenario.getProbability();

                scenarioCount++;
            }

            // ---------- 步骤3：计算总适应度（总收益/总成本） ----------
            double totalCost = firstStageCost - expectedSecondStageCost; // 总成本=一阶段 - 二阶段期望
            fitnessMap.put(new HashMap<>(individual), totalCost);

            // ---------- 输出个体评估信息 ----------
            long individualEndTime = System.currentTimeMillis();
            double individualCostTime = (individualEndTime - individualStartTime) / 1000.0;
            System.out.println("个体评估完成，总成本：" + String.format("%.6f", totalCost) + "，耗时：" + String.format("%.2f", individualCostTime) + " 秒");
        }

        return fitnessMap;
    }

    // ===================== GA 核心辅助方法：更新全局最优解 =====================
    /**
     * 从当前种群适应度中，更新全局最优解
     */
    private void updateGlobalBestSolution(Map<Map<Integer, Integer>, Double> fitnessMap) {
        // 找到当前种群中适应度最优的个体（成本型取最小值，收益型取最大值）
        Map.Entry<Map<Integer, Integer>, Double> bestEntry = fitnessMap.entrySet().stream()
                .min(Map.Entry.comparingByValue()) // 成本型：min，收益型改为max
                .orElse(null);

        if (bestEntry == null) {
            return;
        }

        Map<Integer, Integer> currentBest = bestEntry.getKey();
        double currentBestCost = bestEntry.getValue();

        // 更新全局最优
        if (currentBestCost < this.finalBestCost) { // 成本型：小于当前最优则更新，收益型改为大于
            this.finalBestCost = currentBestCost;
            this.bestSolution = new HashMap<>(currentBest);
            // 过滤掉O_i≠1的项，保持结果简洁
            this.bestSolution.entrySet().removeIf(entry -> entry.getValue() != 1);
            System.out.println("更新全局最优解，当前最优总成本：" + String.format("%.6f", finalBestCost));
        }
    }

    // ===================== GA 核心辅助方法：选择操作（精英保留+轮盘赌） =====================
    /**
     * 选择下一代种群的父代：精英直接保留 + 轮盘赌选择剩余个体
     */
    private List<Map<Integer, Integer>> selectPopulation(List<Map<Integer, Integer>> population,
                                                         Map<Map<Integer, Integer>, Double> fitnessMap) {
        List<Map<Integer, Integer>> selectedPopulation = new ArrayList<>();

        // ---------- 步骤1：精英保留（直接选择适应度最优的N个个体） ----------
        List<Map<Integer, Integer>> eliteIndividuals = fitnessMap.entrySet().stream()
                .sorted(Map.Entry.comparingByValue()) // 成本型：升序，收益型改为降序
                .limit(gaElitismCount)
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());
        selectedPopulation.addAll(eliteIndividuals);

        // ---------- 步骤2：轮盘赌选择剩余个体（适应度越好，被选中概率越高） ----------
        int remainingSlots = gaPopulationSize - gaElitismCount;
        // 计算适应度倒数（成本型：成本越低，权重越高；收益型直接用适应度）
        Map<Map<Integer, Integer>, Double> weightMap = new HashMap<>();
        double totalWeight = 0.0;

        for (Map.Entry<Map<Integer, Integer>, Double> entry : fitnessMap.entrySet()) {
            double fitness = entry.getValue();
            if (fitness == Double.MAX_VALUE) {
                weightMap.put(entry.getKey(), 0.0); // 无效个体权重为0
                continue;
            }
            double weight = 1.0 / fitness; // 成本型：倒数作为权重
            weightMap.put(entry.getKey(), weight);
            totalWeight += weight;
        }

        // 轮盘赌抽样
        for (int i = 0; i < remainingSlots; i++) {
            double random = new Random().nextDouble() * totalWeight;
            double currentWeight = 0.0;

            for (Map.Entry<Map<Integer, Integer>, Double> entry : weightMap.entrySet()) {
                currentWeight += entry.getValue();
                if (currentWeight >= random) {
                    selectedPopulation.add(new HashMap<>(entry.getKey()));
                    break;
                }
            }
        }

        return selectedPopulation;
    }

    // ===================== GA 核心辅助方法：交叉操作 =====================
    /**
     * 交叉操作：对选中的种群进行单点交叉，生成新个体
     */
    private List<Map<Integer, Integer>> crossoverPopulation(List<Map<Integer, Integer>> selectedPopulation) {
        List<Map<Integer, Integer>> crossedPopulation = new ArrayList<>();
        Random random = new Random();

        // 保留精英个体（不参与交叉）
        List<Map<Integer, Integer>> elite = selectedPopulation.subList(0, gaElitismCount);
        crossedPopulation.addAll(elite);

        // 对剩余个体进行两两交叉
        List<Map<Integer, Integer>> nonElite = selectedPopulation.subList(gaElitismCount, selectedPopulation.size());
        for (int i = 0; i < nonElite.size(); i += 2) {
            if (i + 1 >= nonElite.size()) {
                crossedPopulation.add(new HashMap<>(nonElite.get(i)));
                break;
            }

            Map<Integer, Integer> parent1 = nonElite.get(i);
            Map<Integer, Integer> parent2 = nonElite.get(i + 1);
            Set<Integer> candidateIds = parent1.keySet();
            List<Integer> candidateList = new ArrayList<>(candidateIds);

            // 随机决定是否交叉
            if (random.nextDouble() > gaCrossoverRate) {
                // 不交叉，直接保留父代
                crossedPopulation.add(new HashMap<>(parent1));
                crossedPopulation.add(new HashMap<>(parent2));
                continue;
            }

            // 单点交叉：随机选择交叉点
            int crossoverPoint = random.nextInt(candidateList.size());
            Map<Integer, Integer> child1 = new HashMap<>();
            Map<Integer, Integer> child2 = new HashMap<>();

            for (int j = 0; j < candidateList.size(); j++) {
                Integer candidateId = candidateList.get(j);
                if (j <= crossoverPoint) {
                    child1.put(candidateId, parent1.get(candidateId));
                    child2.put(candidateId, parent2.get(candidateId));
                } else {
                    child1.put(candidateId, parent2.get(candidateId));
                    child2.put(candidateId, parent1.get(candidateId));
                }
            }

            // 添加子代到种群
            crossedPopulation.add(child1);
            crossedPopulation.add(child2);
        }

        // 截断到种群大小
        if (crossedPopulation.size() > gaPopulationSize) {
            crossedPopulation = crossedPopulation.subList(0, gaPopulationSize);
        }

        return crossedPopulation;
    }

    // ===================== GA 核心辅助方法：变异操作 =====================
    /**
     * 变异操作：对种群个体进行随机变异（翻转O_i的0/1状态）
     */
    private List<Map<Integer, Integer>> mutatePopulation(List<Map<Integer, Integer>> crossedPopulation) {
        List<Map<Integer, Integer>> mutatedPopulation = new ArrayList<>();
        Random random = new Random();

        for (Map<Integer, Integer> individual : crossedPopulation) {
            Map<Integer, Integer> mutatedIndividual = new HashMap<>(individual);
            Set<Integer> candidateIds = mutatedIndividual.keySet();

            // 精英个体不参与变异（保留最优解）
            if (mutatedPopulation.size() < gaElitismCount) {
                mutatedPopulation.add(mutatedIndividual);
                continue;
            }

            // 随机变异每个候选点的O_i状态
            for (Integer candidateId : candidateIds) {
                if (random.nextDouble() <= gaMutationRate) {
                    // 翻转0/1状态
                    int currentValue = mutatedIndividual.get(candidateId);
                    mutatedIndividual.put(candidateId, currentValue == 1 ? 0 : 1);
                }
            }

            mutatedPopulation.add(mutatedIndividual);
        }

        return mutatedPopulation;
    }

    // ===================== 辅助方法：获取当前种群最优个体 =====================
    private Map<Integer, Integer> getCurrentPopulationBest(Map<Map<Integer, Integer>, Double> fitnessMap) {
        return fitnessMap.entrySet().stream()
                .min(Map.Entry.comparingByValue()) // 成本型：min，收益型改为max
                .map(Map.Entry::getKey)
                .orElse(null);
    }
}