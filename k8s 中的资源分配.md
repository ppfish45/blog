### 什么是 QoS

QoS 是 Quality of Service 的缩写，是 k8s 针对不同服务质量的预期，它具体体现在两个具体的指标：**内存和CPU**。我们谈论 QoS 是在 node 层面的，也就是说，当节点上的资源紧张时，node 上的 kubelet 会根据预期设置的不同 QoS 来对不同的 Pod 进行处理。

QoS 分为三个等级：`Guaranteed`，`Burstable` 和 `Best-Effort`。

### 理解 k8s 的资源分配

requests 是最小资源要求，limits 是资源使用上限。而 k8s 中 pod 对资源的申请是以容器作为最小单位进行的，每个容器都可以设置它的 limits 和 requests。

注意，如果一个只设置了 requests 却没有设置 limits，则它的 limits 为节点上 resource 的最大值。若容器只指定了 limits 却没有指定 requests，则 requests 等于 limits。

### Qos 的三个级别

+ Guaranteed

    属于该级别的 pod 有两种：

    + pod 中所有容器都设置了且只设置了 CPU 和内存的 limits

    + pod 中所有容器都设置了 CPU 和内存的 limits 和 requests，而且 `requests = limits`

+ Burstable

    属于该级别的 pod 满足：

    + 存在至少一个 pod，其 requests 和 limits 不相等

+ Best-Effort

    属于该级别的 pod 满足：

    + pod 中所有容器的 resources 均未设置 requests 和 limits

### 根据 QoS 进行资源回收

k8s 通过 `cgroup` 给 pod 设置 QoS 级别，当资源不足时，会优先 `kill` 掉优先级低的 pod。实际操作中，这个优先级通过 `OOM` 分数来体现，其范围为 `0-1000`，它的分数值是根据 `OOM_ADJ` 参数计算得出。

而对于 Guaranteed 级别的 pod，`OOM_ADJ` 被设置成了 `-998`，而对于 Best-Effort 级别的 pod，`OOM_ADJ` 设置成了 `1000`，而对于 Burstable 级别的 pod，`OOM_ADJ` 的取值为 `2-999`。

对于 k8s 的保留资源，比如 kubelet，docker，它们的 `OOM_ADJ` 都被设置为了 `-999`，意思是他们不会被 `OOM` 给 `kill` 掉。

### QoS 的 pod 被 kill 掉的顺序和场景

+ Best-Effort

    系统用完了所有内存时，这一类的 pod 会被最先 `kill` 掉

+ Burstable

    系统用完了所有内存，且没有 Best-Effort 类型的容器被 `kill` 时，这一类 pod 会被 `kill` 掉

+ Guaranteed

    系统用完所有类型，且 Burstable 和 Best-Effort 类型都被 `kill` 完，这类 pod 会被 `kill` 掉

### 参考资料

https://www.qikqiak.com/post/kubernetes-qos-usage/