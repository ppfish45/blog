### 什么是 Page Fault

Page Fault 并不是 “错误“。在 Linux 中，进程并不是直接访问物理内存，而是通过 MMU 来访问内存资源。关于 MMU 的介绍可以参考 “Linux 内存管理” 这篇博文。

当进程访问虚拟内存中某个 page 时，如果这个 page 还不在物理内存中，则 Linux 会产生一个 Hard Page Fault 中断。在这个时候，系统会从慢速设备（如硬盘）中读入对应 page 的数据，并建立映射关系，然后进程才可以访问这部分内存。

Page Fault 分为以下几种类型：

+ **Major Page Fault**: 即 Hard Page Fault，指访问的内存不在虚拟地址空间，也不在物理内存中，需要从慢速设备中载入。`swap` 就属于一种 Major Page Fault

+ **Minor Page Fault**: 即 Soft Page Fault，指访问的内存不在虚拟地址空间，但存在于物理内存中，只需要 MMU 建立相应映射关系即可（比如多个进程访问同一个共享内存中的数据，某些进程没有建立）

+ **Invalid Fault**: 也成为 Segment Fault，进程访问的内存地址不在虚拟地址空间范围内，属于越界访问

### 参考资料

https://yq.aliyun.com/articles/55820

---

### 什么是 Page-in / Page-out

物理内存不够用时，内核将 page 写入磁盘的过程叫做 Page-out，相反，从磁盘中读取 page 内容的过程叫做 Page-in。由此看出，Page Fault 很多时候是因为分页被 Page-out 了而产生的。

### 参考资料

http://www.ha97.com/4512.html

---

### 页回收和分配

页回收有两种情况，分别为

+ kswapd 进行定时的回收，也叫 **后台回收**

+ 另一种则是物理内存达到 `min` 水位时，Kernel 会直接阻塞当前的 task，开始 **direct page reclaim**

而内存分配也有两种情况，一种是 fast path，而另一种是 slow path。通常在内存非紧张的情况下，fast path 就可以搞定。

### Fast path

Fast path 的流程大致如下，如果系统挂载使用了 memory cgroup，如果超过了 cgroup 限额，则进行 direct reclaim，通过 do_try_to_free_pages 完成。如果没超过限额则进行 cgroup 的 charge 操作。

之后会从本地的 preferred zone 内存节点中查找空闲页，判断是否满足系统的 watermark 和 dirty ratio 的要求。如果满足则从 buddy system 直接拿 page，否则尝试对本地 preferred zone 进行页回收，且 fast path **只会回收 clean page （已经同步的 file page），而不会考虑 dirty page 或 mapped page**，因此不会有任何的 swap 或者 writeback，因此不会引发任何阻塞的 I/O 操作，如果这次回收的内存页数目不足则会进入 slow path。

### Slow path

Slow path 首先会唤醒 kswapd 进行 page reclaim 的后台操作。

之后重新尝试在本地 preferred zone 分配内存，如果失败会根据请求的GFP相关参数决定是否尝试忽略 watermark，dirty ratio 以及本地节点分配等要求进行再次重试，这一步中如果分配页时有指定 `__GFP_NOFAIL` 标记，则分配失败会一直等待重试。

如果没有 `__GFP_NOFAIL`，则会开始 page compact 和 direct page reclaim，如果仍然没有可用内存，则进入 OOM 流程

### 相关函数调用顺序

```
alloc_pages-------------------------------------页面分配的入口
  ->__alloc_pages_nodemask
    ->get_page_from_freelist--------------------直接从zonelist的空闲列表中分配页面
    ->__alloc_pages_slowpath--------------------在初次尝试分配失败后，进入slowpath路径分配页面
      ->wake_all_kswapds------------------------唤醒kswapd内核线程进行页面回收
      ->get_page_from_freelist------------------kswapd页面回收后再次进行页面分配
      ->__alloc_pages_direct_compact------------进行页面规整，然后进行页面分配
      ->__alloc_pages_direct_reclaim------------直接页面回收，然后进行页面分配
      ->__alloc_pages_may_oom-------------------尝试触发OOM
```

### Memory Compaction 内存规整

Memory Compaction 是在内存分配的 slow path 中被触发的，可以参见上一部分。其用来整理 buddy system 产生的内存碎片，从而重新组合出大段的连续物理内存，以供内存分配使用。

