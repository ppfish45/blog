
+ 通过调整 kswapd 的水位减少读取频繁时的 jitter
  http://www.unixer.de/ross2014/slides/ross2014-oyama.pdf

+ 优化 VM 上的 double cache 问题
  https://dl.acm.org/doi/abs/10.1145/2837185.2837267
  使用的 indicator 是 hit ratio，来研究减少 vm 的 page cache 对实际应用的影响

### 研究流程

#### 问题

希望找到一些好的 SLI 来评估 page cache 的激进回收产生的影响。

#### 一些想法

- 不能主观臆测去选取一些可能有效的 **内存相关的指标** 当做 SLI
- 之前有提到一些关键性能指标（rt，qps 等），这些是决定用户是否满意该优化的决定性指标
- 应该要探究一下这些 内存指标 和 关键性能指标 之间的关系，选择那些明显和性能指标相关的内存指标来作为 SLI

#### 筛选流程

在实验环境下运行一些服务，其 rt，qps 这些指标可以被实时监控，然后尝试手动触发回收不同门限的 page cache，看哪些内存指标的变化情况和性能指标的变化最为相关。

问题是，rt 和 qps 的结果受到很多因素的影响，他们的变化不一定和 page cache 有着直接的关系


