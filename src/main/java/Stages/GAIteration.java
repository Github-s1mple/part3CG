package Stages;

import Utils.PriceCalculator;
import Utils.ResultPersistenceUtil;
import algoCG.CGSolve;
import baseinfo.Constants;
import com.gurobi.gurobi.GRBException;
import impl.*;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.stream.Collectors;

import static Stages.IterationImprovementMetrics.calculateIterationImprovement;
import static Stages.PopulationChangeMetrics.calculatePopulationChange;
import static Utils.CommonUtils.getKeyString;

/**
 * 针对高成本求解器优化的遗传算法 (24选6, 每4选1约束)
 *
 * 优化策略：
 * 1. 小种群 (Size=5) + 少迭代 (Max=15) -> 减少总评估次数
 * 2. 强精英 (Count=2) -> 保护优质解
 * 3. 低变异 (Rate=0.05) -> 减少随机破坏
 */
public class GAIteration {
    // 原始参数
    private Scenarios scenarios;
    private double finalBestCost;
    private Map<Integer, Integer> bestSolution;
    private List<Integer> allCandidateIds;

    // 分组映射
    private Map<Integer, Integer> candidateToGroupMap;
    private Map<Integer, List<Integer>> groupToCandidatesMap;
    private int totalGroups;

    // ===================== 【关键优化】GA 核心配置参数 =====================
    // 单次求解25分钟，必须极度吝啬评估次数
    private int gaPopulationSize = 5;          // 原10 -> 降为5
    private double gaCrossoverRate = 0.9;      // 保持高交叉率
    private double gaMutationRate = 0.05;      // 原0.1 -> 降为0.05，保护好解
    private int gaElitismCount = 2;            // 原2 -> 保持2 (占种群40%)
    private int maxGaIterations = 15;          // 【新增】强制限制最大迭代次数，防止跑太久
    private double lastAvgFitness = Double.MAX_VALUE;
    private int zeroProgressCount = 0;

    // 第一阶段模型相关
    private FirstStageLocationModel firstStage;
    private InputData inputData;

    // 历史记录存储
    private List<IterationRecord> iterationHistory;
    private static final String OUTPUT_DIR = "ga_results";
    private static final String FILE_PREFIX = "GA_Optimized_Log_";

    private static class IterationRecord {
        int iterationNum;
        double bestFitness;
        double avgFitness;
        Map<Integer, Integer> bestSolutionSnapshot;
        double timeCostSeconds;
        long timestamp;

        public IterationRecord(int iter, double best, double avg, Map<Integer, Integer> sol, double time) {
            this.iterationNum = iter;
            this.bestFitness = best;
            this.avgFitness = avg;
            this.bestSolutionSnapshot = new HashMap<>(sol);
            this.timeCostSeconds = time;
            this.timestamp = System.currentTimeMillis();
        }
    }

    public GAIteration(Scenarios scenarios) {
        this.scenarios = scenarios;
        this.bestSolution = new HashMap<>();
        this.finalBestCost = Double.MAX_VALUE;
        this.iterationHistory = new ArrayList<>();
    }

