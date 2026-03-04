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
 * 基于遗传算法的两阶段迭代流程
 * 核心：GA生成一阶段初始解 → 一/二阶段求解总收益 → GA迭代优化 → 控制迭代次数取最终解
 * 新增功能：记录每轮迭代详情并导出至文件
 */
public class GAIteration {
    // 原始参数
    private Scenarios scenarios;
    private double finalBestCost; // 最终最优总收益（成本型为最小值，收益型为最大值）
    private Map<Integer, Integer> bestSolution; // 最终最优O_i解
    private List<Integer> allCandidateIds;

    // GA 核心配置参数
    private int gaPopulationSize = 10;
    private double gaCrossoverRate = 0.8;
    private double gaMutationRate = 0.1;
    private int gaElitismCount = 2;
    private double lastBestFitness = Double.MAX_VALUE;
    private double lastAvgFitness = Double.MAX_VALUE;
    private int zeroProgressCount = 0;

    // 初始化群落参数
    private double eliteRatio = 0.1;
    private double neighborRatio = 0.6;
    private double randomRatio = 0.3;
    private int neighborRange = 2;

    // 第一阶段模型相关
    private FirstStageLocationModel firstStage;
    private InputData inputData;

    // ===================== 新增：历史记录存储 =====================
    private List<IterationRecord> iterationHistory;
    private static final String OUTPUT_DIR = "ga_results"; // 输出目录
    private static final String FILE_PREFIX = "GA_Iteration_Log_"; // 文件名前缀

