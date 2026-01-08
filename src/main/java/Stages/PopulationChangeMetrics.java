package Stages;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static Utils.CommonUtils.getKeyString;
import static Utils.PriceCalculator.calculateIndividualSimilarity;

public class PopulationChangeMetrics {
    double averageSimilarity; // 个体相似度均值（0~1，越小变化越大）
    double uniqueIndividualRatio; // 唯一个体占比（0~1，越大多样性越好）

    public static PopulationChangeMetrics calculatePopulationChange(
            List<Map<Integer, Integer>> beforePopulation,
            List<Map<Integer, Integer>> afterPopulation
    ) {
        PopulationChangeMetrics metrics = new PopulationChangeMetrics();

        // 1. 计算选择后→变异后的个体相似度均值（按位置匹配）
        double totalSimilarity = 0.0;
        int compareCount = Math.min(beforePopulation.size(), afterPopulation.size());
        for (int i = 0; i < compareCount; i++) {
            Map<Integer, Integer> before = beforePopulation.get(i);
            Map<Integer, Integer> after = afterPopulation.get(i);
            totalSimilarity += calculateIndividualSimilarity(before, after);
        }
        metrics.averageSimilarity = compareCount == 0 ? 1.0 : (totalSimilarity / compareCount);

        // 2. 计算变异后种群的唯一个体占比
        Set<String> uniqueKeys = new HashSet<>();
        for (Map<Integer, Integer> ind : afterPopulation) {
            uniqueKeys.add(getKeyString(ind)); // 复用你之前的特征串方法
        }
        metrics.uniqueIndividualRatio = afterPopulation.isEmpty() ? 0.0 :
                (double) uniqueKeys.size() / afterPopulation.size();

        return metrics;
    }
}