    /**
     * 核心求解流程
     */
    public void solve() {
        try {
            // 1. 初始化基础数据与分组映射
            this.inputData = new InputData(true);
            this.allCandidateIds = this.inputData.getCandidates().getCandidateIndexes();

            if (!initGroupMapping()) {
                System.err.println("错误：候选点数量 (" + allCandidateIds.size() + ") 不能被 4 整除。");
                return;
            }

            System.out.println("\n[配置确认] 分组数: " + totalGroups + ", 种群大小: " + gaPopulationSize + ", 最大迭代: " + maxGaIterations);

            Map<Map<Integer, Integer>, Double> fitnessMap = new HashMap<>();

            // 2. GA 初始化
            System.out.println("\n生成初始群落...");
            List<Map<Integer, Integer>> population = initGAPopulation();

            // 3. GA 迭代优化主循环
            System.out.println("开始GA迭代...");
            int gaIter = 0;

            for (gaIter = 1; gaIter <= maxGaIterations; gaIter++) {
                long gaIterStartTime = System.currentTimeMillis();
                System.out.println("\n---------------------- GA第 " + gaIter + " / " + maxGaIterations + " 次迭代 ----------------------");

                // 3.1 评估适应度
                fitnessMap = evaluatePopulationFitness(population, fitnessMap); // 传入map以复用已计算结果

                Map<Integer, Integer> currentBestInd = getCurrentPopulationBest(fitnessMap);
                if (currentBestInd == null) {
                    System.err.println("当前代无有效个体，终止迭代。");
                    break;
                }

                double currentBestFitness = fitnessMap.get(currentBestInd);
                double currentAvgFitness = calculatePopulationAvgFitness(fitnessMap);

                // 3.2 更新全局最优
                updateGlobalBestSolution(fitnessMap);

                // 3.3 选择、交叉、变异
                List<Map<Integer, Integer>> selectedPopulation = selectPopulation(fitnessMap);
                List<Map<Integer, Integer>> crossedPopulation = crossoverPopulation(selectedPopulation);
                List<Map<Integer, Integer>> mutatedPopulation = mutatePopulation(crossedPopulation);

                // 3.5 更新状态
                population = mutatedPopulation;
                if (lastAvgFitness == currentBestFitness) zeroProgressCount++;
                lastAvgFitness = currentAvgFitness;

                long gaIterEndTime = System.currentTimeMillis();
                double gaIterCostTime = (gaIterEndTime - gaIterStartTime) / 1000.0;

                recordIteration(gaIter, currentBestFitness, currentAvgFitness, currentBestInd, gaIterCostTime);

                // 精简输出
                System.out.println("当前最优成本: " + String.format("%.4f", currentBestFitness) +
                        " | 平均成本: " + String.format("%.4f", currentAvgFitness) +
                        " | 耗时: " + String.format("%.1f", gaIterCostTime/60) + " 分");

                if (zeroProgressCount >= 3) {
                    System.out.println("连续 " + zeroProgressCount + " 轮最优解无提升，提前终止GA迭代。");
                    break;
                }
            }

            // 5. 输出最终结果
            System.out.println("\n========================================");
            System.out.println("优化全流程结束 (总迭代: " + gaIter + " + 局部搜索)");
            System.out.println("最终最优解 ID: " + bestSolution.keySet());
            System.out.println("最终最优总成本: " + String.format("%.6f", finalBestCost));
            System.out.println("========================================");

            // 6. 导出记录
            exportResultsToFile(gaIter);

            // 7. 释放资源
            if (firstStage != null) {
                firstStage.releaseResources();
            }

        } catch (Exception e) {
            System.err.println("求解过程发生严重异常:");
            e.printStackTrace();
        }
    }

    // ===================== 辅助方法：分组映射 =====================
    private boolean initGroupMapping() {
        if (allCandidateIds == null || allCandidateIds.isEmpty()) return false;

        List<Integer> sortedIds = new ArrayList<>(allCandidateIds);
        Collections.sort(sortedIds);

        if (sortedIds.size() % 4 != 0) return false;

        this.totalGroups = sortedIds.size() / 4;
        this.candidateToGroupMap = new HashMap<>();
        this.groupToCandidatesMap = new HashMap<>();

        for (int i = 0; i < sortedIds.size(); i++) {
            int groupId = i / 4;
            int candidateId = sortedIds.get(i);
            candidateToGroupMap.put(candidateId, groupId);
            groupToCandidatesMap.computeIfAbsent(groupId, k -> new ArrayList<>()).add(candidateId);
        }
        return true;
    }

    // ===================== 记录与导出 =====================
    private void recordIteration(int iter, double bestFit, double avgFit, Map<Integer, Integer> bestSol, double timeCost) {
        if (bestSol == null) return;
        iterationHistory.add(new IterationRecord(iter, bestFit, avgFit, bestSol, timeCost));
    }

