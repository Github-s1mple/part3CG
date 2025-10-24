package algo;

public class old {
    /* 双向标号搜索 */
    private void bidirectionalSearch(boolean isLongDistance) {
        int iterationCnt = 0;
        while (true) {
            // 前向标号搜索
            if (!forwardLabelQueue.isEmpty()) {
                this.labelExpand(forwardLabelQueue.poll(), isLongDistance);
            }
            // 后向标号搜索
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

    private void labelExpand(Label label, boolean isLongDistance) {
        Fence fence = fences.getFence(label.getCurFence());
        boolean isForward = label.isForward();
        for (int nextNode : fence.getDistances().keySet()) {
            // 如果是自己或者是禁止搜索的则跳过
            if (label.getTabu().get(nextNode)) {
                add_record(NameConstants.LABEL_EXPAND_TABU);
                continue;
            }
            // 如果是虚拟节点（目的是截断搜索），则判断是否能成单，并压入待匹配池
            if (nextNode == fences.getDepot() && label.getLoadedQuantity() >= regions.getMinCarrierLoad()) {
                if (isForward) {
                    for (Label labelI : this.backwardLabelPool) {
                        this.labelConnect(label, labelI, isLongDistance);
                    }
                    this.forwardLabelPool.add(label);
                } else {
                    for (Label labelI : this.forwardLabelPool) {
                        this.labelConnect(labelI, label, isLongDistance);
                    }
                    this.backwardLabelPool.add(label);
                }
            } else if (nextNode != fences.getDepot()) {
                // update params
                Fence nextFence = fences.getFence(nextNode);
                String rfId_ = label.getRfId() == null ? nextFence.getRfId() : label.getRfId();
                Region region_ = regions.get(rfId_);
                Integer maxCapacity = isForward ? region_.getMaxCapacity() : regions.getMaxCapacity();
                Double maxDistance;
                if(!isLongDistance){
                    maxDistance = isForward ? region_.getMaxDistance() : regions.getMaxDistance();
                }else {
                    maxDistance = Constants.longDistanceMax;
                }

                Integer maxVisitNum = isForward ? region_.getMaxVisitNum() : regions.getMaxVisitNum();

                if (label.getVisitNum() > maxVisitNum - 2) {
                    add_record("labelExpand: visit num filter num");
                    continue;
                }

                int minNumber_ = label.getMinNumber() + nextFence.getMinDispatchNum();
                if (minNumber_ > maxCapacity) {
                    add_record("labelExpand: capacity filter num");
                    if (isLongDistance && isForward) {
                        //log.info("容量限制");
                    }
                    continue;
                }

                // 根据是否为长距离调度场景进行不同筛选
                double distance_ = (isForward ? fence.getDistance(nextFence) : nextFence.getDistance(fence))
                        + label.getTravelDistance();
                if (distance_ > maxDistance - nextFence.getNearestDiffLabelDist()) {
                    add_record("labelExpand: distance filter num");
                    continue;
                }

                BitSet tabu_ = (BitSet) label.getTabu().clone();
                tabu_.set(nextNode, true);

                //小点插入，如果nextFence的缺口/冗余不超过smallFenceThreshold并且当前标签的smallFence数量不超过上限，则继续进行搜索
                int smallLoadedN = label.getSmallLoadedN();
                if (Math.abs(nextFence.getDemand()) < smallFenceThreshold.get(nextFence.getFenceType())) {
                    smallLoadedN += 1;
                    if (smallLoadedN > smallFenceMaxNum.get(nextFence.getFenceType())) {
                        continue;
                    }
                }

                Label newLabel = Label.generate(isForward,
                        nextFence.getIndexInInstance(), label, tabu_,
                        label.getLoadedQuantity() + Math.abs(nextFence.getDemand()), distance_,
                        label.getVisitNum() + 1, rfId_, minNumber_, smallLoadedN);
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
            if (this.dominantRuleDispatchAndVisit(label, labelI) == 0) {
                if (this.dominantRule(label, labelI) == 1) {
                    this.labelPool.get(fenceIdx).remove(li);
                    if (isForward) {
                        this.forwardLabelQueue.remove(labelI);
                    } else {
                        this.backwardLabelQueue.remove(labelI);
                    }
                    add_record(NameConstants.DOMINANT_ADD);
                } else if (this.dominantRule(label, labelI) == -1) {
                    add_record(NameConstants.DOMINANT_ADD);
                    return;
                } else {
                    li += 1;
                }
            } else if (this.dominantRuleDispatchAndVisit(label, labelI) == 1) {
                this.labelPool.get(fenceIdx).remove(li);
                if (isForward) {
                    this.forwardLabelQueue.remove(labelI);
                } else {
                    this.backwardLabelQueue.remove(labelI);
                }
                add_record(NameConstants.DOMINANT_ADD);
            } else if (this.dominantRuleDispatchAndVisit(label, labelI) == -1) {
                add_record(NameConstants.DOMINANT_ADD);
                return;
            }
        }
        this.labelPool.get(fenceIdx).add(label);
        if (isForward) {
            this.forwardLabelQueue.add(label);
        } else {
            this.backwardLabelQueue.add(label);
        }
    }

    private Integer dominantRule(Label label1, Label label2) {
        // 1: label1 dominant label2; -1: label2 dominant label1; 0: no dominant
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

    /* 标号连接 */
    private void labelConnect(Label forwardLabel, Label backwardLabel, boolean isLongDistance) {
        Fence forward_last_fence = fences.getFence(forwardLabel.getCurFence());
        Fence backward_first_fence = fences.getFence(backwardLabel.getCurFence());
        Region forwardRegion = this.regions.get(forwardLabel.getRfId());

        if (!forward_last_fence.getArcs().contains(backward_first_fence.getIndexInInstance())) {
            add_record(NameConstants.ARC_FILTER);
            return;
        }

        double connect_dist = forward_last_fence.getDistance(backward_first_fence);
        if (connect_dist < this.minUnloadDistance) {
            add_record(NameConstants.MIN_UNLOAD_FILTER);
            return;
        }

        // 附加长距离调度距离最小值
        double total_dist = forwardLabel.getTravelDistance() + connect_dist + backwardLabel.getTravelDistance();
        if(!isLongDistance) {
            if (total_dist > forwardRegion.getMaxDistance()) {
                add_record(NameConstants.MAX_DISTANCE_FILTER);
                return;
            }
        }else{
            if (total_dist < Constants.longDistanceMin) {
                add_record(NameConstants.MIN_DISTANCE_FILTER);
                return;
            }
            if (total_dist > Constants.longDistanceMax) {
                add_record(NameConstants.MAX_DISTANCE_FILTER);
                return;
            }
        }

        int total_visit_num = forwardLabel.getVisitNum() + backwardLabel.getVisitNum();
        if (total_visit_num > forwardRegion.getMaxVisitNum()) {
            add_record(NameConstants.MAX_VISIT_FILTER);
            return;
        }

        int minDispatchNum = Math.max(forwardLabel.getMinNumber(), backwardLabel.getMinNumber());
        int maxDispatchNum = min(forwardLabel.getLoadedQuantity(), backwardLabel.getLoadedQuantity());
        if (maxDispatchNum < minDispatchNum) // 不满足最小装卸量要求
            return;

        if (forwardLabel.getLoadedQuantity() < backwardLabel.getMinNumber()
                || backwardLabel.getLoadedQuantity() < forwardLabel.getMinNumber() - Constants.maxExtraUnloadNum) {
            add_record(NameConstants.MIN_SINGLE_LOAD_FILTER);
            return;
        }

        // 根据连接潜力剪枝
        List<Integer> forwardRoute = forwardLabel.getFenceIndexList();
        List<Integer> backwardRoute = backwardLabel.getFenceIndexList();
        // 若不能产生利润则剪枝
        double maxBackwardValue = 0;
        for (int index : backwardRoute) {
            maxBackwardValue = Math.max(maxBackwardValue, fences.getFenceValue(index));
        }

        double minForwardValue = Double.POSITIVE_INFINITY;
        for (int index : forwardRoute) {
            minForwardValue = min(minForwardValue, fences.getFenceValue(index));
        }

        if (maxBackwardValue - minForwardValue < 0) {
            add_record(NameConstants.NO_PROFIT_FILTER);
            return;
        }

        List<Integer> fenceIndexList = new ArrayList<>();
        fenceIndexList.addAll(forwardRoute);
        fenceIndexList.addAll(backwardRoute);

        // 构造完整路径
        Route route = Route.builder().
                rfId(forwardRegion.getRegionId()).
                totalDistance(total_dist).
                totalVisitNum(total_visit_num).
                maxDispatchNum(maxDispatchNum).
                minDispatchNum(minDispatchNum).
                loadIndex(forwardRoute).
                unloadIndex(backwardRoute).
                isLongDistance(isLongDistance).
                fenceIndexList(fenceIndexList).build();

        // 根据路径经过点集筛除（访问围栏相同，但是顺序不同，只保留短的）
        Order sameNodeSetOrder = this.visited2order.getOrDefault(route.getRouteVitedString(), null);
        if (sameNodeSetOrder != null) {
            if (route.getTotalDistance() >= sameNodeSetOrder.getTotalDistance()) {
                add_record(NameConstants.SAME_NODE_FILTER);
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

        if (order.getObj() < Constants.OBJ_LB) {
            add_record(NameConstants.NEGATIVE_OBJ_FILTER);
            return;
        }

        order.setDualObj(objCal.calculateDualObj(order));
        // 连接成功
        if (sameNodeSetOrder != null) {
            this.orderPool.remove(sameNodeSetOrder); // 去除路径被支配的工单
            add_record(NameConstants.SAME_NODE_FILTER);
        }
        this.visited2order.put(route.getRouteVitedString(), order);
        this.orderPool.add(order);
        this.bestObj = Math.max(this.bestObj, order.getObj());
    }

    private Order loading(Route route) {
        Order order = this.loadingAlgorithm.solve(route);

        if (order == null) {
            return null;
        }

        if (!ConstraintsManager.isOrderFeasible(order, this.minUnloadDistance, fences)) {
            return null;
        }

        order.setObj(objCal.calculatePrimalObj(order));
        add_record(NameConstants.CONNECT_SUCCESS);
        return order;
    }在我的代码上进行修改，并讲述原理
}
