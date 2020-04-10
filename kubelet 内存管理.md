### kubelet 资源不足时的处理机制

#### 驱逐策略

kubelet 会主动监控计算资源的使用情况，并在短缺时主动结束一个或者多个 pod 以回收资源。被回收的 pod 的 `PodPhase` 会变成 `Failed`。

主要有以下几个监控指标：

+ `memory.available := node.status.capacity[memory] - node.stats.memory.workingSet`

+ `nodefs.available := node.stats.fs.available`

+ `nodefs.inodesFree := node.stats.fs.inodesFree`

+ `imagefs.available := node.stats.runtime.imagefs.available`

+ `imagefs.inodesFree := node.stats.runtime.imagefs.inodesFree`

其中 `memory.available` 是由 `cgroupfs` (`sys/fs/cgroup/...`) 获取而不是类似 `free -m` 的工具。具体获取的流程如下脚本：

```
#!/bin/bash
#!/usr/bin/env bash

# This script reproduces what the kubelet does
# to calculate memory.available relative to root cgroup.

# current memory usage
memory_capacity_in_kb=$(cat /proc/meminfo | grep MemTotal | awk '{print $2}')
memory_capacity_in_bytes=$((memory_capacity_in_kb * 1024))
memory_usage_in_bytes=$(cat /sys/fs/cgroup/memory/memory.usage_in_bytes)
memory_total_inactive_file=$(cat /sys/fs/cgroup/memory/memory.stat | grep total_inactive_file | awk '{print $2}')

memory_working_set=${memory_usage_in_bytes}
if [ "$memory_working_set" -lt "$memory_total_inactive_file" ];
then
    memory_working_set=0
else
    memory_working_set=$((memory_usage_in_bytes - memory_total_inactive_file))
fi

memory_available_in_bytes=$((memory_capacity_in_bytes - memory_working_set))
memory_available_in_kb=$((memory_available_in_bytes / 1024))
memory_available_in_mb=$((memory_available_in_kb / 1024))

echo "memory.capacity_in_bytes $memory_capacity_in_bytes"
echo "memory.usage_in_bytes $memory_usage_in_bytes"
echo "memory.total_inactive_file $memory_total_inactive_file"
echo "memory.working_set $memory_working_set"
echo "memory.available_in_bytes $memory_available_in_bytes"
echo "memory.available_in_kb $memory_available_in_kb"
echo "memory.available_in_mb $memory_available_in_mb"
```

+ `/proc/meminfo` 中的 `MemTotal` 为可供 Kernel 支配的内存，在系统运行期间一般不会变动，可以认为是总内存

+ `/sys/fs/cgroup/memory/memory.usage_in_bytes` 一个当前内存使用量的估计值（包含 cache 和 buffer）

+ `/sys/fs/cgroup/memory/memory.stat` 中的 `total_inactive_file` 指 `INACTIVE` 文件页 LRU 链表上的内存数量，以字节为单位

注意 kubelet 在计算 `memory.available` 时将 `inactive_file` 排除在外了，指的是在 Inactive LRU 链表上的文件页大小，在系统内存紧张时会被回收。

#### 驱逐阈值

一个驱逐阈值的定义类似：

+ `memory.available<10%`

+ `memory.available<1Gi`

阈值分为 **软驱逐阈值** 和 **硬驱逐阈值** 两种。

一个软驱逐阈值由 **宽限期** 和 **驱逐阈值** 组成，在宽限期内（比如内存占用 `> 85%` 持续不足 `10` 秒），kubelet 不会采取回收任何 **与驱逐信号相关** 的资源。

如果达到阈值的时间超过了宽限期，则会开始驱逐 pod，并会遵循 `grace period`，即优雅结束一个 pod（等其执行完清理逻辑之后终止）。如果定义了 `pod.Spec.TerminationGracePeriodSeconds`，则会以该值和软驱逐定义的 `grace period` 较小者为准。

软驱逐支持以下标记：

+ `--eviction-soft`： 描述了驱逐阈值的集合（例如 `memory.available<1.5Gi`），如果在宽限期之外满足条件将触发 pod 驱逐

+ `--eviction-soft-grace-period`： 触发条件持续多久才开始驱逐，例如 `memory.available=2m30s`