    private void exportResultsToFile(int totalIterations) {
        if (iterationHistory.isEmpty()) return;
        File dir = new File(OUTPUT_DIR);
        if (!dir.exists()) dir.mkdirs();

        SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMdd_HHmmss");
        String fileName = FILE_PREFIX + sdf.format(new Date()) + ".txt";
        File outputFile = new File(dir, fileName);

        try (BufferedWriter writer = new BufferedWriter(new FileWriter(outputFile))) {
            writer.write("GA Optimized Iteration Report (High-Cost Mode)");
            writer.newLine();
            writer.write("Total GA Iters: " + totalIterations + " | Local Search: Yes");
            writer.write(" | Final Best Cost: " + String.format("%.6f", finalBestCost));
            writer.newLine();
            writer.write("--------------------------------------------------------------------------------");
            writer.newLine();

            String header = String.format("%-6s %-15s %-15s %-10s %-30s", "Iter", "Best_Cost", "Avg_Cost", "Time(m)", "Selected_IDs");
            writer.write(header);
            writer.newLine();

            for (IterationRecord record : iterationHistory) {
                String solSummary = record.bestSolutionSnapshot.keySet().stream()
                        .map(String::valueOf)
                        .collect(Collectors.joining(", "));
                String line = String.format("%-6d %-15.4f %-15.4f %-10.2f %-30s",
                        record.iterationNum, record.bestFitness, record.avgFitness, record.timeCostSeconds/60.0, solSummary);
                writer.write(line);
                writer.newLine();
            }
            writer.write("--------------------------------------------------------------------------------");
            writer.newLine();
            writer.write("Final Solution Map: " + bestSolution.toString());

            System.out.println("\n[系统] 详细日志已保存至: " + outputFile.getAbsolutePath());

        } catch (IOException e) {
            System.err.println("写入日志失败: " + e.getMessage());
        }
    }

    // ===================== GA 初始化 =====================
    private List<Map<Integer, Integer>> initGAPopulation() {
        List<Map<Integer, Integer>> population = new ArrayList<>();
        Random random = new Random();
        Set<String> existedKeyStr = new HashSet<>();

        // 1. 精英层：使用初始解
        Map<Integer, Integer> originalSolution = getFilteredSolution(inputData.getInitialOj());
        if (!isValidConstrainedSolution(originalSolution)) {
            System.out.println("警告：初始解不满足约束，生成随机合法解替代。");
            originalSolution = generateRandomConstrainedSolution(random);
        }
        addSolution(population, existedKeyStr, originalSolution, true);

        // 2. 补充精英 (复制)
        while (population.size() < gaElitismCount) {
            addSolution(population, existedKeyStr, new HashMap<>(originalSolution), false);
        }

        // 3. 近邻层 (组内扰动)
        int targetSize = (int) (gaPopulationSize * 0.6); // 60% 来自近邻
        while (population.size() < targetSize) {
            Map<Integer, Integer> neighbor = new HashMap<>(originalSolution);
            int groupsToMutate = 1 + random.nextInt(Math.min(2, totalGroups));
            List<Integer> groupIndices = new ArrayList<>(groupToCandidatesMap.keySet());
            Collections.shuffle(groupIndices, random);

            for (int i = 0; i < groupsToMutate; i++) {
                swapInGroup(neighbor, groupIndices.get(i), random);
            }
            addSolution(population, existedKeyStr, neighbor, false);
        }

        // 4. 随机层
        while (population.size() < gaPopulationSize) {
            Map<Integer, Integer> randSol = generateRandomConstrainedSolution(random);
            addSolution(population, existedKeyStr, randSol, false);
        }

        return population;
    }

    private Map<Integer, Integer> generateRandomConstrainedSolution(Random random) {
        Map<Integer, Integer> sol = new HashMap<>();
        for (int gId = 0; gId < totalGroups; gId++) {
            List<Integer> candidates = groupToCandidatesMap.get(gId);
            sol.put(candidates.get(random.nextInt(candidates.size())), 1);
        }
        return sol;
    }

    private void swapInGroup(Map<Integer, Integer> solution, int groupId, Random random) {
        List<Integer> candidates = groupToCandidatesMap.get(groupId);
        Integer currentChosen = null;
        for (Integer cand : candidates) {
            if (solution.containsKey(cand)) {
                currentChosen = cand;
                break;
            }
        }
        if (currentChosen != null) {
            solution.remove(currentChosen);
            List<Integer> others = new ArrayList<>(candidates);
            others.remove(currentChosen);
            if (!others.isEmpty()) {
                solution.put(others.get(random.nextInt(others.size())), 1);
            } else {
                solution.put(currentChosen, 1);
            }
        }
    }

