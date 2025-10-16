package algo;

import Utils.CommonUtils;
import impl.*;
import baseinfo.Constants;
import lombok.Getter;
import lombok.Setter;

import java.util.*;

import static java.lang.Math.min;
@Setter
@Getter
public class BidLabeling {
    // 算例数据
    final Fences fences;
    // 时间限制参数
    private Integer timeLimit;
    private Integer orderLimit;
    // 算法参数
    private final Boolean checkFlag = true; // 过程中是否检查约束
    // 输出参数
    private Boolean outputFlag = false; // 是否输出过程信息
    private Double timeRecord = 0.0; // 时间记录
    final HashMap<String, Integer> recordDict = new HashMap<>(); // 记录字典
    // 算法容器
    final List<List<Label>> labelPool = new ArrayList<>(); // 各围栏的标号池
    final PriorityQueue<Label> forwardLabelQueue = new PriorityQueue<>(new LabelComparator()); // 前向标号队列
    final PriorityQueue<Label> backwardLabelQueue = new PriorityQueue<>(new LabelComparator()); // 后向标号队列
    final List<Label> forwardLabelPool = new ArrayList<>(); // 前向标号池
    final List<Label> backwardLabelPool = new ArrayList<>(); // 后向标号池
    private List<Order> orderPool = new ArrayList<>(); // 工单池
    final HashMap<String, Order> visited2order = new HashMap<>(); // 已访问点对应工单
    // 预处理信息
    private final double minUnloadDistance;
    private final List<Carrier> carrierList;

    private final algo.LoadingAlgorithm loadingAlgorithm;

    private final double dual_multiplier;
    private final double streetSweepDistance;
    private final HashMap<String, Integer> smallFenceThreshold;
    private final HashMap<String, Integer> smallFenceMaxNum;

    // 构造方法
    public BidLabeling(Instance instance) {
        this.fences = instance.getFences();
        this.carrierList = instance.getCarrierList();
        this.dual_multiplier = instance.getAlgoParam().getDualMultiplier();
        this.loadingAlgorithm = new LoadingAlgorithm(this);
        this.initialize(); // 初始化
    }

    /* 初始化与预处理 */
    private void initialize() {
        // 初始化标号容器 存储围栏标号
        for (int i = 0; i < fences.getFenceNum(); i++) {
            labelPool.add(new ArrayList<>());
        }
        // 初始化禁忌搜索
        BitSet forwardTabu = new BitSet(fences.getFenceNum());
        BitSet backwardTabu = new BitSet(fences.getFenceNum());
        for (int i = 0; i < fences.getFenceList().size(); i++) {
            backwardTabu.set(i, fences.isFenceType(i, NameConstants.LOAD));
            forwardTabu.set(i, fences.isFenceType(i, NameConstants.UN_LOAD));
        }
        //初始化搜索队列
        forwardLabelQueue.add(Label.generate(true, fences.getDepot(), forwardTabu));
        backwardLabelQueue.add(Label.generate(false, fences.getDepot(), backwardTabu));

        // 初始化每个围栏最近异构围栏距离
        for (int i = 0; i < fences.getFenceNum(); i++) {
            Fence fenceI = fences.getFence(i);
            if (i != fences.getDepot()) {
                for (int j : fenceI.getDistances().keySet()) {
                    Fence fenceJ = fences.getFence(j);
                    if (j != fences.getDepot() && !fenceI.getFenceType().equals(fenceJ.getFenceType())) {
                        fenceI.setNearestDiffLabelDist(min(fenceI.getNearestDiffLabelDist(), fenceI.getDistance(fenceJ)));
                    }
                }
            }
        }
    }

    /* 算法主体 */
    private int startTime; // 开始时间
    private Double bestObj; // 最优目标函数值

