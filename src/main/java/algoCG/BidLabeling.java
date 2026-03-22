package algoCG;

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
    private Integer orderLimit; // 单个仓库的订单阈值（原全局阈值改为单仓库阈值）
    private Boolean outputFlag = true;     // 过程信息输出开关
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

    // 核心修改：每个仓库独立的订单池（替代原全局orderPool）
    private Map<Integer, List<Order>> depotOrderPools; // 仓库索引 -> 该仓库的订单池
    // 结果容器与辅助组件
    private final List<Carrier> carrierList;           // 车型列表
    private final LoadingAlgorithm loadingAlgorithm;   // 装卸方案求解器
    private final double dual_multiplier;
    private HashMap<String, Double> dualsOfRLMP; // 当前对偶信息

    // 算法运行状态
    private int startTime;  // 算法开始时间（秒级）

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

        // 3. 动态初始化仓库相关容器
        initDepotQueues();

        // 新增：初始化每个仓库的独立订单池
        initDepotOrderPools();

        // 5. 调用初始化方法
        this.initialize();
    }

    /**
     * 动态初始化仓库队列（适配任意数量仓库）
     */
    private void initDepotQueues() {
        // 从Depots获取所有仓库索引（自动适配1/N个仓库）
        this.allDepotIndexes = new ArrayList<>(depots.getDepotIndexes());

        // 初始化仓库→队列映射（每个仓库分配独立队列）
        this.depotForwardQueues = new HashMap<>(allDepotIndexes.size());
        this.depotBackwardQueues = new HashMap<>(allDepotIndexes.size());
        this.depotExpandCount = new HashMap<>(allDepotIndexes.size());
        this.depotOrderCount = new HashMap<>(allDepotIndexes.size());
        for (Integer depotIdx : allDepotIndexes) {
            depotForwardQueues.put(depotIdx, new LinkedList<>()); // 前向队列
            depotBackwardQueues.put(depotIdx, new LinkedList<>()); // 后向队列
            depotExpandCount.put(depotIdx, 0); // 初始化扩展次数为0
            depotOrderCount.put(depotIdx, 0); // 初始化订单数为0
        }
    }

    /**
     * 新增：初始化每个仓库的独立订单池
     */
    private void initDepotOrderPools() {
        this.depotOrderPools = new HashMap<>(allDepotIndexes.size());
        for (Integer depotIdx : allDepotIndexes) {
            depotOrderPools.put(depotIdx, new ArrayList<>()); // 每个仓库初始化空订单池
        }
    }

    /* 初始化与预处理 */
    private void initialize() {
        for (Integer i : fences.getFenceIndexList()) {
            Fence fenceI = fences.getFence(i);
            for (Integer j : fenceI.getValidArcFence()) {
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

        initializeMultiDepotUnloadingLabels();
    }

    /* 算法主体 */
    public List<Order> solve(HashMap<String, Double> dualsOfRLMP) {
        // 运行初始化
        this.startTime = CommonUtils.currentTimeInSecond();
        this.timeRecord = 0.0;
        this.dualsOfRLMP = dualsOfRLMP;
        // 更新围栏价值
        this.updateFenceValue(dualsOfRLMP);

        // 检查所有仓库的订单池是否均达到阈值
        if (isAllDepotOrderPoolsReachLimit()) {
            return generateOutputOrders();
        }

        // 双向标号搜索（核心修改：并行拓展）
        this.bidirectionalSearch();

        // 对每个仓库的订单池单独排序
        sortDepotOrderPools();

        return generateOutputOrders();
    }

    /**
     * 新增：检查所有仓库的订单池是否都达到设定阈值
     * @return 所有仓库均达标返回true，否则false
     */
    private boolean isAllDepotOrderPoolsReachLimit() {
        if (this.orderLimit == null || this.orderLimit <= 0) {
            return false;
        }
        for (Integer depotIdx : allDepotIndexes) {
            List<Order> depotPool = depotOrderPools.get(depotIdx);
            if (depotPool == null || depotPool.size() < this.orderLimit) {
                return false; // 任意仓库未达标则返回false
            }
        }
        return true;
    }

    /**
     * 新增：对每个仓库的独立订单池单独排序
     */
    private void sortDepotOrderPools() {
        for (Integer depotIdx : allDepotIndexes) {
            List<Order> depotPool = depotOrderPools.get(depotIdx);
            if (depotPool != null && !depotPool.isEmpty()) {
                depotPool.sort(CommonUtils.dualComparator); // 沿用原有排序器
            }
        }
    }

    /**
     * 输出截止当前各仓库的累计拓展次数、生成订单数（实时）
     */
    private void displayDepotStatsRealTime() {
        if (!outputFlag) return;
        System.out.println("\n=== 各仓库拓展/订单统计 ===");
        int totalExpand = 0;
        int totalOrder = 0;
        for (Integer depotIdx : allDepotIndexes) {
            int expandCount = depotExpandCount.getOrDefault(depotIdx, 0);
            // 核心修改：统计各仓库独立订单池的数量
            int orderCount = depotOrderPools.get(depotIdx).size();
            totalExpand += expandCount;
            totalOrder += orderCount;
            System.out.printf("仓库%d：拓展次数=%d，有效订单数=%d%n",
                    depotIdx, expandCount, orderCount);
        }
        System.out.printf("累计：拓展次数=%d，有效订单数=%d%n",
                totalExpand, totalOrder);
        System.out.println("==========================================\n");
    }

    /* 更新目标函数 */
    private void updateFenceValue(HashMap<String, Double> dualsOfRLMP) {
        // update fenceValue
        for (Integer fenceIndex : fences.getFenceIndexList()) {
            Fence fence = fences.getFence(fenceIndex);
            fence.setFenceValue(fence.getOriginalFenceValue() - dualsOfRLMP.get(fence.getConstName()) * dual_multiplier);
        }

        // 核心修改：遍历所有仓库的订单池更新约减成本
        for (Integer depotIdx : allDepotIndexes) {
            List<Order> depotPool = depotOrderPools.get(depotIdx);
            if (depotPool != null) {
                for (Order order : depotPool) {
                    order.setReducedCost(PriceCalculator.calculateRC(order, dualsOfRLMP));
                }
            }
        }

//        fences.SortValidArcFenceByOriginalValue();
//        for(Depot depot : depots.getDepotList()){
//            fences.sortValidArcFenceByOriginalValue(depot);
//        }
    }

    /* 核心修改：并行双向标号搜索（所有仓库同时拓展） */
    private void bidirectionalSearch() {
        int iterationCnt = 0;

        while (true) {
            boolean hasExpanded = false; // 标记本次迭代是否有拓展行为

            // ========== 核心逻辑：遍历所有仓库，各拓展一个标签（并行） ==========
            for (Integer depotIdx : allDepotIndexes) {
                // 核心修改：若当前仓库订单池已达阈值，跳过拓展
                List<Order> depotPool = depotOrderPools.get(depotIdx);
                if (depotPool != null && depotPool.size() >= this.orderLimit) {
                    continue;
                }

                // 拓展当前仓库的前向队列（最多1个标签）
                Queue<Label> forwardQueue = depotForwardQueues.get(depotIdx);
                if (forwardQueue != null && !forwardQueue.isEmpty()) {
                    Label label = forwardQueue.poll();
                    this.labelExpand(label);
                    logExpand(label);
                    hasExpanded = true;
                }

                // 拓展当前仓库的后向队列（最多1个标签）
                Queue<Label> backwardQueue = depotBackwardQueues.get(depotIdx);
                if (backwardQueue != null && !backwardQueue.isEmpty()) {
                    Label label = backwardQueue.poll();
                    this.labelExpand(label);
                    logExpand(label);
                    hasExpanded = true;
                }
            }

            // 结束条件：无拓展行为 或 超时 或 所有仓库订单池均达标
            if (!hasExpanded
                    || CommonUtils.currentTimeInSecond() - this.startTime > this.timeLimit
                    || isAllDepotOrderPoolsReachLimit()) {
                break;
            }

            iterationCnt++;
        }
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
        // 遍历所有仓库，为每个仓库创建初始标签
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

    // 标签扩展（逻辑不变，仅适配并行拓展）
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
        for (Integer nextNode : currentFence.getValidArcFence()) {
            // 跳过禁忌节点（自身或已访问节点）
            if (label.getTabu().get(nextNode)) {
                continue;
            }

            // 处理999虚拟节点（截断搜索，尝试连接前后向标签）
            if (nextNode == 999 && label.getLoadedQuantity() >= Constants.MIN_CARRIER_LOAD && label.getVisitNum() >= Constants.MIN_VISIT_NUM) {
                // 连接逻辑：仅连接同仓库的前后向标签
                Integer depotIdx = label.getStartDepotIdx();
                if (isForward) {
                    // 前向标签：遍历当前仓库的后向标签
                    for (List<Label> nodeLabels : this.labelPool){
                        for (Label backwardLabel : nodeLabels) {
                            if (!backwardLabel.isForward() && Objects.equals(backwardLabel.getStartDepotIdx(), depotIdx) && backwardLabel.getVisitNum() >= Constants.MIN_VISIT_NUM) {
                                this.labelConnect(label, backwardLabel);
                            }
                        }
                    }
                } else {
                    // 后向标签：遍历当前仓库的前向标签
                    for (List<Label> nodeLabels : this.labelPool){
                        for (Label forwardLabel : nodeLabels) {
                            if (forwardLabel.isForward() && Objects.equals(forwardLabel.getStartDepotIdx(), depotIdx) && forwardLabel.getVisitNum() >= Constants.MIN_VISIT_NUM) {
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
                if (distance_ > Constants.TRUCK_MAX_DISTANCE / 2.0) {
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

                // 加入标签池和对应仓库的队列
                this.dominantAdd(newLabel, nextNode);
            }
        }
    }

    private void dominantAdd(Label label, Integer fenceIdx) {
        boolean isForward = label.isForward();
        Integer depotIdx = label.getStartDepotIdx();

        // 1. 支配性检查（保持不变，筛选优质标签）
        boolean canAdd = true;
        int li = 0;
        while (li < this.labelPool.get(fenceIdx - 1).size()) {
            Label labelI = this.labelPool.get(fenceIdx - 1).get(li);
            if (!Objects.equals(labelI.getStartDepotIdx(), depotIdx)) {
                li++;
                continue; // 仅对比同仓库的标签
            }
            if (this.dominantRule(label, labelI) == 1) {
                this.labelPool.get(fenceIdx - 1).remove(li);
            } else if (this.dominantRule(label, labelI) == -1) {
                canAdd = false;
                break;
            } else {
                li++;
            }
        }

        // 2. 动态分配到对应仓库的队列
        if (canAdd) {
            this.labelPool.get(fenceIdx - 1).add(label);
            // 按仓库索引获取对应队列，添加标签
            if (isForward) {
                Queue<Label> forwardQueue = depotForwardQueues.get(depotIdx);
                forwardQueue.add(label);
            } else {
                Queue<Label> backwardQueue = depotBackwardQueues.get(depotIdx);
                backwardQueue.add(label);
            }
        }
    }

    // 支配规则：仅同仓库的标签才进行支配性对比
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

    // 标签连接（仅连接同仓库标签，统计有效订单）
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
        if (!forwardEnd.getValidArcFence().contains(backwardStart.getIndex())) {
            return;
        }

        // 3. 距离检查
        double connectDist = forwardEnd.getDistance(backwardStart.getIndex());
        double totalDist = forwardLabel.getTravelDistance() + connectDist + backwardLabel.getTravelDistance();
        if (totalDist > Constants.TRUCK_MAX_DISTANCE) {
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

        // 6. 构建闭环路径（起点仓库→卸货点→起点仓库）
        List<Integer> forwardRoute = forwardLabel.getFenceIndexList();
        List<Integer> backwardRoute = backwardLabel.getFenceIndexList();
        reverse(backwardRoute);

        ArrayList<Integer> fenceIndexList = new ArrayList<>();
        fenceIndexList.addAll(forwardRoute);
        fenceIndexList.addAll(backwardRoute);

        // 清理无效节点
        if (fenceIndexList.size() >= 2 && fenceIndexList.getFirst() == 0 && fenceIndexList.getLast() == 0) {
            fenceIndexList.removeFirst();
            fenceIndexList.removeLast();
        } else if (!fenceIndexList.isEmpty()) {
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

        // 根据路径经过点集筛除重复订单
        String routeKey = route.getRouteVitedString();
        Order sameNodeSetOrder = this.visited2order.getOrDefault(routeKey, null);
        if (sameNodeSetOrder != null) {
            if (totalDist >= sameNodeSetOrder.getDistance() && Objects.equals(sameNodeSetOrder.getDepot(), forwardBelongDepot)) {
                return;
            }
        }

        // 求解装卸及车型方案
        int startTime = CommonUtils.currentTimeInSecond();
        Order order = this.loading(route);
        this.timeRecord += CommonUtils.currentTimeInSecond() - startTime;

        if (order == null || order.getOriginalPrice() < Constants.OBJ_LB) {
            return;
        }

        order.setReducedCost(PriceCalculator.calculateRC(order, dualsOfRLMP));
        // 核心修改：将订单存入对应仓库的独立订单池
        Integer depotIdx = order.getDepot();
        List<Order> depotPool = depotOrderPools.get(depotIdx);

        // 移除重复订单（原逻辑适配）
        if (sameNodeSetOrder != null) {
            depotPool.remove(sameNodeSetOrder);
        }
        // 新增有效订单到对应仓库池
        depotPool.add(order);
        this.visited2order.put(routeKey, order);
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

    /**
     * 核心修改：合并所有仓库的订单池并输出
     * @return 合并后的全局订单列表
     */
    private List<Order> generateOutputOrders() {
        List<Order> outputOrders = new ArrayList<>();

        // 遍历所有仓库，合并其订单池
        for (Integer depotIdx : allDepotIndexes) {
            List<Order> depotPool = depotOrderPools.get(depotIdx);
            if (depotPool != null && !depotPool.isEmpty()) {
                // 按阈值截取每个仓库的订单（若需要）
                int takeNum = min(orderLimit, depotPool.size());
                outputOrders.addAll(depotPool.subList(0, takeNum));

                // 保留剩余订单（供下次迭代）
                depotOrderPools.put(depotIdx, new ArrayList<>(depotPool.subList(takeNum, depotPool.size())));
            }
        }

        // 清空全局去重映射（可选，根据业务需求）
        this.visited2order.clear();

        return outputOrders;
    }
}