    // 内部类：用于存储单次迭代记录
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
            this.bestSolutionSnapshot = new HashMap<>(sol); // 深拷贝防止被后续修改影响
            this.timeCostSeconds = time;
            this.timestamp = System.currentTimeMillis();
        }
    }

    public GAIteration(Scenarios scenarios) {
        this.scenarios = scenarios;
        this.bestSolution = new HashMap<>();
        this.finalBestCost = Double.MAX_VALUE;
        this.iterationHistory = new ArrayList<>(); // 初始化历史记录列表
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

            // ===================== 2. GA 初始化：生成初始群落 =====================
            System.out.println("\n生成初始群落中（大小：" + gaPopulationSize + "）...");
            List<Map<Integer, Integer>> population = initGAPopulation();

            // ===================== 3. GA 迭代优化主循环 =====================
            System.out.println("GA开始迭代（最大迭代次数：" + Constants.MAX_ITER + "）");
            int gaIter = 1;
            for (gaIter = 1; gaIter <= Constants.MAX_ITER; gaIter++) {
                long gaIterStartTime = System.currentTimeMillis();
                System.out.println("\n---------------------- GA第" + gaIter + "次迭代 ----------------------");

                // 3.1 评估当前群落适应度
                fitnessMap = evaluatePopulationFitness(population);
                double currentBestFitness = fitnessMap.get(getCurrentPopulationBest(fitnessMap));
                double currentAvgFitness = calculatePopulationAvgFitness(fitnessMap);

                // 3.2 更新全局最优解
                updateGlobalBestSolution(fitnessMap);

                // 3.3 选择、交叉、变异
                List<Map<Integer, Integer>> selectedPopulation = selectPopulation(fitnessMap);
                List<Map<Integer, Integer>> crossedPopulation = crossoverPopulation(selectedPopulation);
                List<Map<Integer, Integer>> mutatedPopulation = mutatePopulation(crossedPopulation);

                // 3.4 计算群落变化程度
                PopulationChangeMetrics changeMetrics = calculatePopulationChange(selectedPopulation, mutatedPopulation);

                // 3.5 计算本次迭代提升效果
                IterationImprovementMetrics improveMetrics = calculateIterationImprovement(
                        lastBestFitness, currentBestFitness,
                        lastAvgFitness, currentAvgFitness
                );

                // 3.6 更新群落和历史记录
                population = mutatedPopulation;
                if (lastAvgFitness == currentBestFitness) zeroProgressCount++;
                lastBestFitness = currentBestFitness;
                lastAvgFitness = currentAvgFitness;

                // 计算耗时
                long gaIterEndTime = System.currentTimeMillis();
                double gaIterCostTime = (gaIterEndTime - gaIterStartTime) / 1000.0;

                // ===================== 新增：记录本轮迭代数据 =====================
                recordIteration(gaIter, currentBestFitness, currentAvgFitness, getCurrentPopulationBest(fitnessMap), gaIterCostTime);

                // 3.7 输出迭代详情
                System.out.println("当前迭代最优适应度（总成本）：" + String.format("%.6f", currentBestFitness));
                System.out.println("当前迭代平均适应度（总成本）：" + String.format("%.6f", currentAvgFitness));
                System.out.println("---------- 群落变化程度 ----------");
                System.out.println("选择后→变异后 个体相似度均值：" + String.format("%.4f", changeMetrics.averageSimilarity));
                System.out.println("变异后群落唯一个体占比：" + String.format("%.2f%%", changeMetrics.uniqueIndividualRatio * 100));
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

            // ===================== 新增：导出历史记录到文件 =====================
            exportResultsToFile(gaIter);

            // ===================== 5. 释放资源 =====================
            if (firstStage != null) {
                firstStage.releaseResources();
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // ===================== 新增：记录迭代数据 =====================
    private void recordIteration(int iter, double bestFit, double avgFit, Map<Integer, Integer> bestSol, double timeCost) {
        if (bestSol == null) return;
        iterationHistory.add(new IterationRecord(iter, bestFit, avgFit, bestSol, timeCost));
    }

    // ===================== 新增：导出结果到文件 =====================
    private void exportResultsToFile(int totalIterations) {
        if (iterationHistory.isEmpty()) {
            System.out.println("无迭代记录可导出。");
            return;
        }

        // 创建目录
        File dir = new File(OUTPUT_DIR);
        if (!dir.exists()) {
            dir.mkdirs();
        }

        // 生成文件名：GA_Iteration_Log_YYYYMMDD_HHMMSS.txt
        SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMdd_HHmmss");
        String fileName = FILE_PREFIX + sdf.format(new Date()) + ".txt";
        File outputFile = new File(dir, fileName);

        try (BufferedWriter writer = new BufferedWriter(new FileWriter(outputFile))) {
            writer.write("GA Iteration History Report");
            writer.newLine();
            writer.write("Generated at: " + new Date());
            writer.write("Total Iterations: " + totalIterations);
            writer.write("Final Best Cost: " + String.format("%.6f", finalBestCost));
            writer.newLine();
            writer.write("--------------------------------------------------------------------------------");
            writer.newLine();

            // 表头
            String header = String.format("%-10s %-15s %-15s %-15s %-10s %-20s",
                    "Iter", "Best_Cost", "Avg_Cost", "Time(s)", "Improvement", "Best_Solution_Summary");
            writer.write(header);
            writer.newLine();
            writer.write("--------------------------------------------------------------------------------");
            writer.newLine();

            double prevBest = Double.MAX_VALUE;

            for (IterationRecord record : iterationHistory) {
                // 计算相对于上一轮的绝对提升 (如果是第一轮则无提升)
                double improvement = (prevBest == Double.MAX_VALUE) ? 0.0 : (prevBest - record.bestFitness);
                prevBest = record.bestFitness;

                // 简化显示解：显示选中数量和前5个ID
                String solSummary = "Count:" + record.bestSolutionSnapshot.size() +
                        ", IDs:[" + record.bestSolutionSnapshot.keySet().stream()
                        .limit(5)
                        .map(String::valueOf)
                        .collect(Collectors.joining(", ")) + "...]";

                String line = String.format("%-10d %-15.6f %-15.6f %-15.2f %-10.6f %-20s",
                        record.iterationNum,
                        record.bestFitness,
                        record.avgFitness,
                        record.timeCostSeconds,
                        improvement,
                        solSummary);

                writer.write(line);
                writer.newLine();
            }

            writer.write("--------------------------------------------------------------------------------");
            writer.newLine();
            writer.write("Detailed Final Solution Map: " + bestSolution.toString());

            System.out.println("\n[系统通知] 迭代历史记录已成功保存至: " + outputFile.getAbsolutePath());

        } catch (IOException e) {
            System.err.println("[错误] 写入迭代历史记录文件失败: " + e.getMessage());
            e.printStackTrace();
        }
    }

    // ===================== GA 核心辅助方法：初始化 =====================
    private List<Map<Integer, Integer>> initGAPopulation() {
        List<Map<Integer, Integer>> population = new ArrayList<>();
        Random random = new Random();
        Set<String> existedKeyStr = new HashSet<>();

        Map<Integer, Integer> originalSolution = getFilteredSolution(inputData.getInitialOj());
        int initialCount = originalSolution.size();
        int candidateTotal = allCandidateIds.size();

        addSolution(population, existedKeyStr, originalSolution, true);

        int eliteSize = (int) (gaPopulationSize * eliteRatio);
        while (population.size() < eliteSize) {
            addSolution(population, existedKeyStr, new HashMap<>(originalSolution), false);
        }

        int neighborSize = (int) (gaPopulationSize * neighborRatio);
        while (population.size() < eliteSize + neighborSize) {
            Map<Integer, Integer> neighborSolution = new HashMap<>(originalSolution);
            int targetOnes = initialCount + (random.nextInt(2 * neighborRange + 1) - neighborRange);
            targetOnes = Math.max(1, Math.min(targetOnes, candidateTotal));
            int currentOnes = neighborSolution.size();

            if (currentOnes > targetOnes) {
                removeRandomKeys(neighborSolution, currentOnes - targetOnes, random);
            } else if (currentOnes < targetOnes) {
                addRandomKeys(neighborSolution, targetOnes - currentOnes, random);
            }

            addSolution(population, existedKeyStr, neighborSolution, false);
        }

        int randomSize = gaPopulationSize - (eliteSize + neighborSize);
        while (population.size() < gaPopulationSize && randomSize > 0) {
            Map<Integer, Integer> randomSolution = new HashMap<>();
            int randomOnes = random.nextInt(candidateTotal / 2) + 1;
            List<Integer> shuffled = new ArrayList<>(allCandidateIds);
            Collections.shuffle(shuffled, random);
            for (int i = 0; i < randomOnes; i++) {
                randomSolution.put(shuffled.get(i), 1);
            }

            if (addSolution(population, existedKeyStr, randomSolution, false)) {
                randomSize--;
            }
        }

        return population;
    }

    // ===================== GA 核心辅助方法：评估适应度 =====================
    private Map<Map<Integer, Integer>, Double> evaluatePopulationFitness(List<Map<Integer, Integer>> population) throws GRBException, IOException {
        Map<Map<Integer, Integer>, Double> fitnessMap = new HashMap<>();
        int evaluateCount = 0;
        for (Map<Integer, Integer> individual : population) {
            evaluateCount++;
            if (fitnessMap.containsKey(individual)) {
                continue;
            }

            long individualStartTime = System.currentTimeMillis();
            System.out.println("\n========================================");
            System.out.println("\n正在评估个体适应度：" + individual + "(" + evaluateCount + "/" + population.size() + ")");

            this.inputData.setInitialOj(individual);
            this.firstStage = new FirstStageLocationModel(inputData);
            firstStage.defineVariables();
            firstStage.setObjective();
            firstStage.addCoreConstraints();
            firstStage.setOutputFlag(false);

            LocationResult firstStageResult = firstStage.solve();

            if (firstStageResult == null) {
                System.err.println("个体一阶段求解失败，跳过该个体");
                fitnessMap.put(individual, Double.MAX_VALUE);
                continue;
            }
            if (Objects.equals(Constants.ALGO_MODE, "building")){
                ResultPersistenceUtil.saveFirstStageResult(firstStageResult);
            }

            System.out.println("一阶段总成本" + firstStage.getTotalCost());
            System.out.println("一阶段配送成本约" + (firstStage.getTotalCost() - firstStage.getDistinctTotalDemand() * Constants.DEPOT_STABLE_COST_PER_ORDER));

            double firstStageCost = firstStage.getTotalCost();

            double expectedSecondStageCost = 0.0;
            int scenarioCount = 1;

            for (Scenario scenario : scenarios.getScenarioList()) {
                Instance instance = new Instance(firstStageResult, scenario);
                CGSolve stage2Model = new CGSolve(instance);
                stage2Model.solve();

                double scenarioCost = stage2Model.getFinalSolver().getTotalProfit();
                expectedSecondStageCost += scenarioCost;
                scenarioCount++;
            }

            double totalCost = firstStageCost - expectedSecondStageCost;
            fitnessMap.put(new HashMap<>(individual), totalCost);

            long individualEndTime = System.currentTimeMillis();
            double individualCostTime = (individualEndTime - individualStartTime) / 1000.0;
            System.out.println("个体评估完成，总成本：" + String.format("%.6f", totalCost) + "，耗时：" + String.format("%.2f", individualCostTime) + " 秒");
        }

        return fitnessMap;
    }

    // ===================== GA 核心辅助方法：更新全局最优解 =====================
    private void updateGlobalBestSolution(Map<Map<Integer, Integer>, Double> fitnessMap) {
        Map.Entry<Map<Integer, Integer>, Double> bestEntry = fitnessMap.entrySet().stream()
                .min(Map.Entry.comparingByValue())
                .orElse(null);

        if (bestEntry == null) {
            return;
        }

        Map<Integer, Integer> currentBest = bestEntry.getKey();
        double currentBestCost = bestEntry.getValue();

        if (currentBestCost < this.finalBestCost) {
            this.finalBestCost = currentBestCost;
            this.bestSolution = new HashMap<>(currentBest);
            System.out.println("更新全局最优解，当前最优总成本：" + String.format("%.6f", finalBestCost));
        }
    }

    // ===================== GA 核心辅助方法：选择操作 =====================
    private List<Map<Integer, Integer>> selectPopulation(Map<Map<Integer, Integer>, Double> fitnessMap) {
        List<Map<Integer, Integer>> selectedPopulation = new ArrayList<>();

        List<Map<Integer, Integer>> eliteIndividuals = fitnessMap.entrySet().stream()
                .sorted(Map.Entry.comparingByValue())
                .limit(gaElitismCount)
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());
        selectedPopulation.addAll(eliteIndividuals);

        int remainingSlots = gaPopulationSize - gaElitismCount;
        Map<Map<Integer, Integer>, Double> weightMap = new HashMap<>();
        double totalWeight = 0.0;

        for (Map.Entry<Map<Integer, Integer>, Double> entry : fitnessMap.entrySet()) {
            double fitness = entry.getValue();
            if (fitness == Double.MAX_VALUE) {
                weightMap.put(entry.getKey(), 0.0);
                continue;
            }
            double weight = 1.0 / fitness;
            weightMap.put(entry.getKey(), weight);
            totalWeight += weight;
        }

        for (int i = 0; i < remainingSlots; i++) {
            double randomVal = new Random().nextDouble() * totalWeight;
            double currentWeight = 0.0;

            for (Map.Entry<Map<Integer, Integer>, Double> entry : weightMap.entrySet()) {
                currentWeight += entry.getValue();
                if (currentWeight >= randomVal) {
                    selectedPopulation.add(new HashMap<>(entry.getKey()));
                    break;
                }
            }
        }

        return selectedPopulation;
    }

    /**
     * 交叉操作
     */
    private List<Map<Integer, Integer>> crossoverPopulation(List<Map<Integer, Integer>> selectedPopulation) {
        List<Map<Integer, Integer>> crossedPopulation = new ArrayList<>();
        Random random = new Random();

        List<Map<Integer, Integer>> elite = new ArrayList<>(selectedPopulation.subList(0, gaElitismCount));
        crossedPopulation.addAll(elite);

        List<Map<Integer, Integer>> nonElite = new ArrayList<>(selectedPopulation.subList(gaElitismCount, selectedPopulation.size()));
        for (int i = 0; i < nonElite.size(); i += 2) {
            if (i + 1 >= nonElite.size()) {
                crossedPopulation.add(new HashMap<>(nonElite.get(i)));
                break;
            }

            Map<Integer, Integer> parent1 = nonElite.get(i);
            Map<Integer, Integer> parent2 = nonElite.get(i + 1);

            Set<Integer> parent1Keys = parent1.keySet();
            Set<Integer> parent2Keys = parent2.keySet();
            Set<Integer> allKeys = new HashSet<>();
            allKeys.addAll(parent1Keys);
            allKeys.addAll(parent2Keys);
            List<Integer> keyList = new ArrayList<>(allKeys);

            if (random.nextDouble() > gaCrossoverRate) {
                crossedPopulation.add(new HashMap<>(parent1));
                crossedPopulation.add(new HashMap<>(parent2));
                continue;
            }

            int crossoverPoint = random.nextInt(keyList.size());
            Map<Integer, Integer> child1 = new HashMap<>();
            Map<Integer, Integer> child2 = new HashMap<>();

            for (int j = 0; j < keyList.size(); j++) {
                Integer key = keyList.get(j);
                if (j <= crossoverPoint) {
                    if (parent1Keys.contains(key)) child1.put(key, 1);
                    if (parent2Keys.contains(key)) child2.put(key, 1);
                } else {
                    if (parent2Keys.contains(key)) child1.put(key, 1);
                    if (parent1Keys.contains(key)) child2.put(key, 1);
                }
            }

            crossedPopulation.add(child1);
            crossedPopulation.add(child2);
        }

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
            Map<Integer, Integer> mutatedIndividual = new HashMap<>(individual);

            if (mutatedPopulation.size() < gaElitismCount) {
                mutatedPopulation.add(mutatedIndividual);
                continue;
            }

            for (Integer candidateId : allCandidateIds) {
                if (random.nextDouble() <= gaMutationRate) {
                    if (mutatedIndividual.containsKey(candidateId)) {
                        mutatedIndividual.remove(candidateId);
                    } else {
                        mutatedIndividual.put(candidateId, 1);
                    }
                }
            }

            mutatedPopulation.add(mutatedIndividual);
        }

        return mutatedPopulation;
    }

    // ===================== 辅助方法 =====================
    private Map<Integer, Integer> getCurrentPopulationBest(Map<Map<Integer, Integer>, Double> fitnessMap) {
        return fitnessMap.entrySet().stream()
                .min(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(null);
    }

    private double calculatePopulationAvgFitness(Map<Map<Integer, Integer>, Double> fitnessMap) {
        return PriceCalculator.calculatePopulationAvgFitness(fitnessMap);
    }

    private Map<Integer, Integer> getFilteredSolution(Map<Integer, Integer> solution) {
        Map<Integer, Integer> filtered = new HashMap<>();
        solution.forEach((k, v) -> {
            if (v == 1) filtered.put(k, 1);
        });
        return filtered;
    }

    private void removeRandomKeys(Map<Integer, Integer> map, int n, Random random) {
        if (n <= 0 || map.isEmpty()) return;
        List<Integer> keys = new ArrayList<>(map.keySet());
        Collections.shuffle(keys, random);
        for (int i = 0; i < n && i < keys.size(); i++) {
            map.remove(keys.get(i));
        }
    }

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