实现的原理是将可移动的页面重新进行分组，从而腾出空闲。

当然它也可以被手动触发，调用 API：

```
echo 1 > /proc/sys/vm/compact_memory
```

### 参考资料

http://abcdxyzk.github.io/blog/2015/09/18/kernel-mm-swappiness/

https://www.cnblogs.com/arnoldlu/p/8335532.html (关于 slow path 和 page compact)

---

### `compact_stall`

`compact_stall` is incremented every time a process stalls to run memory compaction so that a huge page is free for use.

简单来说它记录了 Memory Compaction 被执行了多少次，不包括手动触发的次数。

查看的 API 在 `/proc/vmstat`

### `page_scan`

在 `sar -B` 中，有 `pgscank/s` 和 `pgscand/s` 两种，是 `page scanned by kswapd` 和 `page scanned directly` 的缩写。代表每秒在 kswapd 和 direct reclaim 中被检查过的页面数量。

### `page_steal`

在 `sar -B` 中有 `pgsteal/s`，代表每秒被 page cache 和 swap cache 中被回收的页的数量。简单来说，这个指标代表内存刷新到磁盘的频率。

### `%vmeff`

`%vmeff = pgsteal / pgscan`，是用来衡量页回收效率的一个指标。如果它接近 `100%`，则几乎所有从 `INACTIVE` 队尾出来的页面都会被回收，而如果它太低的话，虚拟内存可能会遇到一些问题。如果该值为 `0`，代表没有页面被扫描过。

### 参考资料

http://linux.laoqinren.net/kernel/memory-compaction/

https://www.jianshu.com/p/ea7ed85918ac (各项指标对系统的影响)

---

### kswapd

kswapd 全程 Kernel Swap Daemon，其对内存进行周期性的检测。其最后调用的回收程序和 direct page reclaim 是一样的，都是 `shrink_zone()`。kswapd 达到阈值时，才会被触发。

### 水位标记 (watermark)

Linux Kernel 用水位标记来描述内存的压力情况。Linux 中有三个内存水位标记：`high`，`low` 和 `min`。意思是：

+ 剩余内存在 `high` 以上时，表示内存剩余较多，目前内存压力不大

+ `high` 到 `low` 之间，代表剩余内存有一定压力

+ `low` 到 `min` 之间，代表剩余内存不多了，压力较大

+ `min` 是最低的水位标记，当剩余内存达到这个状态，代表内存压力很大

+ 小于 `min` 这部分的内存，是保留给特定情况下使用的，一般不会分配

内存回收的具体标准是，

+ 当系统内存低于 `low` 时，kswapd 开始起作用，进行内存回收，直到剩余内存开始达到 `high` 才停止

+ 当剩余内存达到了或者低于 `min` 时，会触发直接回收 direct reclaim

### 函数调用关系

