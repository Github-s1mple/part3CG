package Stages;

public class IterationImprovementMetrics {
    double bestImprovementValue; // 最优适应度提升值（负值=优化）
    double bestImprovementRate;  // 最优适应度提升率（负值=优化）
    double avgImprovementValue;  // 平均适应度提升值（负值=优化）
    double avgImprovementRate;   // 平均适应度提升率（负值=优化）

    public static IterationImprovementMetrics calculateIterationImprovement(
            double lastBest, double currentBest,
            double lastAvg, double currentAvg
    ) {
        IterationImprovementMetrics metrics = new IterationImprovementMetrics();

        // 1. 最优适应度提升（适应度是成本，值越小越好）
        metrics.bestImprovementValue = currentBest - lastBest;
        metrics.bestImprovementRate = lastBest == 0 ? 0.0 :
                (metrics.bestImprovementValue / lastBest);

        // 2. 平均适应度提升
        metrics.avgImprovementValue = currentAvg - lastAvg;
        metrics.avgImprovementRate = lastAvg == 0 ? 0.0 :
                (metrics.avgImprovementValue / lastAvg);

        return metrics;
    }
}