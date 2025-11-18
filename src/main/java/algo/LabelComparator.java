package algo;

import java.util.Comparator;
import java.util.Map;

/**
 * 标签优先级比较器：仓库平衡权重远高于其他标准
 * 核心逻辑：先按仓库标签数量差异划分优先级，差距越大，数量少的仓库优先级越高；
 * 仅当仓库平衡程度接近时，才用距离和负载微调。
 */
public class LabelComparator implements Comparator<Label> {
    private final Map<Integer, Integer> depotExpandCount;

    public LabelComparator(Map<Integer, Integer> depotExpandCount) {
        this.depotExpandCount = depotExpandCount;
    }

    @Override
    public int compare(Label o1, Label o2) {
        Integer depot1 = o1.getStartDepotIdx();
        Integer depot2 = o2.getStartDepotIdx();

        int count1 = depotExpandCount.getOrDefault(depot1, 0);
        int count2 = depotExpandCount.getOrDefault(depot2, 0);

        // 核心修正：次数少的仓库，返回-1（优先级更高）
        if (count1 < count2) {
            System.out.println("比较：仓库" + depot1 + "(" + count1 + "次) < 仓库" + depot2 + "(" + count2 + "次) → " + depot1 + "优先");
            return -1;
        } else if (count1 > count2) {
            System.out.println("比较：仓库" + depot1 + "(" + count1 + "次) > 仓库" + depot2 + "(" + count2 + "次) → " + depot2 + "优先");
            return 1; // 次数多的返回1，优先级更低
        } else {
            // 次数相等时，保留原距离逻辑（可选）
            System.out.println("比较：仓库" + depot1 + "与" + depot2 + "次数相等，按距离排序");
            return Double.compare(o1.getTravelDistance(), o2.getTravelDistance());
        }
    }
}