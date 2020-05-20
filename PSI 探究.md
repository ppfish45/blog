## Pressure Stall Information (PSI)

### Overview

PSI 提供了一个监控 内存，CPU 和 IO 资源压力的直观的指标。它们可以用来检测资源是否发生了短缺，搭配 cgroup2 或是其他工具，采取相应的应对措施。PSI 在 Linux Kernel 内已经有部署了。

### Interface

资源信息可以在 `/proc/pressure/[cpu, memory, io]` 中查看。不同的资源有不同的输出。同时也可以根据 cgroup 来进行监控（如果安装了的话），`/sys/fs/cgroup/<cg_name>/[cpu, memory, io].pressure`

- CPU：`some avg10=0.00 avg60=0.00 avg300=0.00 total=0`

- 内存 或者 IO：

  `some avg10=0.00 avg60=0.00 avg300=0.00 total=0`

  `full avg10=0.00 avg60=0.00 avg300=0.00 total=0`

其中 `avg10`，`avg60` 或 `avg300` 是移动平均值，单位是秒。`total` 指的是停滞时间的总长度，单位为毫秒。注意到这些指标中有 `some` 和 `full` 两种。

其中 `some` 这个指标描述的是 “某些任务因为资源短缺而暂停” 的时间比例 (“percentage of time some (one or more) tasks were delayed due to lack of resources”)，也就是说，从整体上来看资源还是在被正常使用的，只是有部分应用出现资源短缺。

![some_example](https://facebookmicrosites.github.io/psi/docs/assets/someCrop.png)

而 `full` 指的是 “所有任务因为资源短缺而暂停” 的时间比例，也就是说，在这段时间内，系统完全失去了生产力 (“percentage of time in which all tasks were delayed by lack of resources, i.e., the amount of completely unproductive time”)。比如在下面的例子中，`some` 的值为 `50.00%` 而 `full` 的值是 `16.77%`。

![some_example](https://facebookmicrosites.github.io/psi/docs/assets/FullCrop.png)

如果 `full` 的值过高，说明总体的产量因为资源受限受到了影响。因此这个指标可以反应某一段时间内，或某个特定的操作之后与资源相关的延迟情况。

## 应用实例：`oomd` in Sandcastle

### Overview

`OOM (Out of memory)` 原本是 Linux 内核的一个机制，当系统的内存耗尽以后，内核会调用 `OOM` 杀掉一个或多个进程。`OOM` 的调用是非常消耗系统资源的。

`oomd` 的出现是为了解决 `OOM` 的问题，它在 `OOM` 发生以前就会在 userspace 中采取预见性的措施，比如杀掉某些不合规的进程。

### Sandcastle

Sandcastle 是 facebook 的一套代码编译，测试和落地系统，也是 facebook 最大的服务之一。它曾受困于内存耗尽和 `OOM` 的问题，许多任务被重复地杀死。近 5% 左右的 Sandcastle 主机每天都会意外重启，而 facebook 不能通过简单地扩大机器规模来解决问题。

具体来说，Sandcastle 通过 Tupperware （facebook 的容器方案）来运行 workers，workers 会不停地从队列中获取 tasks 并运行。它们的内存来自 `tmpfs`。当 build 进程被杀死以后，它们占用的内存不会被释放，因为这些内存是和 workers 绑定在一起的（进程被杀死，但 worker 并没有终止)。

另外一个麻烦的地方是，`OOM Killer` 往往只会杀死 tasks 中的某一个线程，但整个 task 会因此变得 `undefined`。在这种情况下，只能把所有的 tasks 都取消并重新运行，这会浪费大量的时间。

### 解决方案

希望创建一个 cgroup 结构满足：

- 使 `OOM Killer` 及时释放 tasks 占用的 `tmpfs` 的空间

- 让 `OOM Killer` 每次杀死一整个 task 而非它其中的某一个线程

因此具体的做法是：

- 每个 worker 中的每一个 task 都在自己的 `sub-cgroup` 中运行，且在 `workload.slice` 中，一个 task 所有的进程都包括在它们自己的 `cgroup` 中。

- `oomd` 会观测每个 task 的 `cgroup`。

- 每个 task 都会有自己的 `mount namespace` 和 `tmpfs` 挂载点，而不是依赖于 worker 的 `mount namespace` 和 `tmpfs` 挂载点。

![sol](https://facebookincubator.github.io/oomd/docs/assets/sandcastle-after.png)

这样把每个 task 放到一个单独的 `cgroup` 以后，加上 `oomd` 的配置，我们可以做到每次直接把一整个 `cgroup` 中的所有进程杀掉。

也正因为 `mount namespace` 和 `tmpfs` 只被一个 task 中的进程挂载，当一个 task 被杀掉时，这些挂载点也会随即被释放。

因此 Tupperware 也不再需要清理这些 task 和重启他们，只需要在注意到有 task 被杀死以后，尽快从 job queue 中运行的新的 task 即可。

### oomd 杀死 task 实例

![chart](https://facebookincubator.github.io/oomd/docs/assets/scuba-chart.png)

![log](https://facebookincubator.github.io/oomd/docs/assets/oomd-output.png)

可以看到基本思路就是当 `PSI` 大于某一个水位一段时间后，就开始选择 task 并杀掉。

### 成果

机器的意外重启率从 `5%` 降低到了 `0.5%`，且 `OOM Killer` 之后的不可用时间又几十分钟降低到仅仅几秒钟。每台主机的 tasks 运行量增加了 `35%`。
