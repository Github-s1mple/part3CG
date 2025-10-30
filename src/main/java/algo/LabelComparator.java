package algo;

import java.util.Comparator;

/**
 * 标签优先级比较器：用于 PriorityQueue 排序，决定标签扩展的优先顺序
 * 核心逻辑：优先扩展"潜力更高"的标签（距离更短、卸货量更合理的标签）
 */
public class LabelComparator implements Comparator<Label> {

    /**
     * 比较两个标签的优先级
     * @param o1 标签1
     * @param o2 标签2
     * @return 排序结果：负数表示 o1 优先级高于 o2（o1 应先扩展），正数则相反
     */
    @Override
    public int compare(Label o1, Label o2) {
        // 核心排序逻辑：按"距离越短优先级越高"排序（优先扩展短路径标签）
        // 若距离相同，按"卸货量越大优先级越高"排序（优先扩展负载更合理的标签）
        if (o1.getTravelDistance() != o2.getTravelDistance()) {
            // 距离短的标签优先（升序排列）
            return Double.compare(o1.getTravelDistance(), o2.getTravelDistance());
        } else {
            // 距离相同则卸货量大的优先（降序排列）
            return Double.compare(o2.getLoadedQuantity(), o1.getLoadedQuantity());
        }
    }
}