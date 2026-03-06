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

import static Utils.CommonUtils.getKeyString;

/**
 * 【重构版 - 即时存档】针对高成本求解器的激进遗传算法
 *
 * 核心改进策略：
 * 1. 动态高变异率 + 停滞重启机制。
 * 2. 【新增】每轮迭代后立即追加保存日志，防止意外中断导致数据丢失。
 */
public class GAIteration {
    private Scenarios scenarios;
    private double finalBestCost;
    private Map<Integer, Integer> bestSolution;
    private List<Integer> allCandidateIds;

    // 分组映射
    private Map<Integer, Integer> candidateToGroupMap;
    private Map<Integer, List<Integer>> groupToCandidatesMap;
    private int totalGroups;

    // ===================== GA 核心配置参数 =====================
    private int gaPopulationSize = 6;
    private double gaCrossoverRate = 0.8;
    private int gaElitismCount = 1;

    // 动态变异参数
    private double baseMutationRate = 0.40;
    private double minMutationRate = 0.10;
    private int maxGroupsToMutateRatio = 30;

    private int maxGaIterations = 25;

    // 停滞控制
    private int stagnationThreshold = 3;
    private int zeroProgressCount = 0;
    private double lastBestFitness = Double.MAX_VALUE;

    // 第一阶段模型相关
    private FirstStageLocationModel firstStage;
    private InputData inputData;

    // ===================== 【修改】文件配置 =====================
    private static final String OUTPUT_DIR = "ga_results";
    private static final String FILE_PREFIX = "GA_Aggressive_Log_";
    private File currentLogFile = null; // 持有一个文件引用，避免重复创建文件名
    private boolean fileHeaderWritten = false; // 标记表头是否已写入
    private final Random random = new Random();

    public GAIteration(Scenarios scenarios) {
        this.scenarios = scenarios;
        this.bestSolution = new HashMap<>();
        this.finalBestCost = Double.MAX_VALUE;

        // 初始化输出目录
        File dir = new File(OUTPUT_DIR);
        if (!dir.exists()) dir.mkdirs();
    }

