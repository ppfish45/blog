### 什么是 kube-scheduler

对于一个新创建的 Pod，或是未被调度的 Pod，kube-scheduler 会选择一个最优的 Node 去运行这个 Pod。

寻找这个最优的 Node 分为两个步骤，过滤和打分。

### 过滤

过滤阶段会将所有满足 Pod 调度需求的 Node 选出来。例如，PodFitsResources 过滤函数会检查候选 Node  的可用资源能否满足 Pod 的资源请求。<u>在过滤之后，得出一个 Node 列表，里面包含了所有可调度节点</u>；通常情况下，这个 Node  列表包含不止一个 Node。如果这个列表是空的，<u>代表这个 Pod 不可调度</u>。

### 打分

scheduler 会从所有的<u>可调度节点</u>中选取一个最合适的 Node。根据当前启用的打分规则，调度器会给每一个可调度节点进行打分。若有多个最高得分的 Node，随机选取一个

### 参考资料

https://kubernetes.io/zh/docs/concepts/scheduling/kube-scheduler/