![https://oscimg.oschina.net/oscnet/33f9024d70cd92f9cc711df451500aa6047.jpg](https://oscimg.oschina.net/oscnet/33f9024d70cd92f9cc711df451500aa6047.jpg)

进行简单的初始化后，kswapd 进入一个死循环，然后接下来的工作分为两部分：

+ 仅当内存紧缺时（或 `INACTIVE` 页数紧缺时），会调用 `do_try_to_free_pages()`

+ 第二部分每一次循环都会调用，`refill_inactive_scan()`

两部分做完以后，kswapd 已经回收了许多页面了，此时可以唤醒那些因为内存不足而转入睡眠的进程。如果此时 kswapd 已经让系统脱离了内存短缺，则它会转入休眠状态，时长为 1 秒，当然在睡眠期间可能被提前唤醒。但如果依然是内存短缺状态，则只能调动 `OOM_Killer` 来杀死进程以回收一些内存。

### `do_try_to_free_pages()`

函数起始，会调用 `page_launder` 将 `INACTIVE` 的脏页面洗干净，变为可以分配的页面（可分配的页面即空闲页面，或者 `INACTIVE` 的干净页面）。如果可分配的页面依然短缺，则会从下面三个方面努力：

+ 在打开文件的过程当中，要分配表明着目录项 dentry 的数据结构以及表明着索引节点 inode 的数据结构，这些数据结构在文件关闭是并无当即释放，而是放到 LRU 队列中养起来，以备后面打开同一个文件时再用到。这样通过一段时间之后，系统中积累起大量的这种结构，占据着可观的物理页面，是时候将它们适度回收了，这就是 `shrink_dcache_memory` 和 `shrink_icache_memory` 函数的作用

+ 内核中的 slab 分配器担负着为小对象分配内存的工作，slab 一方面从内核“批发”成块的内存页面，再“零售”给内核中的小对象。slab 也倾向于向内核多要内存以做为本身的缓冲，却没有“及时归还”的美德，是时候回收一下了，`kmem_cache_reap` 函数就是用于此目的

+ 最后的办法，只能是将若干活跃的页面变为不活跃的页面，甚至 swap out 一些页面。这个工作由 `refill_inactive` 完成。其从优先级从低到高开始扫描 `LRU` 链表当中的页面，优先级越高，扫描到的页面越多。每次扫描，都会将一些 `ACTIVE` 页面的映射断开，并转为 `INACTIVE`，甚至一些页面会被交换出去。

### `refill_inactive`

该函数外层的循环以 `priority` 作为关键字，从 `6` 开始逐渐递减，即优先级逐渐变高。内层是两个循环

+ 一个是循环调用 `refill_inactive_scan`，扫描 `ACTIVE` 链，试图从中找到可以转为 `INACTIVE` 的页面。挑选的标准是页面的寿命（由 kswapd 决定）以及 LRU 链表本身的双重计数标准。扫描页面的多少，是由 `priority` 决定的，只有到 `priority` 增加到 `0` 时，才会扫描每一个页面。

+ 另一个是循环调用 `swap_out`，选择一个进程（找常驻内存 rss 最多的进程），扫描其页面映射表，找到中间可以被转成 `INACTIVE` 的页面。

`refill_inactive_scan` 和 `swap_out` 每次尝试换出一个页面，且当换出的页面达到要求时会 break 退出循环。反之，如果换出页面没法达到要求，重新开始最外层循环之前，会主动检查 `current->need_resched` 并请求一次新的调度（否则可能会一直占用 CPU）

### 参考资料

https://blog.csdn.net/zqz_zqz/article/details/80333607

https://my.oschina.net/u/3857782/blog/1854548

---

### 异步/同步内存回收

其实在之前已经提到了这两种回收方式。

大部分时间里，系统内存分配的频率其实是不高的。因此 kswapd 定时尽快地回收内存以保持系统在一种平衡的状态，让需要内存的进程可以很快地拿到需要的内存。kswapd 的工作不会让别的进程陷入阻塞当中，这是所谓的异步。

而当内存请求量十分巨大时，kswapd 无法胜任所有的内存释放工作。这时，内存的分配进程将会帮助他们释放内存。这种时候他们会直接调用 `try_to_free_pages()` （该函数后继续调用 `do_try_to_free_pages()`），这样的调用会使调度程序被阻塞，从而专注于释放需要的内存，待内存释放完毕再继续内存分配的工作，这是所谓的同步。

### 参考资料

https://linux-mm.org/PageOutKswapd#Asynchronous_and_Synchronous_Pageout

---

### 内存颠簸 （thrashing）

在 swapping 的过程中，最糟糕的情况就是，刚 swap out 的内存马上又要被 swap in，刚 swap in 的内存又要被 swap out，这种频繁的页面调度被称为抖动。另外一种说法是，如果一个进程在 swapping 上花的时间多余进程执行的时间（即 swapping 使用了主要的 CPU 时间），则我们认为它处于颠簸状态。

主要几个可以观察的指标是：

+ page in 和 page out 同时飙升到一个非常高的水平

+ 物理内存的使用比例非常高（ > 90% ）

内存颠簸发生后，一般只能通过物理重启来解决问题。因此我们需要以预防为主，最好是实时调整来保持节点的 swapping 处于一个健康的状态。Pepperdata 用机器学习的模型提前探测到颠簸的迹象，并在发现颠簸的迹象后，马上停止创建新的 container，甚至停止一些 container。

### 参考资料

https://stackoverflow.com/questions/19031902/what-is-thrashing-why-does-it-occur

### 什么是 Page Cache

http://roux.top/2017/10/28/page%20cache%E5%92%8Caddress_space/

### JVM, 应用程序内存 与 Page Cache

https://www.debugger.wiki/article/html/1562946445000314

### Page Cache Metrics

http://www.brendangregg.com/blog/2014-12-31/linux-page-cache-hit-ratio.html
