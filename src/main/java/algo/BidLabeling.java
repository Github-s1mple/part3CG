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
    final Depots depots; // 多仓库管理类（原代码已引用，确保字段存在）

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
    final PriorityQueue<Label> forwardLabelQueue;  // 前向标签优先级队列（多起点统一调度）
    final PriorityQueue<Label> backwardLabelQueue; // 后向标签优先级队列（多起点统一调度）
    final List<Label> forwardLabelPool;  // 前向标签待拼接池（虚拟节点触发后存入）
    final List<Label> backwardLabelPool; // 后向标签待拼接池（虚拟节点触发后存入）

    // 结果容器与辅助组件
    private List<Order> orderPool = new ArrayList<>(); // 最终订单池
    private final List<Carrier> carrierList;           // 车型列表（原代码已引用）
    private final LoadingAlgorithm loadingAlgorithm;   // 装卸方案求解器（原代码已引用）
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

        // 3. 初始化标签队列（优先级队列：按自定义规则排序，优先扩展优质标签）
        this.forwardLabelQueue = new PriorityQueue<>(new LabelComparator());
        this.backwardLabelQueue = new PriorityQueue<>(new LabelComparator());

        // 4. 初始化标签待拼接池（虚拟节点触发后存储待拼接标签）
        this.forwardLabelPool = new ArrayList<>();
        this.backwardLabelPool = new ArrayList<>();

        // 5. 调用初始化方法（移除原单仓库逻辑，后续由initializeMultiDepotUnloadingLabels接管多起点）
        this.initialize();
        System.out.println("算法初始化完成！");
    }

    /* 初始化与预处理：仅保留通用逻辑（虚拟节点、异构距离计算），多起点标签初始化移至专用方法 */
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
            for (Integer j : depot.getVaildArcFence()) {
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
        // 初始化：通过Depots类获取所有仓库，创建前向/后向标签
        initializeMultiDepotUnloadingLabels();

        while (true) {
            // 前向标号搜索（统一队列，按潜力调度）
            if (!forwardLabelQueue.isEmpty()) {
                this.labelExpand(forwardLabelQueue.poll());
            }
            // 后向标号搜索（统一队列）
            if (!backwardLabelQueue.isEmpty()) {
                this.labelExpand(backwardLabelQueue.poll());
            }

            // 完整结束条件: 前后向队列均为空
            if (forwardLabelQueue.isEmpty() && backwardLabelQueue.isEmpty()) {
                displayIterationInformationIfNecessary(iterationCnt);
                break;
            }
            // 提前结束条件：超时或达到订单数量上限
            if (CommonUtils.currentTimeInSecond() - this.startTime > this.timeLimit
                    || this.orderPool.size() >= this.orderLimit) {
                displayIterationInformationIfNecessary(iterationCnt);
                break;
            }
            // 定期输出迭代信息
            iterationCnt += 1;
            if (iterationCnt % Constants.OUTPUT_INTERVAL == 0) {
                displayIterationInformationIfNecessary(iterationCnt);
            }
        }
    }

    // 核心修改1：基于Depots类初始化多仓库标签（强制起点=终点）
    private void initializeMultiDepotUnloadingLabels() {
        // 1. 通过Depots类获取所有真实仓库索引（不再依赖fences，统一仓库管理）
        List<Integer> allDepotIndexes = depots.getDepotIndexes();
        if (allDepotIndexes.isEmpty()) {
            throw new IllegalArgumentException("Depots类中未配置任何起点仓库，无法初始化标签");
        }

        // 2. 为每个仓库创建前向初始标签（起点=当前仓库，后续必须返回此仓库）
        // 前向标签初始化：添加当前仓库到禁忌表
        for (Integer depotIdx : allDepotIndexes) {
            BitSet forwardTabu = new BitSet(fences.size());
            forwardTabu.set(depotIdx);
            Label forwardInit = Label.generate(
                    true,
                    depotIdx,
                    null,
                    forwardTabu,
                    0.0,
                    0.0,
                    0,
                    depotIdx
            );
            forwardLabelQueue.add(forwardInit);
        }

        // 后向标签初始化：添加当前仓库到禁忌表
        for (Integer depotIdx : allDepotIndexes) {
            BitSet backwardTabu = new BitSet(fences.size());
            backwardTabu.set(depotIdx);
            Label backwardInit = Label.generate(
                    false,
                    depotIdx,
                    null,
                    backwardTabu,
                    0.0,
                    0.0,
                    0,
                    depotIdx
            );
            backwardLabelQueue.add(backwardInit);
        }
    }

    // 核心修改2：扩展逻辑强化（禁止扩展到其他仓库，仅允许返回归属仓库）
    private void labelExpand(Label label) {
        Fence currentFence;
        if (label.getParent() == null){
            Depot depot = depots.getDepot(label.getStartDepotIdx());
            currentFence = depot.depot2Fence(999);
        }else{
            currentFence = fences.getFence(label.getCurFence());
        }
        boolean isForward = label.isForward();

        for (Integer nextNode : currentFence.getVaildArcFence()) {
            // 如果是自己或者是禁止搜索的则跳过
            if (label.getTabu().get(nextNode)) {
                continue;
            }
            // 如果是虚拟节点（目的是截断搜索），则判断是否能成单，并压入待匹配池
            if (nextNode == 999 && label.getLoadedQuantity() >= Constants.MIN_CARRIER_LOAD) {
                if (isForward) {
                    for (Label labelI : this.backwardLabelPool) {
                        this.labelConnect(label, labelI);
                    }
                    this.forwardLabelPool.add(label);
                } else {
                    for (Label labelI : this.forwardLabelPool) {
                        this.labelConnect(labelI, label);
                    }
                    this.backwardLabelPool.add(label);
                }
            } else if (nextNode != 999) {
                Fence nextFence = fences.getFence(nextNode);

                // 访问次数约束（仅卸货点计数，归属仓库不计入）
                int newVisitNum = label.getVisitNum() + 1;
                if (newVisitNum > Constants.MAX_VISIT_NUM) {
                    continue;
                }

                // 卸货量约束（仅卸货点累加，归属仓库不计入）
                double newLoad = label.getLoadedQuantity() + nextFence.getDeliverDemand();
                if (newLoad > Constants.MAX_CAPACITY) {
                    continue;
                }

                // 距离约束（含归属仓库的距离计算）
                double distance_ = currentFence.getDistance(nextNode) + label.getTravelDistance();
                if (distance_ > Constants.MAX_DISTANCE) {
                    continue;
                }

                // 创建新标签（更新禁忌表）
                BitSet tabu_ = (BitSet) label.getTabu().clone();
                tabu_.set(nextNode, true);

                Label newLabel = Label.generate(
                        isForward,
                        nextFence.getIndex(),
                        label,
                        tabu_,
                        newLoad,
                        distance_,
                        newVisitNum,
                        label.getStartDepotIdx()
                );
                this.dominantAdd(newLabel, nextNode);
            }
        }
    }

    private void dominantAdd(Label label, int fenceIdx) {
        boolean isForward = label.isForward();
        int li = 0;
        while (li < this.labelPool.get(fenceIdx).size()) {
            Label labelI = this.labelPool.get(fenceIdx).get(li);
            // 是否存在围栏相同，但是路径更短的，如果存在就替换并删除搜索队列中的标号，如果更差则放弃
            if (this.dominantRule(label, labelI) == 1) {
                this.labelPool.get(fenceIdx).remove(li);
                if (isForward) {
                    this.forwardLabelQueue.remove(labelI);
                } else {
                    this.backwardLabelQueue.remove(labelI);
                }
            } else if (this.dominantRule(label, labelI) == -1) {
                return;
            } else {
                li += 1;
            }
        }
        this.labelPool.get(fenceIdx).add(label);
        if (isForward) {
            this.forwardLabelQueue.add(label);
        } else {
            this.backwardLabelQueue.add(label);
        }
    }

    // 支配规则
    private Integer dominantRule(Label label1, Label label2) {
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

    // 核心修改4：标号连接（强制归属仓库一致，路径闭环=起点→卸货点→起点）
    private void labelConnect(Label forwardLabel, Label backwardLabel) {
        // 1. 强制校验：前后向标签归属仓库必须一致（禁止跨仓库）
        Integer forwardBelongDepot = forwardLabel.getStartDepotIdx();
        Integer backwardBelongDepot = backwardLabel.getStartDepotIdx();
        if (!Objects.equals(forwardBelongDepot, backwardBelongDepot)) {
            return;
        }

        // 2. 节点连接校验：前向终点+后向起点必须满足"卸货点→卸货点"或"卸货点→归属仓库"
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
        boolean hasDuplicateUnloading = false;
        for (int i = intersection.nextSetBit(0); i >= 0; i = intersection.nextSetBit(i + 1)) {
            // 排除归属仓库，其他重复节点均为无效
            if (i != forwardBelongDepot) {
                hasDuplicateUnloading = true;
                break;
            }
        }
        if (hasDuplicateUnloading) {
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
            if (totalDist >= sameNodeSetOrder.getDistance()) {
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

        if (order.getPrice() < Constants.OBJ_LB) {
            return;
        }

        order.setReducedCost(PriceCalculator.calculateRC(order, dualsOfRLMP));
        // 连接成功
        if (sameNodeSetOrder != null) {
            this.orderPool.remove(sameNodeSetOrder); // 去除路径被支配的工单
        }
        this.visited2order.put(route.getRouteVitedString(), order);
        this.orderPool.add(order);
        this.bestObj = Math.max(this.bestObj, order.getPrice());
    }

    private Order loading(Route route) {
        Order order = this.loadingAlgorithm.solve(route);

        if (order == null) {
            return null;
        }

        if (!ConstraintsManager.isOrderFeasible(order, this.fences)) {
            return null;
        }

        order.setPrice(PriceCalculator.calculatePrimalObj(order));
        return order;
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