    public void solve() {
        try {
            this.inputData = new InputData(true);
            this.allCandidateIds = this.inputData.getCandidates().getCandidateIndexes();

            if (!initGroupMapping()) {
                System.err.println("错误：候选点数量 (" + allCandidateIds.size() + ") 不能被 4 整除。");
                return;
            }

            // 【修改】生成唯一的日志文件名 (在开始前确定)
            SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMdd_HHmmss");
            String fileName = FILE_PREFIX + sdf.format(new Date()) + ".txt";
            this.currentLogFile = new File(OUTPUT_DIR, fileName);

            System.out.println("\n[激进模式配置] 分组数: " + totalGroups + ", 种群大小: " + gaPopulationSize
                    + ", 精英数: " + gaElitismCount + ", 最大迭代: " + maxGaIterations);
            System.out.println("[日志策略] 每轮迭代即时追加保存至: " + currentLogFile.getAbsolutePath());

            Map<Map<Integer, Integer>, Double> fitnessMap = new HashMap<>();

            // 1. 初始化种群
            System.out.println("\n生成高多样性初始群落...");
            List<Map<Integer, Integer>> population = initGAPopulation();

            // 2. GA 迭代主循环
            System.out.println("开始激进迭代...");
            int gaIter = 0;
            boolean triggeredRestart = false;

            for (gaIter = 1; gaIter <= maxGaIterations; gaIter++) {
                long gaIterStartTime = System.currentTimeMillis();
                System.out.println("\n---------------------- GA第 " + gaIter + " / " + maxGaIterations + " 次迭代 ----------------------");

                // 2.1 评估适应度
                fitnessMap = evaluatePopulationFitness(population, fitnessMap);

                Map<Integer, Integer> currentBestInd = getCurrentPopulationBest(fitnessMap);
                if (currentBestInd == null) {
                    System.err.println("当前代无有效个体，终止迭代。");
                    break;
                }

                double currentBestFitness = fitnessMap.get(currentBestInd);
                double currentAvgFitness = calculatePopulationAvgFitness(fitnessMap);
                double diversity = calculateDiversity(population);

                // 2.2 更新全局最优
                boolean improved = updateGlobalBestSolution(fitnessMap);

                // 2.3 停滞检测与处理
                if (!improved || Math.abs(currentBestFitness - lastBestFitness) < 1e-6) {
                    zeroProgressCount++;
                } else {
                    zeroProgressCount = 0;
                }
                lastBestFitness = currentBestFitness;

                // 停滞处理：触发部分重启
                if (zeroProgressCount >= stagnationThreshold) {
                    System.out.println("⚠️ 检测到停滞 (" + zeroProgressCount + "代)! 触发【部分重启机制】...");
                    population = performPartialRestart(population, currentBestInd, fitnessMap);
                    zeroProgressCount = 0;
                    triggeredRestart = true;
                    System.out.println(">>> 重启完成，种群多样性已恢复。");
                } else {
                    triggeredRestart = false;
                    // 正常演化
                    List<Map<Integer, Integer>> selectedPopulation = selectPopulation(fitnessMap);
                    List<Map<Integer, Integer>> crossedPopulation = crossoverPopulation(selectedPopulation);
                    population = mutatePopulation(crossedPopulation, gaIter);
                }

                long gaIterEndTime = System.currentTimeMillis();
                double gaIterCostTime = (gaIterEndTime - gaIterStartTime) / 1000.0;

                // 【关键修改】每轮迭代后立即保存记录
                saveIterationLogImmediately(gaIter, currentBestFitness, currentAvgFitness, diversity, currentBestInd, gaIterCostTime, triggeredRestart);

                System.out.println("当前最优: " + String.format("%.4f", currentBestFitness) +
                        " | 平均: " + String.format("%.4f", currentAvgFitness) +
                        " | 多样性: " + String.format("%.2f", diversity) +
                        " | 耗时: " + String.format("%.1f", gaIterCostTime/60) + " 分" +
                        (triggeredRestart ? " [已重启]" : ""));
            }

            // 3. 输出最终结果摘要 (单独保存一份最终最优解详情)
            System.out.println("\n========================================");
            System.out.println("优化全流程结束 (总迭代: " + gaIter + ")");
            System.out.println("最终最优解 ID: " + bestSolution.keySet());
            System.out.println("最终最优总成本: " + String.format("%.6f", finalBestCost));
            System.out.println("========================================");

            // 保存最终解的详细信息到单独文件
            saveFinalSolutionDetails(gaIter);

            if (firstStage != null) firstStage.releaseResources();

        } catch (Exception e) {
            System.err.println("求解过程发生严重异常:");
            e.printStackTrace();
            // 即使异常，之前的迭代数据也已经保存在日志中了
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

    // ===================== 多样性计算 =====================
    private double calculateDiversity(List<Map<Integer, Integer>> population) {
        if (population.size() < 2) return 0.0;
        int totalDiff = 0;
        int comparisons = 0;

        for (int i = 0; i < population.size(); i++) {
            for (int j = i + 1; j < population.size(); j++) {
                int diffCount = 0;
                Map<Integer, Integer> p1 = population.get(i);
                Map<Integer, Integer> p2 = population.get(j);

                for (int g = 0; g < totalGroups; g++) {
                    List<Integer> cands = groupToCandidatesMap.get(g);
                    Integer v1 = cands.stream().filter(p1::containsKey).findFirst().orElse(null);
                    Integer v2 = cands.stream().filter(p2::containsKey).findFirst().orElse(null);
                    if (v1 != null && v2 != null && !v1.equals(v2)) {
                        diffCount++;
                    }
                }
                totalDiff += diffCount;
                comparisons++;
            }
        }
        return comparisons == 0 ? 0 : (double) totalDiff / comparisons / totalGroups;
    }

    // ===================== 部分重启机制 =====================
    private List<Map<Integer, Integer>> performPartialRestart(List<Map<Integer, Integer>> currentPop, Map<Integer, Integer> bestInd, Map<Map<Integer, Integer>, Double> fitnessMap) {
        List<Map<Integer, Integer>> newPop = new ArrayList<>();
        newPop.add(new HashMap<>(bestInd));

        int remaining = gaPopulationSize - 1;
        for (int i = 0; i < remaining; i++) {
            if (i < remaining / 2) {
                newPop.add(generateRandomConstrainedSolution(random));
            } else {
                Map<Integer, Integer> mutated = new HashMap<>(bestInd);
                int groupsToDestroy = (int) (totalGroups * (0.4 + random.nextDouble() * 0.2));
                List<Integer> groupIndices = new ArrayList<>(groupToCandidatesMap.keySet());
                Collections.shuffle(groupIndices, random);

                for (int k = 0; k < groupsToDestroy; k++) {
                    swapInGroup(mutated, groupIndices.get(k), random);
                }
                newPop.add(mutated);
            }
        }
        return newPop;
    }

    // ===================== 【核心修改】即时保存日志 =====================
    private void saveIterationLogImmediately(int iter, double bestFit, double avgFit, double div,
                                             Map<Integer, Integer> bestSol, double timeCost, boolean restarted) {
        if (bestSol == null || currentLogFile == null) return;

        try (BufferedWriter writer = new BufferedWriter(new FileWriter(currentLogFile, true))) { // true 表示追加模式
            // 如果是第一次写入，先写表头
            if (!fileHeaderWritten) {
                writer.write("GA Aggressive Iteration Report (Real-time Append Mode)");
                writer.newLine();
                writer.write("Start Time: " + new Date().toString());
                writer.newLine();
                writer.write("--------------------------------------------------------------------------------");
                writer.newLine();
                String header = String.format("%-6s %-12s %-12s %-10s %-8s %-30s", "Iter", "Best_Cost", "Avg_Cost", "Diversity", "Restart", "Selected_IDs");
                writer.write(header);
                writer.newLine();
                fileHeaderWritten = true;
            }

            // 写入当前行数据
            String solSummary = bestSol.keySet().stream()
                    .map(String::valueOf)
                    .collect(Collectors.joining(", "));

            String line = String.format("%-6d %-12.4f %-12.4f %-10.4f %-8s %-30s",
                    iter, bestFit, avgFit, div, restarted ? "YES" : "NO", solSummary);

            writer.write(line);
            writer.newLine();
            writer.flush(); // 强制刷新缓冲区，确保立即写入磁盘

        } catch (IOException e) {
            System.err.println("警告：无法即时写入日志文件: " + e.getMessage());
        }
    }

    // ===================== 保存最终解详情 =====================
    private void saveFinalSolutionDetails(int totalIterations) {
        if (bestSolution.isEmpty() || currentLogFile == null) return;

        File finalDetailFile = new File(OUTPUT_DIR, "FINAL_BestSolution_" + currentLogFile.getName().replace(".txt", "_DETAILS.txt"));

        try (BufferedWriter writer = new BufferedWriter(new FileWriter(finalDetailFile))) {
            writer.write("=== FINAL OPTIMAL SOLUTION DETAILS ===");
            writer.newLine();
            writer.write("Total Iterations Performed: " + totalIterations);
            writer.newLine();
            writer.write("Final Best Cost: " + String.format("%.6f", finalBestCost));
            writer.newLine();
            writer.write("Timestamp: " + new Date().toString());
            writer.newLine();
            writer.write("--------------------------------------------------------------------------------");
            writer.newLine();
            writer.write("Selected Candidate IDs: " + bestSolution.keySet().stream().map(String::valueOf).collect(Collectors.joining(", ")));
            writer.newLine();
            writer.newLine();
            writer.write("Full Solution Map: " + bestSolution.toString());

            System.out.println("\n[系统] 最终最优解详情已保存至: " + finalDetailFile.getAbsolutePath());
        } catch (IOException e) {
            System.err.println("保存最终解详情失败: " + e.getMessage());
        }
    }

    // ===================== GA 初始化 =====================
    private List<Map<Integer, Integer>> initGAPopulation() {
        List<Map<Integer, Integer>> population = new ArrayList<>();
        Set<String> existedKeyStr = new HashSet<>();

        Map<Integer, Integer> originalSolution = getFilteredSolution(inputData.getInitialOj());
        if (!isValidConstrainedSolution(originalSolution)) {
            originalSolution = generateRandomConstrainedSolution(random);
        }
        addSolution(population, existedKeyStr, originalSolution, true);

        int randomCount = (int) (gaPopulationSize * 0.4);
        while (population.size() <= randomCount) {
            Map<Integer, Integer> randSol = generateRandomConstrainedSolution(random);
            addSolution(population, existedKeyStr, randSol, false);
        }

        while (population.size() < gaPopulationSize) {
            Map<Integer, Integer> neighbor = new HashMap<>(originalSolution);
            int groupsToMutate = (int) (totalGroups * (0.2 + random.nextDouble() * 0.1));
            List<Integer> groupIndices = new ArrayList<>(groupToCandidatesMap.keySet());
            Collections.shuffle(groupIndices, random);
            for (int i = 0; i < groupsToMutate; i++) {
                swapInGroup(neighbor, groupIndices.get(i), random);
            }
            addSolution(population, existedKeyStr, neighbor, false);
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

    // ===================== 评估适应度 =====================
    private Map<Map<Integer, Integer>, Double> evaluatePopulationFitness(
            List<Map<Integer, Integer>> population,
            Map<Map<Integer, Integer>, Double> knownFitnessMap) throws GRBException, IOException {

        int evaluateCount = 0;
        for (Map<Integer, Integer> individual : population) {
            if (knownFitnessMap.containsKey(individual)) {
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
            System.out.println("  -> 完成。成本: " + String.format("%.4f", totalCost) + ", 耗时: " + duration + "秒");
        }
        return knownFitnessMap;
    }

    private boolean updateGlobalBestSolution(Map<Map<Integer, Integer>, Double> fitnessMap) {
        Map.Entry<Map<Integer, Integer>, Double> bestEntry = fitnessMap.entrySet().stream()
                .min(Map.Entry.comparingByValue())
                .orElse(null);
        if (bestEntry == null) return false;

        double currentCost = bestEntry.getValue();
        if (currentCost < this.finalBestCost - 1e-6) {
            this.finalBestCost = currentCost;
            this.bestSolution = new HashMap<>(bestEntry.getKey());
            System.out.println(">>> 🚀 更新全局最优解! 成本: " + String.format("%.6f", finalBestCost));
            return true;
        }
        return false;
    }

    private List<Map<Integer, Integer>> selectPopulation(Map<Map<Integer, Integer>, Double> fitnessMap) {
        List<Map<Integer, Integer>> selected = new ArrayList<>();

        List<Map<Integer, Integer>> elite = fitnessMap.entrySet().stream()
                .sorted(Map.Entry.comparingByValue())
                .limit(gaElitismCount)
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());
        selected.addAll(elite);

        int remaining = gaPopulationSize - gaElitismCount;
        if (remaining <= 0) return selected;

        Map<Map<Integer, Integer>, Double> weightMap = new HashMap<>();
        double totalWeight = 0.0;
        for (Map.Entry<Map<Integer, Integer>, Double> entry : fitnessMap.entrySet()) {
            double f = entry.getValue();
            if (f == Double.MAX_VALUE) continue;
            double w = 1.0 / (f + 0.001);
            weightMap.put(entry.getKey(), w);
            totalWeight += w;
        }

        for (int i = 0; i < remaining; i++) {
            double r = random.nextDouble() * totalWeight;
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
        nextGen.addAll(selected.subList(0, Math.min(gaElitismCount, selected.size())));

        List<Map<Integer, Integer>> nonElite = selected.subList(Math.min(gaElitismCount, selected.size()), selected.size());
        for (int i = 0; i < nonElite.size(); i += 2) {
            if (i + 1 >= nonElite.size()) {
                nextGen.add(new HashMap<>(nonElite.get(i)));
                break;
            }
            Map<Integer, Integer> p1 = nonElite.get(i);
            Map<Integer, Integer> p2 = nonElite.get(i+1);

            if (random.nextDouble() > gaCrossoverRate) {
                nextGen.add(new HashMap<>(p1));
                nextGen.add(new HashMap<>(p2));
                continue;
            }

            Map<Integer, Integer> c1 = new HashMap<>();
            Map<Integer, Integer> c2 = new HashMap<>();

            for (int g = 0; g < totalGroups; g++) {
                boolean takeP1ForC1 = random.nextBoolean();
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

    private List<Map<Integer, Integer>> mutatePopulation(List<Map<Integer, Integer>> population, int currentIter) {
        List<Map<Integer, Integer>> mutated = new ArrayList<>();

        double progress = (double) currentIter / maxGaIterations;
        double currentMutationRate = baseMutationRate - (progress * (baseMutationRate - minMutationRate));
        double currentMutateRatio = maxGroupsToMutateRatio * (1.0 - progress * 0.5);

        System.out.println("  -> 动态变异率: " + String.format("%.2f", currentMutationRate) +
                ", 变异组比例上限: " + String.format("%.1f", currentMutateRatio) + "%");

        for (int i = 0; i < population.size(); i++) {
            if (i < gaElitismCount) {
                mutated.add(new HashMap<>(population.get(i)));
                continue;
            }

            Map<Integer, Integer> ind = new HashMap<>(population.get(i));

            if (random.nextDouble() < currentMutationRate) {
                int groupsToMutate = 1 + random.nextInt((int) (totalGroups * currentMutateRatio / 100.0) + 1);
                groupsToMutate = Math.min(groupsToMutate, totalGroups - 1);

                List<Integer> groupIndices = new ArrayList<>(groupToCandidatesMap.keySet());
                Collections.shuffle(groupIndices, random);

                for (int k = 0; k < groupsToMutate; k++) {
                    swapInGroup(ind, groupIndices.get(k), random);
                }
            }

            if (random.nextDouble() < 0.05) {
                ind = generateRandomConstrainedSolution(random);
            }

            mutated.add(ind);
        }
        return mutated;
    }

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