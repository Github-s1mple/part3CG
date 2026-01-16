package Stages;

import Utils.PriceCalculator;
import Utils.ResultPersistenceUtil;
import algoCG.CGSolve;
import baseinfo.Constants;
import com.gurobi.gurobi.GRBException;
import impl.*;

import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

import static Stages.IterationImprovementMetrics.calculateIterationImprovement;
import static Stages.PopulationChangeMetrics.calculatePopulationChange;
import static Utils.CommonUtils.getKeyString;

/**
 * 基于遗传算法的两阶段迭代流程
 * 核心：GA生成一阶段初始解 → 一/二阶段求解总收益 → GA迭代优化 → 控制迭代次数获取最终解
 */
public class GAIteration {
    // 原始参数
    private Scenarios scenarios;
    private double finalBestCost; // 最终最优总收益（成本型为最小值，收益型为最大值）
    private Map<Integer, Integer> bestSolution; // 最终最优O_i解
    private List<Integer> allCandidateIds;

    // GA 核心配置参数
    private int gaPopulationSize = 10; // 种群大小
    private double gaCrossoverRate = 0.8; // 交叉概率
    private double gaMutationRate = 0.1; // 变异概率
    private int gaElitismCount = 2; // 精英保留数量（直接进入下一代种群）
    private double lastBestFitness = Double.MAX_VALUE;// 上一轮最优适应度
    private double lastAvgFitness = Double.MAX_VALUE;// 上一轮种群平均适应度
    private int zeroProgressCount = 0;
    // 初始化种群参数
    private double eliteRatio = 0.1;    // 精英层占比
    private double neighborRatio = 0.6; // 近邻层占比
    private double randomRatio = 0.3;   // 随机层占比
    private int neighborRange = 2;      // 近邻层1的数量波动范围（±2）

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
            this.inputData = new InputData(true);
            this.allCandidateIds = this.inputData.getCandidates().getCandidateIndexes();
            Map<Map<Integer, Integer>, Double> fitnessMap;

            // ===================== 2. GA 初始化：生成初始种群 =====================
            System.out.println("\n生成初始种群中（大小：" + gaPopulationSize + "）...");
            List<Map<Integer, Integer>> population = initGAPopulation();

            // ===================== 3. GA 迭代优化主循环 =====================
            System.out.println("GA开始迭代（最大迭代次数：" + Constants.MAX_ITER + "）");
            int gaIter = 1;
            for (gaIter = 1; gaIter <= Constants.MAX_ITER; gaIter++) {
                long gaIterStartTime = System.currentTimeMillis();
                System.out.println("\n---------------------- GA第" + gaIter + "次迭代 ----------------------");

                // 3.1 评估当前种群适应度
                fitnessMap = evaluatePopulationFitness(population);
                double currentBestFitness = fitnessMap.get(getCurrentPopulationBest(fitnessMap));
                double currentAvgFitness = calculatePopulationAvgFitness(fitnessMap);

                // 3.2 更新全局最优解
                updateGlobalBestSolution(fitnessMap);

                // 3.3 选择、交叉、变异
                List<Map<Integer, Integer>> selectedPopulation = selectPopulation(fitnessMap);
                List<Map<Integer, Integer>> crossedPopulation = crossoverPopulation(selectedPopulation);
                List<Map<Integer, Integer>> mutatedPopulation = mutatePopulation(crossedPopulation);

                // 3.4 计算种群变化程度（选择后 → 变异后）
                PopulationChangeMetrics changeMetrics = calculatePopulationChange(selectedPopulation, mutatedPopulation);

                // 3.5 计算本次迭代提升效果
                IterationImprovementMetrics improveMetrics = calculateIterationImprovement(
                        lastBestFitness, currentBestFitness,
                        lastAvgFitness, currentAvgFitness
                );

                // 3.6 更新种群和历史记录
                population = mutatedPopulation;
                if (lastAvgFitness == currentBestFitness) zeroProgressCount++;
                lastBestFitness = currentBestFitness;
                lastAvgFitness = currentAvgFitness;

                // 3.7 输出迭代详情
                long gaIterEndTime = System.currentTimeMillis();
                double gaIterCostTime = (gaIterEndTime - gaIterStartTime) / 1000.0;
                System.out.println("当前迭代最优适应度（总成本）：" + String.format("%.6f", currentBestFitness));
                System.out.println("当前迭代平均适应度（总成本）：" + String.format("%.6f", currentAvgFitness));
                System.out.println("---------- 种群变化程度 ----------");
                System.out.println("选择后→变异后 个体相似度均值：" + String.format("%.4f", changeMetrics.averageSimilarity));
                System.out.println("变异后种群唯一个体占比：" + String.format("%.2f%%", changeMetrics.uniqueIndividualRatio * 100));
                System.out.println("---------- 迭代提升效果 ----------");
                System.out.println("最优适应度提升值：" + String.format("%.6f", improveMetrics.bestImprovementValue) + "(" + String.format("%.2f%%", improveMetrics.bestImprovementRate * 100) + ")");
                System.out.println("平均适应度提升值：" + String.format("%.6f", improveMetrics.avgImprovementValue) + "(" + String.format("%.2f%%", improveMetrics.avgImprovementRate * 100) + ")");
                System.out.println("---------- 迭代耗时 ----------");
                System.out.println("当前迭代耗时：" + String.format("%.2f", gaIterCostTime) + " 秒");

                if (zeroProgressCount == 3) {
                    System.out.println("连续3轮无提升，提前结束迭代，已迭代" + gaIter + "次");
                    break;
                }
            }

