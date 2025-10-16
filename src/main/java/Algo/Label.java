package algo;

import lombok.Getter;
import lombok.Setter;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;

/**
 * 标签类：适配只卸问题的双向标签搜索，使用Lombok简化getter/setter
 */
@Getter
@Setter
public class Label {
    // 核心状态字段（只卸问题适配）
    private final boolean isForward;          // 是否为前向标签（true=从起点扩展，false=从终点反向扩展）
    private final int curFence;               // 当前所在围栏索引
    private final Label parent;               // 父标签（用于回溯路径）
    private final BitSet tabu;                // 禁忌表（已访问围栏）
    private double unloadedQuantity;          // 累计卸货量（核心字段）
    private double travelDistance;            // 累计行驶距离
    private int visitNum;                     // 累计访问卸货点数量
    private String rfId;                      // 所属区域ID
    private int minUnloadNum;                 // 累计最小卸货数
    private int smallLoadedN;                 // 累计小型卸货点数量

    // 辅助字段
    private List<Integer> fenceIndexList;     // 路径缓存（从起点/终点到当前围栏）
    private double dualCost;                  // 列生成reduced cost

    /**
     * 构造方法：直接初始化核心字段，通过setter补充非必要字段
     * @param isForward 是否前向标签
     * @param curFence 当前围栏索引
     * @param parent 父标签
     * @param tabu 禁忌表（需提前克隆）
     */
    public Label(boolean isForward, int curFence, Label parent, BitSet tabu) {
        this.isForward = isForward;
        this.curFence = curFence;
        this.parent = parent;
        this.tabu = tabu;

        // 初始化默认值
        this.unloadedQuantity = 0.0;
        this.travelDistance = 0.0;
        this.visitNum = 0;
        this.minUnloadNum = 0;
        this.smallLoadedN = 0;
        this.fenceIndexList = generateFenceIndexList();
        this.dualCost = 0.0;

        // 校验禁忌表必须包含当前围栏
        if (!tabu.get(curFence)) {
            throw new IllegalArgumentException("禁忌表必须包含当前围栏: " + curFence);
        }
    }

    /**
     * 生成路径缓存：从父标签继承路径并添加当前围栏
     */
    private List<Integer> generateFenceIndexList() {
        List<Integer> path = new ArrayList<>();
        if (parent != null) {
            path.addAll(parent.getFenceIndexList());
        }
        path.add(this.curFence);
        return path;
    }

    /**
     * 判断是否为初始标签（无父标签）
     */
    public boolean isInitialLabel() {
        return parent == null;
    }

    /**
     * 更新路径缓存（当父标签路径变化时调用）
     */
    public void refreshFenceIndexList() {
        this.fenceIndexList = generateFenceIndexList();
    }

    /**
     * 复制标签（用于扩展新标签时避免引用冲突）
     */
    public Label copy() {
        BitSet newTabu = (BitSet) this.tabu.clone();
        Label copy = new Label(this.isForward, this.curFence, this.parent, newTabu);
        copy.setUnloadedQuantity(this.unloadedQuantity);
        copy.setTravelDistance(this.travelDistance);
        copy.setVisitNum(this.visitNum);
        copy.setRfId(this.rfId);
        copy.setMinUnloadNum(this.minUnloadNum);
        copy.setSmallLoadedN(this.smallLoadedN);
        copy.setDualCost(this.dualCost);
        return copy;
    }

    // ========================= 内部成本计算器接口 =========================
    public interface LabelCostCalculator {
        double calculateDualCost(Label label, double[] dualValues);
    }

    /**
     * 只卸问题的成本计算器
     */
    public static class UnloadingCostCalculator implements LabelCostCalculator {
        private final double distanceCostCoeff;

        public UnloadingCostCalculator(double distanceCostCoeff) {
            this.distanceCostCoeff = distanceCostCoeff;
        }

        @Override
        public double calculateDualCost(Label label, double[] dualValues) {
            // 成本 = 距离成本 - 卸货点对偶收益
            double pathCost = label.getTravelDistance() * distanceCostCoeff;
            double dualProfit = 0.0;
            for (int fenceIdx : label.getFenceIndexList()) {
                dualProfit += dualValues[fenceIdx];
            }
            return pathCost - dualProfit;
        }
    }
}