+ `--eviction-max-pod-grace-period`：驱逐 pod 时等待的 `grace period` 的长度，让 pod 做一些清理工作，如果到时间还没有结束就做 kill

**硬驱逐** 比较干脆果断，一旦达到阈值，立刻开始驱逐，并且没有 `grace period`。它对应的参数只有一个：

+ `--evictio-hard`： 描述了驱逐阈值的集合

#### 节点状态

kubelet 会将一个或者多个驱逐信号对应到相应的节点状态，状态有以下两种：

+ `MemoryPressure`： 与 `memory.available` 相关

+ `DiskPressure`： 与 `nodefs.available`, `nodefs.inodesFree`, `imagefs.available` 和 `imagefs.inodesFree` 相关

#### 驱逐哪些 pods ?

驱逐时，kubelet 会根据 pod requests 和 limits，QoS 和 pod 实际的资源使用决定驱逐顺序。具体来说，会按一下优先级：

1. 使用的紧张资源超过 requests 的 `BestEffort` 和 `Burstable` pod，内部会根据优先级和使用比例排序

2. 资源紧张使用量低于 requests 的 `Burstable` 和 `Guaranteed` 的 pod 只有当系统组件内存不够，且没有上面一项描述的那些 pod 可以驱逐时，才会被驱逐。且执行时这些 pod 还会根据优先级排序。

#### 防止波动

波动的情况有两种：

+ 如果 kubelet 驱逐 pod 到资源使用率低于阈值就停止，则有可能很快资源使用率又返回到阈值，从而又触发驱逐，如此循环往复。那么我们可以用 `--eviction-minimum-reclaim` 来配置至少要清理多少资源才会停止

+ 一个 pod 被驱逐后，k8s 会生成一个新的 pod 来替代他，并分配到一个节点继续运行。而这个分配逻辑是沿用之前的逻辑，因此它很有可能重新被分配到同一个节点，然后又可能触发驱逐，如此往复。要解决这个问题，通过设置 `--eviction-pressure-transition-period` 这个参数，限制了 kubelet 需要进入 **压力状态** 一段时间才可以向 api-server 报告本节点的情况，从而防止 pod 在短时间内又被分配到同一个节点。在这段压力状态中，必须确保没有资源使用超过阈值。

#### 回收磁盘资源

在驱逐 pod 之前，如果发现磁盘压力，如果节点针对容器有独占的 `imagefs`，则其回收资源的方式会不同。注意 `nodefs` 和 `imagefs` 的区别：

+ `nodefs`： 指 node 自身的存储，存储 daemon 的运行日志等，一般指 root 分区 `/`；
+ `imagefs`： 指 docker daemon 用于存储 image 和容器可写层 (writable layer) 的磁盘；

那么两种回收方式：

+ 如果使用了 `imagefs`： 如果 `nodefs` 超过了阈值，则 kubelet 通过驱逐 pod 和其容器来释放空间。如果 `imagefs` 超过阈值，则 kubelet 通过删除未使用的 image 来释放空间

+ 如果没有使用 `imagefs`： 如果 `nodefs` 超过了阈值，则 kubelet 会先删除停止运行的 pod 和容器，再删除全部没有使用的镜像

#### 节点 OOM 行为

正常情况下，节点的资源紧张时，都是通过驱逐行为来释放资源。但有可能在达到阈值之前，就触发了系统的 OOM 行为，即 Linux Kernel 的 `oom_killer`。kill 的顺序是根据 QoS 即设定的 `OOM_ADJ` 分数来决定的。

### 参考资料

https://www.kernel.org/doc/Documentation/cgroup-v1/memory.txt (cgroup 中 memory 相关)

https://hustcat.github.io/memory-usage-in-process-and-cgroup/

https://www.cnblogs.com/xuxinkun/p/5541894.html (cgroup 中的 memory 关系计算)

https://kubernetes.io/zh/docs/tasks/administer-cluster/out-of-resource/

---

### 参考资料

https://kubernetes.io/zh/docs/tasks/administer-cluster/out-of-resource/
https://cizixs.com/2018/06/25/kubernetes-resource-management/