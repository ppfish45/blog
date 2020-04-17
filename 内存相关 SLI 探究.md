
+ 通过调整 kswapd 的水位减少读取频繁时的 jitter
  http://www.unixer.de/ross2014/slides/ross2014-oyama.pdf

+ 优化 VM 上的 double cache 问题
  https://dl.acm.org/doi/abs/10.1145/2837185.2837267
  使用的 indicator 是 hit ratio，来研究减少 vm 的 page cache 对实际应用的影响

### 研究流程

#### 问题

希望找到一些好的 SLI 来评估 page cache 的激进回收产生的影响。

### 一些想法

- 不能主观臆测去选取一些可能有效的 indicator 当做 SLI
- 之前有提到一些关键性能指标（rt，qps 等），这些是决定用户是否满意该优化的决定性指标

### 筛选流程