            // ===================== 4. 输出最终结果 =====================
            System.out.println("\n========================================");
            System.out.println("GA迭代优化结束（总迭代次数：" + gaIter + "次）");
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
    private List<Map<Integer, Integer>> initGAPopulation() {
        List<Map<Integer, Integer>> population = new ArrayList<>();
        Random random = new Random();
        Set<String> existedKeyStr = new HashSet<>(); // 快速去重

        // 1. 第一步：处理原始初始解（精英层核心）
        Map<Integer, Integer> originalSolution = getFilteredSolution(inputData.getInitialOj());
        int initialCount = originalSolution.size(); // 初始解中1的数量
        int candidateTotal = allCandidateIds.size(); // 所有候选数量

        // 1.1 加入原始初始解（精英层第一个个体）
        addSolution(population, existedKeyStr, originalSolution, true);

        // 1.2 补充精英层剩余个体（复制原始解，保证权重）
        int eliteSize = (int) (gaPopulationSize * eliteRatio);
        while (population.size() < eliteSize) {
            addSolution(population, existedKeyStr, new HashMap<>(originalSolution), false);
        }

        // 2. 第二步：生成近邻层（围绕初始解小幅扰动，60%）
        int neighborSize = (int) (gaPopulationSize * neighborRatio);
        while (population.size() < eliteSize + neighborSize) {
            Map<Integer, Integer> neighborSolution = new HashMap<>(originalSolution);

            // 2.1 调整1的数量（initialCount ± neighborRange）
            int targetOnes = initialCount + (random.nextInt(2 * neighborRange + 1) - neighborRange);
            targetOnes = Math.max(1, Math.min(targetOnes, candidateTotal)); // 边界保护
            int currentOnes = neighborSolution.size();

            // 2.2 调整数量：多退少补（小幅扰动）
            if (currentOnes > targetOnes) {
                // 多了：随机移除（currentOnes - targetOnes）个1
                removeRandomKeys(neighborSolution, currentOnes - targetOnes, random);
            } else if (currentOnes < targetOnes) {
                // 少了：随机添加（targetOnes - currentOnes）个0→1
                addRandomKeys(neighborSolution, targetOnes - currentOnes, random);
            }

            addSolution(population, existedKeyStr, neighborSolution, false);
        }

        // 3. 第三步：生成随机层（保证全局多样性，30%）
        int randomSize = gaPopulationSize - (eliteSize + neighborSize);
        while (population.size() < gaPopulationSize && randomSize > 0) {
            Map<Integer, Integer> randomSolution = new HashMap<>();

            // 3.1 随机确定1的数量（1 ~ 候选总数/2，避免全1）
            int randomOnes = random.nextInt(candidateTotal / 2) + 1;
            // 3.2 随机选候选设为1
            List<Integer> shuffled = new ArrayList<>(allCandidateIds);
            Collections.shuffle(shuffled, random);
            for (int i = 0; i < randomOnes; i++) {
                randomSolution.put(shuffled.get(i), 1);
            }

            // 3.3 去重后加入种群
            if (addSolution(population, existedKeyStr, randomSolution, false)) {
                randomSize--;
            }
        }

        return population;
    }


