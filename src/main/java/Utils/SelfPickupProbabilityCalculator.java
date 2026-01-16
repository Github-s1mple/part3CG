package Utils;

import java.util.HashMap;
import java.util.Map;

/**
 * 基于距离计算用户选择自提的概率
 * 核心逻辑：距离越近，自提概率越高；超过阈值后概率急剧下降（符合"距离衰减效应"）
 */
public class SelfPickupProbabilityCalculator {

    // 距离分段阈值（单位：公里）及对应基础概率（可根据业务数据调整）
    private static final Map<Double, Double> DISTANCE_PROBABILITY_BASE = new HashMap<>();
    // 距离衰减系数（每增加1公里，概率的衰减比例）
    private static final double DECAY_RATE = 0.12;
    // 最大自提可接受距离（超过此距离，自提概率为0）
    private static final double MAX_ACCEPTABLE_DISTANCE = 20.0;
    // 自提总量占比
    private static final double TOTAL_SELF = 0.5;
    static {
        // 初始化距离分段基础概率：近距离基础概率高，中距离逐步降低
        DISTANCE_PROBABILITY_BASE.put(1.0, 0.85);   // ≤1公里：85%基础概率
        DISTANCE_PROBABILITY_BASE.put(3.0, 0.65);   // 1-3公里：65%基础概率
        DISTANCE_PROBABILITY_BASE.put(5.0, 0.45);   // 3-5公里：45%基础概率
        DISTANCE_PROBABILITY_BASE.put(10.0, 0.20);  // 5-10公里：20%基础概率
        DISTANCE_PROBABILITY_BASE.put(MAX_ACCEPTABLE_DISTANCE, 0.05);  // 10-20公里：5%基础概率
    }

    /**
     * 计算用户选择自提的概率
     * @param distance 仓库到用户点的直线距离（单位：公里，需≥0）
     * @return 自提概率（范围：0.0~1.0）
     */
    public static double calculateSelfPickupProbability(double distance) {
        // 输入校验：距离为负数时返回0（无效距离）
        if (distance < 0) {
            throw new IllegalArgumentException("距离不能为负数");
        }

        // 超过最大可接受距离，自提概率为0
        if (distance >= MAX_ACCEPTABLE_DISTANCE) {
            return 0.0;
        }

        // 确定当前距离所在区间的基础概率
        double baseProbability = getBaseProbabilityByDistance(distance);

        // 计算距离衰减后的概率：基础概率 × (1 - 衰减系数)^(超出区间起始的距离)
        double intervalStart = getIntervalStart(distance);
        double excessDistance = distance - intervalStart;
        double decayFactor = Math.pow(1 - DECAY_RATE, excessDistance);
        double finalProbability = baseProbability * decayFactor;

        // 确保概率在0~1之间
        return Math.max(0.0, Math.min(1.0, finalProbability));
    }

    /**
     * 根据距离获取所在区间的基础概率
     */
    private static double getBaseProbabilityByDistance(double distance) {
        if (distance <= 1.0) {
            return DISTANCE_PROBABILITY_BASE.get(1.0) * TOTAL_SELF;
        } else if (distance <= 3.0) {
            return DISTANCE_PROBABILITY_BASE.get(3.0) * TOTAL_SELF;
        } else if (distance <= 5.0) {
            return DISTANCE_PROBABILITY_BASE.get(5.0) * TOTAL_SELF;
        } else if (distance <= 10.0) {
            return DISTANCE_PROBABILITY_BASE.get(10.0) * TOTAL_SELF;
        } else {
            return DISTANCE_PROBABILITY_BASE.get(MAX_ACCEPTABLE_DISTANCE) * TOTAL_SELF;
        }
    }

    /**
     * 获取当前距离所在区间的起始值（用于计算超出距离）
     */
    private static double getIntervalStart(double distance) {
        if (distance <= 1.0) {
            return 0.0;
        } else if (distance <= 3.0) {
            return 1.0;
        } else if (distance <= 5.0) {
            return 3.0;
        } else if (distance <= 10.0) {
            return 5.0;
        } else {
            return 10.0;
        }
    }


    // 测试示例
    public static void main(String[] args) {
        double[] testDistances = {0.5, 2.0, 4.0, 8.0, 15.0, 25.0};
        for (double distance : testDistances) {
            double probability = calculateSelfPickupProbability(distance);
            System.out.printf("距离：%.1f公里 → 自提概率：%.2f%%\n",
                    distance, probability * 100);
        }
    }
}