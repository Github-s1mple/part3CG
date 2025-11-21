package algo;

import Utils.CommonUtils;
import Utils.ConstraintsManager;
import Utils.PriceCalculator;
import impl.*;
import baseinfo.Constants;
import lombok.Getter;
import lombok.Setter;

import java.util.*;

import static java.lang.Math.min;
import static java.util.Collections.reverse;

@Setter
@Getter
public class BidLabeling {
    // ========================== 1. 类字段调整：适配多起点+虚拟节点逻辑 ==========================
    // 算例核心数据
    final Fences fences;
    final Carriers carriers;
    final Depots depots;

    // 算法控制参数
    private Integer timeLimit;
    private Integer orderLimit;
    private final Boolean checkFlag = true; // 过程约束检查开关
    private Boolean outputFlag = false;     // 过程信息输出开关
    private Double timeRecord = 0.0;        // 算法耗时记录

    // 统计与映射容器
    final HashMap<String, Integer> recordDict = new HashMap<>(); // 过滤原因统计
    final HashMap<String, Order> visited2order = new HashMap<>(); // 节点集→最优订单映射（去重用）

    // 标签容器
    final List<List<Label>> labelPool; // 按【节点索引】存储标签（多节点多标签）


    // 仓库队列
    private List<Integer> allDepotIndexes;
    private Map<Integer, Queue<Label>> depotForwardQueues;
    private Map<Integer, Queue<Label>> depotBackwardQueues;
    private Map<Integer, Integer> depotExpandCount;
    private Map<Integer, Integer> depotOrderCount;

    // 结果容器与辅助组件
    private List<Order> orderPool = new ArrayList<>(); // 最终订单池
    private final List<Carrier> carrierList;           // 车型列表
    private final LoadingAlgorithm loadingAlgorithm;   // 装卸方案求解器
    private final double dual_multiplier;
    private HashMap<String, Double> dualsOfRLMP; // 当前对偶信息

    // 算法运行状态
    private int startTime;  // 算法开始时间（秒级）
    private Double bestObj; // 最优订单收益（用于过程输出）


    // ========================== 2. 构造方法：初始化字段，对齐多起点逻辑 ==========================
    public BidLabeling(Instance instance) {

        // 1. 初始化算例核心数据
        this.fences = instance.getFences();
        this.carriers = instance.getCarriers();
        this.depots = instance.getDepots(); // 从实例中获取多仓库管理类
        this.carrierList = instance.getCarrierList();
        this.dual_multiplier = Constants.DUAL_MULTIPLIER;
        this.loadingAlgorithm = new LoadingAlgorithm(this);

        // 2. 初始化标签容器（关键：labelPool按节点数量初始化，避免索引越界）
        this.labelPool = new ArrayList<>();
        for (int i = 0; i < fences.getFenceNum(); i++) {
            this.labelPool.add(new ArrayList<>()); // 每个节点对应一个标签列表
        }

        // 3. 动态初始化仓库相关容器（核心适配逻辑）
        initDepotQueues();

        // 5. 调用初始化方法
        this.initialize();
        System.out.println("算法初始化完成！");
    }


    /**
     * 动态初始化仓库队列（适配任意数量仓库）
     */
    private void initDepotQueues() {
        // 从Depots获取所有仓库索引（自动适配1/N个仓库）
        this.allDepotIndexes = new ArrayList<>(depots.getDepotIndexes());
        if (allDepotIndexes.isEmpty()) {
            throw new IllegalArgumentException("Depots中未配置任何仓库，无法初始化队列");
        }

        // 初始化仓库→队列映射（每个仓库分配独立队列）
        this.depotForwardQueues = new HashMap<>(allDepotIndexes.size());
        this.depotBackwardQueues = new HashMap<>(allDepotIndexes.size());
        this.depotExpandCount = new HashMap<>(allDepotIndexes.size());
        this.depotOrderCount = new HashMap<>(allDepotIndexes.size());
        for (Integer depotIdx : allDepotIndexes) {
            depotForwardQueues.put(depotIdx, new LinkedList<>()); // 前向队列
            depotBackwardQueues.put(depotIdx, new LinkedList<>()); // 后向队列
            depotExpandCount.put(depotIdx, 0); // 初始化扩展次数为0
            depotOrderCount.put(depotIdx, 0); // 初始化扩展次数为0
        }
    }