    private boolean isValidConstrainedSolution(Map<Integer, Integer> solution) {
        if (solution == null || solution.size() != totalGroups) return false;
        for (int gId = 0; gId < totalGroups; gId++) {
            int count = 0;
            for (Integer cand : groupToCandidatesMap.get(gId)) {
                if (solution.containsKey(cand)) count++;
            }
            if (count != 1) return false;
        }
        return true;
    }

    // ===================== 评估适应度 (带缓存) =====================
    private Map<Map<Integer, Integer>, Double> evaluatePopulationFitness(
            List<Map<Integer, Integer>> population,
            Map<Map<Integer, Integer>, Double> knownFitnessMap) throws GRBException, IOException {

        int evaluateCount = 0;
        for (Map<Integer, Integer> individual : population) {
            // 检查缓存
            if (knownFitnessMap.containsKey(individual)) {
                System.out.println("  -> 个体已评估过，跳过: " + individual.keySet());
                continue;
            }

            evaluateCount++;
            long individualStartTime = System.currentTimeMillis();
            System.out.println("\n[昂贵评估] 正在求解个体 (" + evaluateCount + "/" + population.size() + "): " + individual.keySet());

            this.inputData.setInitialOj(individual);
            this.firstStage = new FirstStageLocationModel(inputData);
            firstStage.defineVariables();
            firstStage.setObjective();
            firstStage.addCoreConstraints();
            firstStage.setOutputFlag(false);

            LocationResult firstStageResult = firstStage.solve();

            if (firstStageResult == null) {
                System.err.println("  -> 求解失败，设为无穷大");
                knownFitnessMap.put(new HashMap<>(individual), Double.MAX_VALUE);
                continue;
            }

            if (Objects.equals(Constants.ALGO_MODE, "building")){
                ResultPersistenceUtil.saveFirstStageResult(firstStageResult);
            }

            double firstStageCost = firstStage.getTotalCost();
            double expectedSecondStageCost = 0.0;

            for (Scenario scenario : scenarios.getScenarioList()) {
                Instance instance = new Instance(firstStageResult, scenario);
                CGSolve stage2Model = new CGSolve(instance);
                stage2Model.solve();
                double scenarioCost = stage2Model.getFinalSolver().getTotalProfit();
                expectedSecondStageCost += (scenarioCost * instance.getScenarioProbability());
            }

            double totalCost = firstStageCost - expectedSecondStageCost;
            knownFitnessMap.put(new HashMap<>(individual), totalCost);

            long duration = (System.currentTimeMillis() - individualStartTime) / 1000;
            System.out.println("  -> 完成。成本: " + String.format("%.4f", totalCost) + ", 耗时: " + duration + "秒 (" + String.format("%.1f", duration/60.0) + "分)");
        }
        return knownFitnessMap;
    }


    // ===================== 其他 GA 操作 (选择/交叉/变异) =====================
    private void updateGlobalBestSolution(Map<Map<Integer, Integer>, Double> fitnessMap) {
        Map.Entry<Map<Integer, Integer>, Double> bestEntry = fitnessMap.entrySet().stream()
                .min(Map.Entry.comparingByValue())
                .orElse(null);
        if (bestEntry == null) return;

        double currentCost = bestEntry.getValue();
        if (currentCost < this.finalBestCost) {
            this.finalBestCost = currentCost;
            this.bestSolution = new HashMap<>(bestEntry.getKey());
            System.out.println(">>> 更新全局最优解! 成本: " + String.format("%.6f", finalBestCost));
        }
    }

    private List<Map<Integer, Integer>> selectPopulation(Map<Map<Integer, Integer>, Double> fitnessMap) {
        List<Map<Integer, Integer>> selected = new ArrayList<>();
        // 精英直接入选
        List<Map<Integer, Integer>> elite = fitnessMap.entrySet().stream()
                .sorted(Map.Entry.comparingByValue())
                .limit(gaElitismCount)
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());
        selected.addAll(elite);

        // 轮盘赌选择剩余
        int remaining = gaPopulationSize - gaElitismCount;
        if (remaining <= 0) return selected;

