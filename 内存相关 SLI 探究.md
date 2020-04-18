


### 研究流程

#### 问题

希望找到一些好的 SLI 来评估 page cache 的激进回收产生的影响。

#### 最直接的思路

直接考虑，如果我们回收了过多的 “热内存”，即短时间内可能还被访问到的缓存，那么会出现：

+ hit ratio 降低，因为有显著数量的热内存被回收掉

+ page in / page out 增加

而两个的关联性非常强，即 hit ratio 的降低会带动 page in 和 page out 的增高，因此可以只关注 hit ratio 一个指标。

从另一方面考虑，回收 page cache 会带来其他的影响吗？它不会对程序产生功能性的影响，只会拖慢程序的效率，而效率的降低对应着 hit ratio 的降低。因此监控 hit ratio 作为 SLI 是合理的。

#### 如果没有直接的 hit ratio 统计，如何算出这个值

For the kernels I'm studying (3.2 and 3.13), here are the four kernel functions I'm profiling to measure cache activity:

+ mark_page_accessed() for measuring cache accesses
+ mark_buffer_dirty() for measuring cache writes
+ add_to_page_cache_lru() for measuring page additions
+ account_page_dirtied() for measuring page dirties

mark_page_accessed() shows total cache accesses, and add_to_page_cache_lru() shows cache insertions (so does add_to_page_cache_locked(), which even includes a tracepoint, but doesn't fire on later kernels). I thought for a second that these two were sufficient: assuming insertions are misses, I have misses and total accesses, and can calculate hits.

The problem is that accesses and insertions also happens for writes, dirtying cache data. So the other two kernel functions help tease this apart (remember, I only have function call rates to work with here). mark_buffer_dirty() is used to see which of the accesses were for writes, and account_page_dirtied() to see which of the insertions were for writes.

#### 一些不那么直接的想法

+ 之前有提到一些关键性能指标（rt，qps 等），这些是决定用户是否满意该优化的决定性指标

+ 应该要探究一下这些 内存指标 和 关键性能指标 之间的关系，选择那些明显和性能指标相关的内存指标来作为 SLI

在实验环境下运行一些服务，其 rt，qps 这些指标可以被实时监控，然后尝试手动触发回收不同门限的 page cache，看哪些内存指标的变化情况和性能指标的变化最为相关。

#### 缺陷

rt 和 qps 的结果受到很多因素的影响，他们的变化不一定和 page cache 有着直接的关系

### 参考资料

+ 通过调整 kswapd 的水位减少读取频繁时的 jitter

  http://www.unixer.de/ross2014/slides/ross2014-oyama.pdf

+ 优化 VM 上的 double cache 问题

  https://dl.acm.org/doi/abs/10.1145/2837185.2837267

  使用的 indicator 是 hit ratio，来研究减少 vm 的 page cache 对实际应用的影响

+ Average memory swapped in or out / Average memory swapped

  https://subscription.packtpub.com/book/virtualization_and_cloud/9781782170006/2/ch02lvl1sec23/key-memory-performance-metrics-to-monitor

+ 如何测量 linux 内的 hit ratio

  http://www.brendangregg.com/blog/2014-12-31/linux-page-cache-hit-ratio.html