    /* 初始化与预处理 */
    private void initialize() {
        for (Integer i : fences.getFenceIndexList()) {
            Fence fenceI = fences.getFence(i);
            for (Integer j : fenceI.getVaildArcFence()) {
                double currentDist = fenceI.getDistance(j);
                fenceI.setNearestDiffLabelDist(min(fenceI.getNearestDiffLabelDist(), currentDist));
            }
            fenceI.addFakeDepot();
        }

        for (Integer i : depots.getDepotIndexList()) {
            Depot depot = depots.getDepot(i);
            for (Integer j : depot.getValidArcFence()) {
                Fence fenceJ = fences.getFence(j);
                double currentDist = depot.getDistance(fenceJ);
                depot.setNearestDiffLabelDist(min(depot.getNearestDiffLabelDist(), currentDist));
            }
        }
    }

    /* 算法主体 */
    public List<Order> solve(HashMap<String, Double> dualsOfRLMP) {
        // 运行初始化
        this.startTime = CommonUtils.currentTimeInSecond();
        this.bestObj = 0.0;
        this.timeRecord = 0.0;
        this.dualsOfRLMP = dualsOfRLMP;
        // 更新围栏价值
        this.updateFenceValue(dualsOfRLMP);
        // 若初始orderPool超出orderLimit直接输出
        if (this.orderPool.size() >= this.orderLimit) {
            return generateOutputOrders();
        }
        // 双向标号搜索
        this.bidirectionalSearch();
        // 排序结果
        this.orderPool.sort(CommonUtils.dualComparator);
        // 展示结果
        if (this.outputFlag) {
            this.displayRecordDict();
            this.displayOrders();
            this.displayTimeRecord();
        }
        return generateOutputOrders();
    }


    public void displayRecordDict() {
        System.out.println("recordDict:");
        for (String key : this.recordDict.keySet()) {
            System.out.println("  " + key + ": " + this.recordDict.get(key));
        }
    }

    public void displayOrders() {
        System.out.println("bidLabeling orders:");
        CommonUtils.displayOrders(this.orderPool);
    }

    public void displayTimeRecord() {
        System.out.println("timeRecord: " + this.timeRecord);
    }

    /* 更新目标函数 */
    private void updateFenceValue(HashMap<String, Double> dualsOfRLMP) {
        // update fenceValue
        for (Integer fenceIndex : fences.getFenceIndexList()) {
            Fence fence = fences.getFence(fenceIndex);
            fence.setFenceValue(fence.getOriginalFenceValue() - dualsOfRLMP.get(fence.getConstName()) * dual_multiplier);
        }

        for (Order order : this.orderPool) {
            order.setReducedCost(PriceCalculator.calculateRC(order, dualsOfRLMP));
        }

        orderPool.sort(CommonUtils.dualComparator);
    }

    /* 双向标号搜索 - 适配多真实起点仓库+全卸点（强制返回起点仓库） */
    private void bidirectionalSearch() {
        int iterationCnt = 0;
        initializeMultiDepotUnloadingLabels();
        this.startTime = CommonUtils.currentTimeInSecond();
        this.bestObj = 0.0;

        while (true) {
            // 1. 选择当前扩展次数最少的仓库（核心公平逻辑）
            Integer targetDepot = selectTargetDepot();
            if (targetDepot == null) {
                // 所有仓库队列都为空，退出
                break;
            }

            // 2. 扩展目标仓库的前向队列
            Queue<Label> forwardQueue = depotForwardQueues.get(targetDepot);
            if (forwardQueue != null && !forwardQueue.isEmpty()) {
                Label label = forwardQueue.poll();
                this.labelExpand(label);
                logExpand(label);
            }

            // 3. 扩展目标仓库的后向队列
            Queue<Label> backwardQueue = depotBackwardQueues.get(targetDepot);
            if (backwardQueue != null && !backwardQueue.isEmpty()) {
                Label label = backwardQueue.poll();
                this.labelExpand(label);
                logExpand(label);
            }

            // 4. 结束条件
            if (isAllQueuesEmpty()
                    || CommonUtils.currentTimeInSecond() - this.startTime > this.timeLimit
                    || this.orderPool.size() >= this.orderLimit) {
                break;
            }

            iterationCnt++;
            if (outputFlag && iterationCnt % Constants.OUTPUT_INTERVAL == 0) {
                System.out.println("累计扩展次数：" + depotExpandCount);
            }
        }
    }