    // ===================== GA 核心辅助方法：评估种群适应度 =====================
    /**
     * 评估种群中每个个体的适应度（即两阶段总收益/总成本）
     * 流程：个体（一阶段初始解）→ 一阶段求解 → 多场景二阶段求解 → 计算期望收益 → 总收益=一阶段成本+二阶段期望成本
     */
    private Map<Map<Integer, Integer>, Double> evaluatePopulationFitness(List<Map<Integer, Integer>> population) throws GRBException, IOException {
        Map<Map<Integer, Integer>, Double> fitnessMap = new HashMap<>();
        int evaluateCount = 0;
        for (Map<Integer, Integer> individual : population) {
            evaluateCount++;
            if (fitnessMap.containsKey(individual)) {
                continue; // 避免重复评估
            }

            long individualStartTime = System.currentTimeMillis();
            System.out.println("\n========================================");
            System.out.println("\n正在评估个体适应度：" + individual + "(" + evaluateCount + "/" + population.size() + ")");

            // ---------- 步骤1：将个体作为一阶段初始解，求解一阶段模型 ----------
            this.inputData.setInitialOj(individual);
            this.firstStage = new FirstStageLocationModel(inputData);
            // 初始化第一阶段模型（定义变量、设置目标、添加核心约束）
            firstStage.defineVariables();
            firstStage.setObjective();
            firstStage.addCoreConstraints();
            firstStage.setOutputFlag(false);

            // 求解一阶段模型
            LocationResult firstStageResult = firstStage.solve();

            if (firstStageResult == null) {
                System.err.println("个体一阶段求解失败，跳过该个体");
                fitnessMap.put(individual, Double.MAX_VALUE); // 无效个体设为极大成本（被淘汰）

                continue;
            }
            if (Objects.equals(Constants.ALGO_MODE, "building")){
                ResultPersistenceUtil.saveFirstStageResult(firstStageResult);
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
                //expectedSecondStageCost += scenarioCost * scenario.getProbability();

                // 单场景调试用
                expectedSecondStageCost += scenarioCost;

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
        // 找到当前种群中适应度最优的个体（成本型取最小值）
        Map.Entry<Map<Integer, Integer>, Double> bestEntry = fitnessMap.entrySet().stream()
                .min(Map.Entry.comparingByValue())
                .orElse(null);

        if (bestEntry == null) {
            return;
        }

        Map<Integer, Integer> currentBest = bestEntry.getKey();
        double currentBestCost = bestEntry.getValue();

        // 更新全局最优
        if (currentBestCost < this.finalBestCost) {
            this.finalBestCost = currentBestCost;
            this.bestSolution = new HashMap<>(currentBest);
            System.out.println("更新全局最优解，当前最优总成本：" + String.format("%.6f", finalBestCost));
        }
    }

    // ===================== GA 核心辅助方法：选择操作（精英保留+轮盘赌） =====================
    /**
     * 选择下一代种群的父代：精英直接保留 + 轮盘赌选择剩余个体
     */
    private List<Map<Integer, Integer>> selectPopulation(Map<Map<Integer, Integer>, Double> fitnessMap) {
        List<Map<Integer, Integer>> selectedPopulation = new ArrayList<>();

        // ---------- 步骤1：精英保留（直接选择适应度最优的N个个体） ----------
        List<Map<Integer, Integer>> eliteIndividuals = fitnessMap.entrySet().stream()
                .sorted(Map.Entry.comparingByValue())
                .limit(gaElitismCount)
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());
        selectedPopulation.addAll(eliteIndividuals);

        // ---------- 步骤2：轮盘赌选择剩余个体（适应度越好，被选中概率越高） ----------
        int remainingSlots = gaPopulationSize - gaElitismCount;
        // 计算适应度倒数
        Map<Map<Integer, Integer>, Double> weightMap = new HashMap<>();
        double totalWeight = 0.0;

        for (Map.Entry<Map<Integer, Integer>, Double> entry : fitnessMap.entrySet()) {
            double fitness = entry.getValue();
            if (fitness == Double.MAX_VALUE) {
                weightMap.put(entry.getKey(), 0.0); // 无效个体权重为0
                continue;
            }
            double weight = 1.0 / fitness;
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

    /**
     * 交叉操作：对选中的种群进行单点交叉，生成新个体
     */
    private List<Map<Integer, Integer>> crossoverPopulation(List<Map<Integer, Integer>> selectedPopulation) {
        List<Map<Integer, Integer>> crossedPopulation = new ArrayList<>();
        Random random = new Random();

        // 1. 保留精英个体（深拷贝，避免视图问题）
        List<Map<Integer, Integer>> elite = new ArrayList<>(selectedPopulation.subList(0, gaElitismCount));
        crossedPopulation.addAll(elite);

        // 2. 处理非精英个体的两两交叉
        List<Map<Integer, Integer>> nonElite = new ArrayList<>(selectedPopulation.subList(gaElitismCount, selectedPopulation.size()));
        for (int i = 0; i < nonElite.size(); i += 2) {
            // 边界：奇数个个体直接保留最后一个
            if (i + 1 >= nonElite.size()) {
                crossedPopulation.add(new HashMap<>(nonElite.get(i)));
                break;
            }

            Map<Integer, Integer> parent1 = nonElite.get(i);
            Map<Integer, Integer> parent2 = nonElite.get(i + 1);

            // 提取父代的key集合（核心基因）
            Set<Integer> parent1Keys = parent1.keySet();
            Set<Integer> parent2Keys = parent2.keySet();
            // 合并所有key作为交叉的基础（避免丢失基因）
            Set<Integer> allKeys = new HashSet<>();
            allKeys.addAll(parent1Keys);
            allKeys.addAll(parent2Keys);
            List<Integer> keyList = new ArrayList<>(allKeys); // 转为有序列表

            // 3. 随机决定是否交叉
            if (random.nextDouble() > gaCrossoverRate) {
                crossedPopulation.add(new HashMap<>(parent1));
                crossedPopulation.add(new HashMap<>(parent2));
                continue;
            }

            // 4. 单点交叉（基于key的存在性）
            int crossoverPoint = random.nextInt(keyList.size());
            Map<Integer, Integer> child1 = new HashMap<>();
            Map<Integer, Integer> child2 = new HashMap<>();

            for (int j = 0; j < keyList.size(); j++) {
                Integer key = keyList.get(j);
                if (j <= crossoverPoint) {
                    // 交叉点前：child1继承parent1的key（有则加1，无则不加），child2继承parent2的key
                    if (parent1Keys.contains(key)) {
                        child1.put(key, 1);
                    }
                    if (parent2Keys.contains(key)) {
                        child2.put(key, 1);
                    }
                } else {
                    // 交叉点后：child1继承parent2的key，child2继承parent1的key
                    if (parent2Keys.contains(key)) {
                        child1.put(key, 1);
                    }
                    if (parent1Keys.contains(key)) {
                        child2.put(key, 1);
                    }
                }
            }

            // 5. 添加子代到新种群
            crossedPopulation.add(child1);
            crossedPopulation.add(child2);
        }

        // 6. 截断到种群大小（转为新列表，避免视图问题）
        if (crossedPopulation.size() > gaPopulationSize) {
            crossedPopulation = new ArrayList<>(crossedPopulation.subList(0, gaPopulationSize));
        }

        return crossedPopulation;
    }

    // ===================== GA 核心辅助方法：变异操作 =====================
    private List<Map<Integer, Integer>> mutatePopulation(List<Map<Integer, Integer>> crossedPopulation) {
        List<Map<Integer, Integer>> mutatedPopulation = new ArrayList<>();
        Random random = new Random();

        for (Map<Integer, Integer> individual : crossedPopulation) {
            // 深拷贝个体，避免修改原数据
            Map<Integer, Integer> mutatedIndividual = new HashMap<>(individual);

            // 1. 精英个体不参与变异，直接保留
            if (mutatedPopulation.size() < gaElitismCount) {
                mutatedPopulation.add(mutatedIndividual);
                continue;
            }

            // 2. 遍历所有候选点（而非仅当前个体的key），执行变异
            for (Integer candidateId : allCandidateIds) {
                // 按变异概率决定是否翻转当前点的状态
                if (random.nextDouble() <= gaMutationRate) {
                    // 翻转0/1状态：存在（值为1）则移除/设为0，不存在（值为0）则添加/设为1
                    if (mutatedIndividual.containsKey(candidateId)) {
                        // 情况1：当前是1 → 翻转为0（直接移除key，因为你的存储只保留1）
                        mutatedIndividual.remove(candidateId);
                        // 如果你想显式存储0，可改为：mutatedIndividual.put(candidateId, 0);
                    } else {
                        // 情况2：当前是0 → 翻转为1（添加key，值为1）
                        mutatedIndividual.put(candidateId, 1);
                    }
                }
            }

            // 3. 将变异后的个体加入新种群
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

    private double calculatePopulationAvgFitness(Map<Map<Integer, Integer>, Double> fitnessMap) {
        return PriceCalculator.calculatePopulationAvgFitness(fitnessMap);
    }

    /**
     * 过滤解：仅保留值为1的键值对
     */
    private Map<Integer, Integer> getFilteredSolution(Map<Integer, Integer> solution) {
        Map<Integer, Integer> filtered = new HashMap<>();
        solution.forEach((k, v) -> {
            if (v == 1) filtered.put(k, 1);
        });
        return filtered;
    }

    /**
     * 随机移除Map中的n个key
     */
    private void removeRandomKeys(Map<Integer, Integer> map, int n, Random random) {
        if (n <= 0 || map.isEmpty()) return;
        List<Integer> keys = new ArrayList<>(map.keySet());
        Collections.shuffle(keys, random);
        for (int i = 0; i < n && i < keys.size(); i++) {
            map.remove(keys.get(i));
        }
    }

    /**
     * 随机添加n个未包含的key（值为1）
     */
    private void addRandomKeys(Map<Integer, Integer> map, int n, Random random) {
        if (n <= 0) return;
        List<Integer> notInMap = new ArrayList<>();
        for (Integer id : allCandidateIds) {
            if (!map.containsKey(id)) {
                notInMap.add(id);
            }
        }
        if (notInMap.isEmpty()) return;

        Collections.shuffle(notInMap, random);
        for (int i = 0; i < n && i < notInMap.size(); i++) {
            map.put(notInMap.get(i), 1);
        }
    }

    /**
     * 去重并添加解到种群（返回是否成功添加）
     */
    private boolean addSolution(List<Map<Integer, Integer>> population,
                                Set<String> existedKeyStr,
                                Map<Integer, Integer> solution,
                                boolean needDeduplicate) {
        if (!needDeduplicate) {
            population.add(solution);
            return true;
        }
        String keyStr = getKeyString(solution);
        if (!existedKeyStr.contains(keyStr)) {
            population.add(solution);
            existedKeyStr.add(keyStr);
            return true;
        }
        return false;
    }
}