        Map<Map<Integer, Integer>, Double> weightMap = new HashMap<>();
        double totalWeight = 0.0;
        for (Map.Entry<Map<Integer, Integer>, Double> entry : fitnessMap.entrySet()) {
            double f = entry.getValue();
            if (f == Double.MAX_VALUE) continue;
            double w = 1.0 / f;
            weightMap.put(entry.getKey(), w);
            totalWeight += w;
        }

        Random rand = new Random();
        for (int i = 0; i < remaining; i++) {
            double r = rand.nextDouble() * totalWeight;
            double current = 0.0;
            for (Map.Entry<Map<Integer, Integer>, Double> entry : weightMap.entrySet()) {
                current += entry.getValue();
                if (current >= r) {
                    selected.add(new HashMap<>(entry.getKey()));
                    break;
                }
            }
        }
        return selected;
    }

    private List<Map<Integer, Integer>> crossoverPopulation(List<Map<Integer, Integer>> selected) {
        List<Map<Integer, Integer>> nextGen = new ArrayList<>();
        Random rand = new Random();

        // 精英直接复制
        nextGen.addAll(selected.subList(0, Math.min(gaElitismCount, selected.size())));

        List<Map<Integer, Integer>> nonElite = selected.subList(Math.min(gaElitismCount, selected.size()), selected.size());
        for (int i = 0; i < nonElite.size(); i += 2) {
            if (i + 1 >= nonElite.size()) {
                nextGen.add(new HashMap<>(nonElite.get(i)));
                break;
            }
            Map<Integer, Integer> p1 = nonElite.get(i);
            Map<Integer, Integer> p2 = nonElite.get(i+1);

            if (rand.nextDouble() > gaCrossoverRate) {
                nextGen.add(new HashMap<>(p1));
                nextGen.add(new HashMap<>(p2));
                continue;
            }

            Map<Integer, Integer> c1 = new HashMap<>();
            Map<Integer, Integer> c2 = new HashMap<>();

            for (int g = 0; g < totalGroups; g++) {
                boolean takeP1ForC1 = rand.nextBoolean();
                List<Integer> gCands = groupToCandidatesMap.get(g);
                Integer v1 = gCands.stream().filter(p1::containsKey).findFirst().orElse(gCands.get(0));
                Integer v2 = gCands.stream().filter(p2::containsKey).findFirst().orElse(gCands.get(0));

                if (takeP1ForC1) { c1.put(v1, 1); c2.put(v2, 1); }
                else { c1.put(v2, 1); c2.put(v1, 1); }
            }
            nextGen.add(c1);
            nextGen.add(c2);
        }

        if (nextGen.size() > gaPopulationSize) nextGen = nextGen.subList(0, gaPopulationSize);
        return nextGen;
    }

    private List<Map<Integer, Integer>> mutatePopulation(List<Map<Integer, Integer>> population) {
        List<Map<Integer, Integer>> mutated = new ArrayList<>();
        Random rand = new Random();

        for (int i = 0; i < population.size(); i++) {
            // 精英不变异
            if (i < gaElitismCount) {
                mutated.add(new HashMap<>(population.get(i)));
                continue;
            }

            Map<Integer, Integer> ind = new HashMap<>(population.get(i));
            // 低变异率
            if (rand.nextDouble() < gaMutationRate) {
                int gToMutate = rand.nextInt(totalGroups);
                swapInGroup(ind, gToMutate, rand);
            }
            mutated.add(ind);
        }
        return mutated;
    }

    // ===================== 工具方法 =====================
    private Map<Integer, Integer> getCurrentPopulationBest(Map<Map<Integer, Integer>, Double> map) {
        return map.entrySet().stream().min(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(null);
    }

    private double calculatePopulationAvgFitness(Map<Map<Integer, Integer>, Double> map) {
        return PriceCalculator.calculatePopulationAvgFitness(map);
    }

    private Map<Integer, Integer> getFilteredSolution(Map<Integer, Integer> sol) {
        if (sol == null) return new HashMap<>();
        return sol.entrySet().stream()
                .filter(e -> e.getValue() == 1)
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    private boolean addSolution(List<Map<Integer, Integer>> pop, Set<String> keys, Map<Integer, Integer> sol, boolean checkDup) {
        if (!checkDup) { pop.add(sol); return true; }
        String k = getKeyString(sol);
        if (!keys.contains(k)) { pop.add(sol); keys.add(k); return true; }
        return false;
    }
}