    /**
     * 选择当前扩展次数最少的仓库（适配任意数量，保证公平）
     */
    private Integer selectTargetDepot() {
        Integer targetDepot = null;
        int minExpandCount = Integer.MAX_VALUE;

        // 遍历所有仓库，找到扩展次数最少的
        for (Integer depotIdx : allDepotIndexes) {
            int count = depotExpandCount.get(depotIdx);
            // 优先选择次数最少的；次数相同时，按索引顺序选择（避免随机）
            if (count < minExpandCount) {
                minExpandCount = count;
                targetDepot = depotIdx;
            }
        }

        // 兜底：若目标仓库队列全空，切换到下一个有标签的仓库
        if (targetDepot != null && isDepotQueuesEmpty(targetDepot)) {
            for (int i = 0; i < allDepotIndexes.size(); i++) {
                int nextIdx = (allDepotIndexes.indexOf(targetDepot) + 1) % allDepotIndexes.size();
                Integer nextDepot = allDepotIndexes.get(nextIdx);
                if (!isDepotQueuesEmpty(nextDepot)) {
                    targetDepot = nextDepot;
                    break;
                }
            }
        }

        return targetDepot;
    }

    /**
     * 检查某个仓库的前后向队列是否都为空
     */
    private boolean isDepotQueuesEmpty(Integer depotIdx) {
        Queue<Label> forwardQueue = depotForwardQueues.get(depotIdx);
        Queue<Label> backwardQueue = depotBackwardQueues.get(depotIdx);
        return (forwardQueue == null || forwardQueue.isEmpty())
                && (backwardQueue == null || backwardQueue.isEmpty());
    }

    /**
     * 检查所有仓库的队列是否都为空
     */
    private boolean isAllQueuesEmpty() {
        for (Integer depotIdx : allDepotIndexes) {
            if (!isDepotQueuesEmpty(depotIdx)) {
                return false;
            }
        }
        return true;
    }

    /**
     * 扩展日志（统计累计次数）
     */
    private void logExpand(Label label) {
        Integer depotIdx = label.getStartDepotIdx();
        depotExpandCount.put(depotIdx, depotExpandCount.get(depotIdx) + 1);
    }


    // 基于Depots类初始化多仓库标签（强制起点=终点）
    private void initializeMultiDepotUnloadingLabels() {
        // 遍历所有仓库，为每个仓库创建初始标签（适配任意数量）
        for (Integer depotIdx : allDepotIndexes) {
            // 前向初始标签
            BitSet forwardTabu = new BitSet(fences.getFenceNum());
            Label forwardInit = Label.generate(
                    true,
                    0,
                    null,
                    forwardTabu,
                    0.0,
                    0.0,
                    0,
                    depotIdx
            );
            // 添加到对应仓库的前向队列
            depotForwardQueues.get(depotIdx).add(forwardInit);

            // 后向初始标签
            BitSet backwardTabu = new BitSet(fences.getFenceNum());
            Label backwardInit = Label.generate(
                    false,
                    0,
                    null,
                    backwardTabu,
                    0.0,
                    0.0,
                    0,
                    depotIdx
            );
            // 添加到对应仓库的后向队列
            depotBackwardQueues.get(depotIdx).add(backwardInit);
        }
    }

