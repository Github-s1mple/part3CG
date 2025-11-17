package algo;

import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;

@Setter
@Getter
public class Label {
    // 核心字段（与调用参数对应）
    private final boolean isForward;          // 是否前向标签
    private final Integer curFence;               // 当前节点（仓库/卸货点索引）
    private final Label parent;               // 父标签
    private final BitSet tabu;                // 禁忌表（已访问节点）
    private final double loadedQuantity;    // 累计卸货量
    private final double travelDistance;      // 累计距离
    private final int visitNum;               // 访问卸货点数量
    private Integer startDepotIdx;                // 起点仓库索引

    // 辅助字段（路径回溯）
    private List<Integer> fenceIndexList;     // 路径节点列表


    // 私有构造方法（仅由 generate 调用）
    private Label(boolean isForward, Integer curFence, Label parent, BitSet tabu,
                  double loadedQuantity, double travelDistance, int visitNum, Integer startDepotIdx) {
        this.isForward = isForward;
        this.curFence = curFence;
        this.parent = parent;
        this.tabu = tabu;
        this.loadedQuantity = loadedQuantity;
        this.travelDistance = travelDistance;
        this.visitNum = visitNum;
        this.fenceIndexList = generatePath();
        this.startDepotIdx = startDepotIdx;
    }


    // 静态工厂方法：生成标签（与调用参数完全匹配）
    public static Label generate(boolean isForward, Integer curFence, Label parent, BitSet tabu,
                                 double loadedQuantity, double travelDistance, int visitNum, Integer startDepotIdx) {
        // 参数校验（避免无效标签）
        validateParams(curFence, tabu, loadedQuantity, travelDistance, visitNum);
        // 创建并返回标签实例
        return new Label(isForward, curFence, parent, tabu, loadedQuantity,
                travelDistance, visitNum, startDepotIdx);
    }


    // 路径生成：从父标签继承路径，追加当前节点
    private List<Integer> generatePath() {
        List<Integer> path = new ArrayList<>();
        if (parent != null) {
            // 复制父标签路径（避免引用冲突）
            path.addAll(parent.getFenceIndexList());
        }
        path.add(this.curFence); // 追加当前节点
        return path;
    }


    // 参数校验：确保核心字段合法
    private static void validateParams(Integer curFence, BitSet tabu,
                                       double unloadedQuantity, double travelDistance, int visitNum) {
        if (unloadedQuantity < 0) {
            throw new IllegalArgumentException("卸货量不能为负：" + unloadedQuantity);
        }
        if (travelDistance < 0) {
            throw new IllegalArgumentException("行驶距离不能为负：" + travelDistance);
        }
        if (visitNum < 0) {
            throw new IllegalArgumentException("访问次数不能为负：" + visitNum);
        }
    }
}