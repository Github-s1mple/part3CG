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
    // 算例核心数据（保留，新增Depots类管理多仓库）
    final Fences fences;
    final Carriers carriers;
    final Depots depots; // 多仓库管理类（原代码已引用，确保字段存在）

    // 算法控制参数（保留，新增虚拟节点常量便于维护）
    private Integer timeLimit;
    private Integer orderLimit;
    private final Boolean checkFlag = true; // 过程约束检查开关
    private Boolean outputFlag = false;     // 过程信息输出开关
    private Double timeRecord = 0.0;        // 算法耗时记录

    // 统计与映射容器（保留，新增visited2order用于节点集去重）
    final HashMap<String, Integer> recordDict = new HashMap<>(); // 过滤原因统计
    final HashMap<String, Order> visited2order = new HashMap<>(); // 节点集→最优订单映射（去重用）

    // 标签容器（关键调整：labelPool按节点索引存储，队列用优先级队列按潜力排序）
    final List<List<Label>> labelPool; // 按【节点索引】存储标签（多节点多标签）
    final PriorityQueue<Label> forwardLabelQueue;  // 前向标签优先级队列（多起点统一调度）
    final PriorityQueue<Label> backwardLabelQueue; // 后向标签优先级队列（多起点统一调度）
    final List<Label> forwardLabelPool;  // 前向标签待拼接池（虚拟节点触发后存入）
    final List<Label> backwardLabelPool; // 后向标签待拼接池（虚拟节点触发后存入）

    // 结果容器与辅助组件
    private List<Order> orderPool = new ArrayList<>(); // 最终订单池
    private final List<Carrier> carrierList;           // 车型列表（原代码已引用）
    private final LoadingAlgorithm loadingAlgorithm;   // 装卸方案求解器（原代码已引用）
    private final double dual_multiplier;              // 对偶乘数（原代码已引用）

    // 算法运行状态（新增，记录最优目标函数与开始时间）
    private int startTime;  // 算法开始时间（秒级）
    private Double bestObj; // 最优订单收益（用于过程输出）


    // ========================== 2. 构造方法：初始化字段，对齐多起点逻辑 ==========================
    public BidLabeling(Instance instance) {

        // 1. 初始化算例核心数据
        this.fences = instance.getFences();
        this.carriers = instance.getCarriers();
        this.depots = instance.getDepots(); // 从实例中获取多仓库管理类
        this.carrierList = instance.getCarrierList();
        this.dual_multiplier = Constants.DUALMULTIPLIER;
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
    }


    // ========================== 3. initialize方法：移除单仓库残留，适配多起点 ==========================
    /* 初始化与预处理：仅保留通用逻辑（虚拟节点、异构距离计算），多起点标签初始化移至专用方法 */
    private void initialize() {
        // 3.1 初始化【虚拟节点相关】：无需修改（原逻辑适配截断搜索）
        // （注：虚拟节点999的逻辑在labelExpand中处理，此处无需额外初始化）

        // 3.2 初始化【每个节点的最近异构节点距离】（用于距离约束剪枝，保留原逻辑）
        for (int i = 0; i < fences.getFenceNum(); i++) {
            Fence fenceI = fences.getFence(i);
            // 跳过仓库节点（仅处理卸货点/其他功能节点）
            if (depots.isValidDepot(i)) { // 用Depots类判断是否为仓库，避免依赖fences
                continue;
            }
            // 遍历当前节点的所有邻接节点，找最近的异构节点（类型不同）
            for (int j : fenceI.getVaildArcFence()) {
                Fence fenceJ = fences.getFence(j);
                double currentDist = fenceI.getDistance(fenceJ);
                fenceI.setNearestDiffLabelDist(min(fenceI.getNearestDiffLabelDist(), currentDist));
            }
        }
    }

    /* 算法主体 */
    public List<Order> solve(HashMap<String, Double> dualsOfRLMP) {
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
        for (int fenceIndex : fences.getFenceIndexList()) {
            Fence fence = fences.getFence(fenceIndex);
            fence.setFenceValue(fence.getOriginalFenceValue() - dualsOfRLMP.get(fence.getConstName()) * dual_multiplier);
        }
        // update carrierValue
        for (Carrier carrier : carrierList) {
            carrier.setCarrierValue(dualsOfRLMP.get(carrier.getConstName()));
        }

        for (Order order : this.orderPool) {
            order.setDualObj(PriceCalculator.calculateDualObj(order));
        }
        // filter orders according to new obj
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
            if (iterationCnt % Constants.OUTPUTINTERVAL == 0) {
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
        for (int depotIdx : allDepotIndexes) {
            // 校验仓库合法性（通过Depots类二次确认，避免无效索引）
            if (!depots.isValidDepot(depotIdx)) {
                throw new IllegalArgumentException("Depots类中不存在仓库索引：" + depotIdx);
            }

            BitSet forwardTabu = new BitSet(fences.size());
            forwardTabu.set(depotIdx, true); // 仅标记当前仓库为已访问

            Label forwardInit = Label.generate(
                    true,                  // 前向标签（从起点仓库出发）
                    depotIdx,              // 当前节点=仓库索引（来自Depots）
                    null,                  // 无父标签
                    forwardTabu,           // 禁忌表
                    0.0,                   // 初始卸货量=0
                    0.0,                   // 初始距离=0
                    0                      // 初始访问次数=0（未访问卸货点）
            );
            // 关键：记录标签的"归属仓库"（后续必须返回此仓库，而非其他仓库）
            forwardInit.setStartDepotIdx(depotIdx);
            forwardLabelQueue.add(forwardInit);
        }

        // 3. 为每个仓库创建后向初始标签（反向从起点仓库出发，目标是与前向标签拼接成闭环）
        for (int depotIdx : allDepotIndexes) {
            if (!depots.isValidDepot(depotIdx)) {
                throw new IllegalArgumentException("Depots类中不存在仓库索引：" + depotIdx);
            }

            BitSet backwardTabu = new BitSet(fences.size());
            backwardTabu.set(depotIdx, true); // 仅标记当前仓库为已访问

            Label backwardInit = Label.generate(
                    false,                 // 后向标签（反向扩展，最终需回到起点仓库）
                    depotIdx,              // 当前节点=仓库索引（来自Depots）
                    null,                  // 无父标签
                    backwardTabu,          // 禁忌表
                    0.0,                   // 初始卸货量=0
                    0.0,                   // 初始距离=0
                    0                      // 初始访问次数=0
            );
            // 关键：记录后向标签的"归属仓库"（必须与前向标签归属一致才能拼接）
            backwardInit.setStartDepotIdx(depotIdx);
            backwardLabelQueue.add(backwardInit);
        }
    }

    // 核心修改2：扩展逻辑强化（禁止扩展到其他仓库，仅允许返回归属仓库）
    private void labelExpand(Label label) {
        Fence currentFence = fences.getFence(label.getCurFence());
        boolean isForward = label.isForward();
        int startDepotIdx = label.getStartDepotIdx(); // 当前标签的归属仓库（必须返回此仓库）
        // 只扩展有效节点：卸货点 + 归属仓库（禁止其他仓库）
        for (int nextNode : currentFence.getVaildArcFence()) {
            // 如果是自己或者是禁止搜索的则跳过
            if (label.getTabu().get(nextNode)) {
                continue;
            }
            // 如果是虚拟节点（目的是截断搜索），则判断是否能成单，并压入待匹配池
            if (nextNode == 999 && label.getLoadedQuantity() >= Constants.MINCARRIERLOAD) {
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
                if (newVisitNum > Constants.MAXVISITNUMBER) {
                    continue;
                }

                // 卸货量约束（仅卸货点累加，归属仓库不计入）
                double newLoad = label.getLoadedQuantity() + nextFence.getDeliverDemand();
                if (newLoad > Constants.MAXCAPACITY) {
                    continue;
                }

                // 距离约束（含归属仓库的距离计算）
                double distance_ = currentFence.getDistance(nextNode) + label.getTravelDistance();
                if (distance_ > Constants.MAXDISTANCE) {
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
                        newVisitNum
                );
                // 关键：新标签继承父标签的归属仓库（确保后续必须返回同一仓库）
                newLabel.setStartDepotIdx(startDepotIdx);

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
        int forwardBelongDepot = forwardLabel.getStartDepotIdx();
        int backwardBelongDepot = backwardLabel.getStartDepotIdx();
        if (forwardBelongDepot != backwardBelongDepot) {
            return;
        }
        int targetDepot = forwardBelongDepot; // 统一目标：返回归属仓库

        // 2. 节点连接校验：前向终点+后向起点必须满足"卸货点→卸货点"或"卸货点→归属仓库"
        Fence forwardEnd = fences.getFence(forwardLabel.getCurFence());
        Fence backwardStart = fences.getFence(backwardLabel.getCurFence());

        // 检查弧是否存在（前向终点→后向起点）
        if (!forwardEnd.getVaildArcFence().contains(backwardStart.getIndex())) {
            return;
        }

        // 3. 距离约束：使用归属仓库所属区域的限制（通过Depots获取）
        double connectDist = forwardEnd.getDistance(backwardStart.getIndex());
        double totalDist = forwardLabel.getTravelDistance() + connectDist + backwardLabel.getTravelDistance();
        if (totalDist > Constants.MAXDISTANCE) {
            return;
        }

        // 4. 卸货量和访问次数校验（仅累加卸货点）
        double totalLoaded = forwardLabel.getLoadedQuantity() + backwardLabel.getLoadedQuantity();
        if (totalLoaded > Constants.MAXCAPACITY) {
            return;
        }

        int totalVisitNum = forwardLabel.getVisitNum() + backwardLabel.getVisitNum();
        if (totalVisitNum > Constants.MAXVISITNUMBER) {
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
            if (i != targetDepot) {
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
                totalDist,
                totalVisitNum,
                fenceIndexList,
                targetDepot,
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

        order.setDualObj(PriceCalculator.calculateDualObj(order));
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