    // 标签扩展（适配新的仓库专属队列逻辑）
    private void labelExpand(Label label) {
        Fence currentFence;
        if (label.getParent() == null || label.getCurFence() == 0) {
            // 初始标签：从仓库创建999虚拟节点（截断搜索用）
            Depot depot = depots.getDepot(label.getStartDepotIdx());
            currentFence = depot.depot2Fence(999);
        } else {
            // 非初始标签：获取当前节点对应的围栏
            currentFence = fences.getFence(label.getCurFence());
        }

        boolean isForward = label.isForward();

        // 遍历当前节点的所有有效后续节点
        for (Integer nextNode : currentFence.getVaildArcFence()) {
            // 跳过禁忌节点（自身或已访问节点）
            if (label.getTabu().get(nextNode)) {
                continue;
            }

            // 处理999虚拟节点（截断搜索，尝试连接前后向标签）
            if (nextNode == 999 && label.getLoadedQuantity() >= Constants.MIN_CARRIER_LOAD) {
                // 连接逻辑：无需依赖旧的 forwardLabelPool/backwardLabelPool，直接从标签池全局搜索
                if (isForward) {
                    // 前向标签：遍历所有后向标签尝试连接
                    for (List<Label> nodeLabels : labelPool) {
                        for (Label backwardLabel : nodeLabels) {
                            if (!backwardLabel.isForward()) { // 确保是后向标签
                                this.labelConnect(label, backwardLabel);
                            }
                        }
                    }
                } else {
                    // 后向标签：遍历所有前向标签尝试连接
                    for (List<Label> nodeLabels : labelPool) {
                        for (Label forwardLabel : nodeLabels) {
                            if (forwardLabel.isForward()) { // 确保是前向标签
                                this.labelConnect(forwardLabel, label);
                            }
                        }
                    }
                }
            }
            // 处理非虚拟节点（生成新标签并加入对应仓库的队列）
            else if (nextNode != 999) {
                Fence nextFence = fences.getFence(nextNode);

                // 访问次数约束（仅卸货点计数，避免超过上限）
                int newVisitNum = label.getVisitNum() + 1;
                if (newVisitNum > Constants.MAX_VISIT_NUM / 2) {
                    continue;
                }

                // 卸货量约束（累计卸货量不超过最大容量的一半）
                double newLoad = label.getLoadedQuantity() + nextFence.getDeliverDemand();
                if (newLoad > Constants.MAX_CAPACITY / 2.0) {
                    continue;
                }

                // 距离约束（累计距离不超过最大距离的一半）
                double distance_ = currentFence.getDistance(nextNode) + label.getTravelDistance();
                if (distance_ > Constants.MAX_DISTANCE / 2.0) {
                    continue;
                }

                // 复制禁忌表并标记当前节点为已访问
                BitSet tabu_ = (BitSet) label.getTabu().clone();
                tabu_.set(nextNode, true);

                // 生成新标签（继承原标签的仓库索引）
                Label newLabel = Label.generate(
                        isForward,
                        nextFence.getIndex(),
                        label,
                        tabu_,
                        newLoad,
                        distance_,
                        newVisitNum,
                        label.getStartDepotIdx() // 关键：新标签继承原标签的仓库索引
                );

                // 调用 dominantAdd 加入标签池和对应仓库的队列（新逻辑兼容）
                this.dominantAdd(newLabel, nextNode);
            }
        }
    }


    private void dominantAdd(Label label, Integer fenceIdx) {
        boolean isForward = label.isForward();
        Integer depotIdx = label.getStartDepotIdx();

        // 1. 原有支配性检查（保持不变，筛选优质标签）
        boolean canAdd = true;
        int li = 0;
        while (li < this.labelPool.get(fenceIdx - 1).size()) {
            Label labelI = this.labelPool.get(fenceIdx - 1).get(li);
            if (this.dominantRule(label, labelI) == 1) {
                this.labelPool.get(fenceIdx - 1).remove(li);
            } else if (this.dominantRule(label, labelI) == -1) {
                canAdd = false;
                break;
            } else {
                li++;
            }
        }

        // 2. 动态分配到对应仓库的队列（适配任意仓库）
        if (canAdd) {
            this.labelPool.get(fenceIdx - 1).add(label);
            // 按仓库索引获取对应队列，添加标签
            if (isForward) {
                Queue<Label> forwardQueue = depotForwardQueues.get(depotIdx);
                if (forwardQueue != null) {
                    forwardQueue.add(label);
                }
            } else {
                Queue<Label> backwardQueue = depotBackwardQueues.get(depotIdx);
                if (backwardQueue != null) {
                    backwardQueue.add(label);
                }
            }
        }
    }

    // 支配规则：较强的禁忌表完全一致才支配
    private Integer dominantRule(Label label1, Label label2) {
        if(!Objects.equals(label1.getStartDepotIdx(), label2.getStartDepotIdx())){
            return 0;
        }
        BitSet tabuDominate = (BitSet) label1.getTabu().clone();
        tabuDominate.xor(label2.getTabu());
        if (tabuDominate.cardinality() == 0) {
            if (label1.getTravelDistance() <= label2.getTravelDistance()) {
                return 1;
            } else {
                return -1;
            }
        } else {
            return 0;
        }
    }