    public List<Order> solve(HashMap<String, Double> dualsOfRLMP, boolean isLongDistance) {
        // 运行初始化
        this.startTime = CommonUtils.currentTimeInSecond();
        this.bestObj = 0.0;
        this.timeRecord = 0.0;
        // 更新围栏价值
        this.updateFenceValue(dualsOfRLMP);
        // 若初始orderPool超出orderLimit直接输出
        if (this.orderPool.size() >= this.orderLimit) {
            return generateOutputOrders();
        }
        // 双向标号搜索
        this.bidirectionalSearch(isLongDistance);
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

    /* 输出信息函数 */
    private void displayIterationInformation(Integer iterationCnt) {
        double obj = (double) Math.round(this.bestObj * 10) / 10;
        double timeRecord = (double) Math.round(this.timeRecord * 10) / 10;
        double timeCost = (double) Math.round((double) (CommonUtils.currentTimeInSecond() - this.startTime) * 10) / 10;
        System.out.println("iterationCnt: " + iterationCnt + ", forwardPoolSize: " + this.forwardLabelPool.size()
                + ", backwardPoolSize: " + this.backwardLabelPool.size()
                + ", forwardQueueSize: " + this.forwardLabelQueue.size()
                + ", backwardQueueSize: " + this.backwardLabelQueue.size()
                + ", orderPoolSize: " + this.orderPool.size()
                + ", obj: " + obj + ", timeRecord: " + timeRecord + "(" + timeCost + ")");
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
        for (int fenceIndex : fences.getInFenceList()) {
            Fence fence = fences.getFence(fenceIndex);
            fence.setFenceValue(fence.getOriginalFenceValue() - dualsOfRLMP.get(fence.getConstName()) * dual_multiplier);
        }
        for (int fenceIndex : fences.getOutFenceList()) {
            Fence fence = fences.getFence(fenceIndex);
            fence.setFenceValue(fence.getOriginalFenceValue() + dualsOfRLMP.get(fence.getConstName()) * dual_multiplier);
        }
        // update carrierValue
        for (Carrier carrier : carrierList) {
            carrier.setCarrierValue(dualsOfRLMP.get(carrier.getConstName()));
        }

        for (Order order : this.orderPool) {
            order.setDualObj(objCal.calculateDualObj(order));
        }
        // filter orders according to new obj
        orderPool.sort(CommonUtils.dualComparator);
        // 放弃价值过低的工单
        for (int i = 0; i < this.orderPool.size(); i++) {
            if (this.orderPool.get(i).getDualObj() < Constants.EPS) {
                this.orderPool = this.orderPool.subList(0, i);
                return;
            }
        }
    }

    /* 双向标号搜索 - 适配只卸问题 */
    private void bidirectionalSearch(boolean isLongDistance) {
        int iterationCnt = 0;
        // 初始化：前向从起点出发，后向从终点(仓库)出发
        initializeUnloadingLabels();

        while (true) {
            // 前向标号搜索（从起点向卸货点扩展）
            if (!forwardLabelQueue.isEmpty()) {
                this.labelExpand(forwardLabelQueue.poll(), isLongDistance);
            }
            // 后向标号搜索（从终点向卸货点反向扩展）
            if (!backwardLabelQueue.isEmpty()) {
                this.labelExpand(backwardLabelQueue.poll(), isLongDistance);
            }

            // 完整结束条件: 前后向都探索完毕
            if (forwardLabelQueue.isEmpty() && backwardLabelQueue.isEmpty()) {
                displayIterationInformationIfNecessary(iterationCnt);
                break;
            }
            // 提前结束条件：达到timeLimit或达到orderLimit
            if (CommonUtils.currentTimeInSecond() - this.startTime > this.timeLimit
                    || this.orderPool.size() >= this.orderLimit) {
                displayIterationInformationIfNecessary(iterationCnt);
                break;
            }
            // 输出信息
            iterationCnt += 1;
            if (iterationCnt % Constants.outputInterval == 0) {
                displayIterationInformationIfNecessary(iterationCnt);
            }
        }
    }

    // 初始化只卸问题的标签
    private void initializeUnloadingLabels() {
        // 前向标签：从起点出发，无装载量，初始卸货量为0
        Label forwardInit = Label.generate(
                true,  // 前向标签
                fences.getDepot(),  // 起点
                null,  // 父标签
                new BitSet(fences.size()),  // 禁忌表
                0.0,  // 初始卸货量（只卸问题中记录总卸货量）
                0.0,  // 初始距离
                0,  // 访问次数
                null,  // 区域ID
                0,  // 最小卸货数
                0   // 小型站点数量
        );
        forwardInit.getTabu().set(fences.getDepot(), true);  // 标记起点为已访问
        forwardLabelQueue.add(forwardInit);

        // 后向标签：从终点(仓库)出发，反向扩展
        Label backwardInit = Label.generate(
                false,  // 后向标签
                fences.getDepot(),  // 终点
                null,  // 父标签
                new BitSet(fences.size()),  // 禁忌表
                0.0,  // 初始卸货量
                0.0,  // 初始距离
                0,  // 访问次数
                null,  // 区域ID
                0,  // 最小卸货数
                0   // 小型站点数量
        );
        backwardInit.getTabu().set(fences.getDepot(), true);  // 标记终点为已访问
        backwardLabelQueue.add(backwardInit);
    }

    private void labelExpand(Label label, boolean isLongDistance) {
        Fence currentFence = fences.getFence(label.getCurFence());
        boolean isForward = label.isForward();

        // 只卸问题：扩展时只考虑卸货点
        for (int nextNode : getValidUnloadingNodes(currentFence, label, isForward)) {
            // 跳过禁忌节点
            if (label.getTabu().get(nextNode)) {
                add_record(NameConstants.LABEL_EXPAND_TABU);
                continue;
            }

            Fence nextFence = fences.getFence(nextNode);
            // 只卸问题：检查是否为卸货点
            if (!nextFence.isUnloadingPoint()) {
                add_record(NameConstants.NOT_UNLOADING_POINT);
                continue;
            }

            // 区域和约束参数（只卸问题调整）
            String rfId_ = label.getRfId() == null ? nextFence.getRfId() : label.getRfId();
            Region region_ = regions.get(rfId_);
            double maxDistance = getMaxDistance(isLongDistance, isForward, region_);
            int maxVisitNum = isForward ? region_.getMaxVisitNum() : regions.getMaxVisitNum();

            // 访问次数约束
            if (label.getVisitNum() + 1 > maxVisitNum) {
                add_record("labelExpand: visit num filter");
                continue;
            }

            // 卸货量约束（只卸问题核心约束）
            double newUnloaded = label.getLoadedQuantity() + nextFence.getDemand();  // 原loadedQuantity改为记录卸货量
            if (newUnloaded > region_.getMaxCapacity()) {  // 最大卸货容量约束
                add_record("labelExpand: unload capacity filter");
                continue;
            }

            // 距离约束
            double distance_ = calculateDistance(label, currentFence, nextFence, isForward) + label.getTravelDistance();
            if (distance_ > maxDistance - nextFence.getNearestDiffLabelDist()) {
                add_record("labelExpand: distance filter");
                continue;
            }

            // 小型站点数量控制
            int smallLoadedN = label.getSmallLoadedN();
            if (isSmallUnloadingPoint(nextFence)) {
                smallLoadedN += 1;
                if (smallLoadedN > getSmallFenceMaxNum(nextFence.getFenceType())) {
                    continue;
                }
            }

            // 创建新标签（更新禁忌表）
            BitSet tabu_ = (BitSet) label.getTabu().clone();
            tabu_.set(nextNode, true);

            Label newLabel = Label.generate(
                    isForward,
                    nextFence.getIndexInInstance(),
                    label,
                    tabu_,
                    newUnloaded,  // 记录卸货量
                    distance_,
                    label.getVisitNum() + 1,
                    rfId_,
                    label.getMinNumber() + nextFence.getMinUnloadNum(),  // 最小卸货数
                    smallLoadedN
            );

            this.dominantAdd(newLabel, nextNode);
        }
    }

    // 计算扩展距离（前向/后向不同逻辑）
    private double calculateDistance(Label label, Fence current, Fence next, boolean isForward) {
        return isForward ? current.getDistance(next) : next.getDistance(current);
    }

    // 获取只卸问题的有效扩展节点
    private List<Integer> getValidUnloadingNodes(Fence current, Label label, boolean isForward) {
        List<Integer> nodes = new ArrayList<>();
        for (int node : current.getDistances().keySet()) {
            // 排除仓库节点（除了后向标签返回仓库的情况）
            if (node == fences.getDepot() && !(label.getVisitNum() > 0 && !isForward)) {
                continue;
            }
            nodes.add(node);
        }
        return nodes;
    }

    // 支配规则修改（只卸问题适配）
    private Integer dominantRule(Label label1, Label label2) {
        // 1: label1支配label2; -1: label2支配label1; 0: 无支配关系
        // 1. 检查禁忌表（已访问节点）
        BitSet tabu1 = label1.getTabu();
        BitSet tabu2 = label2.getTabu();

        // 2. 只卸问题支配条件：访问节点相同情况下，卸货量更大且距离更短的标签更优
        if (tabu1.equals(tabu2)) {
            if (label1.getLoadedQuantity() >= label2.getLoadedQuantity() &&  // 卸货量更大
                    label1.getTravelDistance() <= label2.getTravelDistance()) {  // 距离更短
                return 1;
            } else if (label2.getLoadedQuantity() >= label1.getLoadedQuantity() &&
                    label2.getTravelDistance() <= label1.getTravelDistance()) {
                return -1;
            }
        }

        // 3. 检查是否为子集关系（A包含B的所有节点，且更优）
        BitSet subsetCheck = (BitSet) tabu1.clone();
        subsetCheck.and(tabu2);
        if (subsetCheck.equals(tabu2)) {  // label1包含label2的所有节点
            if (label1.getTravelDistance() <= label2.getTravelDistance() &&
                    label1.getLoadedQuantity() >= label2.getLoadedQuantity()) {
                return 1;
            }
        }

        subsetCheck = (BitSet) tabu2.clone();
        subsetCheck.and(tabu1);
        if (subsetCheck.equals(tabu1)) {  // label2包含label1的所有节点
            if (label2.getTravelDistance() <= label1.getTravelDistance() &&
                    label2.getLoadedQuantity() >= label1.getLoadedQuantity()) {
                return -1;
            }
        }

        return 0;  // 无支配关系
    }

    /* 标号连接 - 只卸问题简化版 */
    private void labelConnect(Label forwardLabel, Label backwardLabel, boolean isLongDistance) {
        // 1. 检查前向终点和后向起点是否可连接
        Fence forwardEnd = fences.getFence(forwardLabel.getCurFence());
        Fence backwardStart = fences.getFence(backwardLabel.getCurFence());

        if (!forwardEnd.getArcs().contains(backwardStart.getIndexInInstance())) {
            add_record(NameConstants.ARC_FILTER);
            return;
        }

        // 2. 距离约束检查
        double connectDist = forwardEnd.getDistance(backwardStart);
        double totalDist = forwardLabel.getTravelDistance() + connectDist + backwardLabel.getTravelDistance();
        Region region = regions.get(forwardLabel.getRfId());

        if (!checkDistanceConstraint(totalDist, isLongDistance, region)) {
            add_record(NameConstants.DISTANCE_FILTER);
            return;
        }

        // 3. 卸货量和访问次数检查
        double totalUnloaded = forwardLabel.getLoadedQuantity() + backwardLabel.getLoadedQuantity();
        if (totalUnloaded > region.getMaxCapacity()) {
            add_record(NameConstants.TOTAL_UNLOAD_FILTER);
            return;
        }

        int totalVisitNum = forwardLabel.getVisitNum() + backwardLabel.getVisitNum();
        if (totalVisitNum > region.getMaxVisitNum()) {
            add_record(NameConstants.MAX_VISIT_FILTER);
            return;
        }

        // 4. 检查节点是否重复访问（前后向标签访问节点必须无交集）
        BitSet forwardVisited = forwardLabel.getTabu();
        BitSet backwardVisited = backwardLabel.getTabu();
        BitSet intersection = (BitSet) forwardVisited.clone();
        intersection.and(backwardVisited);
        if (!intersection.isEmpty() && !isDepotOnly(intersection)) {  // 仅允许仓库节点重复
            add_record(NameConstants.DUPLICATE_NODE_FILTER);
            return;
        }

        // 5. 构建完整路径
        List<Integer> forwardRoute = forwardLabel.getFenceIndexList();
        List<Integer> backwardRoute = reverse(backwardLabel.getFenceIndexList());  // 后向路径反转

        List<Integer> fullRoute = new ArrayList<>(forwardRoute);
        fullRoute.remove(fullRoute.size() - 1);  // 移除重复的仓库节点
        fullRoute.addAll(backwardRoute);

        // 6. 创建路径并生成订单
        Route route = Route.builder()
                .rfId(region.getRegionId())
                .totalDistance(totalDist)
                .totalVisitNum(totalVisitNum)
                .totalUnloaded(totalUnloaded)  // 只卸问题：记录总卸货量
                .fenceIndexList(fullRoute)
                .isLongDistance(isLongDistance)
                .build();

        // 7. 生成订单（简化装载逻辑，只处理卸货）
        Order order = this.unloadingOnlyLoading(route);
        if (order == null) return;

        // 8. 加入订单池
        addOrderToPool(order, route);
    }

    // 只卸问题的订单生成（取消装载逻辑）
    private Order unloadingOnlyLoading(Route route) {
        Order order = new Order();
        order.setRoute(route);
        order.setTotalUnloaded(route.getTotalUnloaded());
        order.setTotalDistance(route.getTotalDistance());

        // 计算目标函数（只考虑卸货收益和成本）
        double profit = calculateUnloadingProfit(route);
        order.setObj(profit);

        // 可行性检查（只卸相关约束）
        if (!ConstraintsManager.isUnloadingOrderFeasible(order, this.minUnloadDistance, fences)) {
            return null;
        }

        add_record(NameConstants.CONNECT_SUCCESS);
        return order;
    }

    // 辅助方法：检查是否仅包含仓库节点
    private boolean isDepotOnly(BitSet bitSet) {
        int depot = fences.getDepot();
        return bitSet.cardinality() == 1 && bitSet.get(depot);
    }

    // 辅助方法：检查距离约束
    private boolean checkDistanceConstraint(double totalDist, boolean isLongDistance, Region region) {
        if (isLongDistance) {
            return totalDist >= Constants.longDistanceMin && totalDist <= Constants.longDistanceMax;
        } else {
            return totalDist <= region.getMaxDistance();
        }
    }


    private List<Order> generateOutputOrders() {
        List<Order> orders = orderPool.subList(0, min(orderLimit, orderPool.size()));
        orderPool = orderPool.subList(min(orderLimit, orderPool.size()), orderPool.size());
        return orders;
    }

    private void add_record(String filter_name) {
        if (this.outputFlag) {
            this.recordDict.put(filter_name, this.recordDict.getOrDefault(filter_name, 0) + 1);
        }
    }

    public void displayIterationInformationIfNecessary(int iterationCnt) {
        if (this.outputFlag) {
            this.displayIterationInformation(iterationCnt);
        }
    }

    public void setOutputFlag(Boolean outputFlag) {
        this.outputFlag = outputFlag;
    }

    public void setTimeLimit(Integer timeLimit) {
        this.timeLimit = timeLimit;
    }

    public void setOrderLimit(Integer orderLimit) {
        this.orderLimit = orderLimit;
    }
}