    // 标签连接
    private void labelConnect(Label forwardLabel, Label backwardLabel) {
        // 1. 前后向标签归属仓库必须一致
        Integer forwardBelongDepot = forwardLabel.getStartDepotIdx();
        Integer backwardBelongDepot = backwardLabel.getStartDepotIdx();
        if (!Objects.equals(forwardBelongDepot, backwardBelongDepot)) {
            return;
        }

        // 2. 前向终点+后向起点
        Fence forwardEnd = fences.getFence(forwardLabel.getCurFence());
        Fence backwardStart = fences.getFence(backwardLabel.getCurFence());

        // 检查弧是否存在（前向终点→后向起点）
        if (!forwardEnd.getVaildArcFence().contains(backwardStart.getIndex())) {
            return;
        }

        // 3. 距离检查
        double connectDist = forwardEnd.getDistance(backwardStart.getIndex());
        double totalDist = forwardLabel.getTravelDistance() + connectDist + backwardLabel.getTravelDistance();
        if (totalDist > Constants.MAX_DISTANCE) {
            return;
        }

        // 4. 卸货量和访问次数校验
        double totalLoaded = forwardLabel.getLoadedQuantity() + backwardLabel.getLoadedQuantity();
        if (totalLoaded > Constants.MAX_CAPACITY) {
            return;
        }

        int totalVisitNum = forwardLabel.getVisitNum() + backwardLabel.getVisitNum();
        if (totalVisitNum > Constants.MAX_VISIT_NUM) {
            return;
        }

        // 5. 重复节点校验（仅允许归属仓库重复，卸货点禁止重复）
        BitSet forwardVisited = forwardLabel.getTabu();
        BitSet backwardVisited = backwardLabel.getTabu();
        BitSet intersection = (BitSet) forwardVisited.clone();
        intersection.and(backwardVisited);

        if (!intersection.isEmpty()) {
            return;
        }
        //TODO:利润剪枝

        // 6. 构建闭环路径（起点仓库→卸货点→起点仓库）
        List<Integer> forwardRoute = forwardLabel.getFenceIndexList();
        List<Integer> backwardRoute = backwardLabel.getFenceIndexList();
        reverse(backwardRoute);

        ArrayList<Integer> fenceIndexList = new ArrayList<>();
        fenceIndexList.addAll(forwardRoute);
        fenceIndexList.addAll(backwardRoute);

        // 删除第一个和最后一个元素
        if (fenceIndexList.size() >= 2 && fenceIndexList.getFirst() == 0 && fenceIndexList.getLast() == 0) {// 避免空列表或只有一个元素时索引越界
            fenceIndexList.removeFirst(); // 删除第一个元素
            fenceIndexList.removeLast(); // 删除最后一个元素
        } else if (!fenceIndexList.isEmpty()) { // 只有一个元素时清空列表
            fenceIndexList.clear();
        }

        // 构造完整路径
        Route route = Route.generate(
                fences,
                totalDist,
                totalVisitNum,
                fenceIndexList,
                forwardBelongDepot,
                totalLoaded);

        // 根据路径经过点集筛除（访问围栏相同，但是顺序不同，只保留短的）
        Order sameNodeSetOrder = this.visited2order.getOrDefault(route.getRouteVitedString(), null);
        if (sameNodeSetOrder != null) {
            if (totalDist >= sameNodeSetOrder.getDistance() && Objects.equals(sameNodeSetOrder.getDepot(), forwardBelongDepot)) {
                return;
            }
        }

        // 求解装卸及车型方案
        int startTime = CommonUtils.currentTimeInSecond();
        Order order = this.loading(route);
        this.timeRecord += CommonUtils.currentTimeInSecond() - startTime;

        if (order == null) {
            return;
        }

        if (order.getOriginalPrice() < Constants.OBJ_LB) {
            return;
        }

        order.setReducedCost(PriceCalculator.calculateRC(order, dualsOfRLMP));
        // 连接成功
        if (sameNodeSetOrder != null) {
            this.orderPool.remove(sameNodeSetOrder); // 去除路径被支配的工单
        }
        Integer depotIdx = order.getDepot();
        depotOrderCount.put(depotIdx, depotExpandCount.get(depotIdx) + 1);
        this.visited2order.put(route.getRouteVitedString(), order);
        this.orderPool.add(order);
        this.bestObj = Math.max(this.bestObj, order.getOriginalPrice());
    }

    private Order loading(Route route) {
        Order order = this.loadingAlgorithm.solve(route);

        if (order == null) {
            return null;
        }

        if (!ConstraintsManager.isOrderFeasible(order, this.fences)) {
            return null;
        }
        order.setDualPrice(PriceCalculator.calculateDualObj(order));
        order.setOriginalPrice(PriceCalculator.calculatePrimalObj(order));
        return order;
    }

    private List<Order> generateOutputOrders() {
        List<Order> orders = orderPool.subList(0, min(orderLimit, orderPool.size()));
        orderPool = orderPool.subList(min(orderLimit, orderPool.size()), orderPool.size());
        return orders;
    }
}