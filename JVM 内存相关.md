### JVM 内存机制

Java 内存分为五个部分

+ 堆（Heap）：**被所有线程共享**，储存对象实例，例如由 `new` 创建的对象。

+ 方法区（Method Area）：**被所有线程共享**，用来储存已被虚拟机加载的类、常量、静态变量、编译后的代码等等。

+ 栈（Stack）：**每个线程的栈是独立的**，储存一些对象的引用、局部变量以及计算过程的中间数据，方法退出后变量也会被销毁。它的储存比堆快得多，只比 CPU 的寄存器慢。**每一个方法被调用直到完成的过程，对应着在栈中入栈和出栈的过程**。

+ 程序计数器（Program Counter Register）：**每个线程都是独立的**，一块较小的内存空间，用来保存 **当前线程** 所执行的字节码行号显示器

+ 本地方法栈（Native Method Stack）：**每个线程独立**，为 JVM 使用的 Native 方法服务。

在上面提到的五个部分中，**堆内存** 是最大的一块，它被分为 **年轻代 (Young Generation)** 和 **老年代 (Old Generation)**。而年轻代又分为三个部分，**Eden 空间**，**From Survivor 空间** 和 **To Survivor** 空间。

在年轻代中，**Eden区** 用来存放 `new` 或者 `newInstance` 等方法创建的方法。两个 **Survivor区** 为 **S0** 和 **S1**，理论上它们是一样大的，它们的工作原理是：

在不断创建新对象的过程中，Eden区会满，这个时候会触发 `Minor GC`，它会把 Eden区 中活着的对象（一个对象是活的说明仍有指针指向它）移到 S0 或 S1 区中的其中一个。如果选择了 S0，那么它会将活着的对象不断往 S0 区拷贝，S0 满了复制不下的就会移到 Old 区中，最终将 Eden 区清空。

当第二次 Eden 区满了的时候，`Minor GC` 会将 Eden 区 + S0 区 活着的对象迁移到 S1 区，并将放不下的扔到老年期，最后将 Eden 区和 S0 区清空。

之后若干次也是这样轮流交换来进行，每次 GC 的时候都会保证 S0 和 S1 区中有一个是空的。如果一个对象在 S0 和 S1 间来回转移 `-XX:MaxTenuringThreshold=15` 次，会被移动到老年代。

`Full GC (Major GC)` 发生的条件有包括例如：

+ 调用 `System.gc()`

+ 从新生代转入大对象的时候，老年代空间不足

+ 分配很大的对象，老年区无法找到这么大的连续空间

发生 `Full GC` 的时候，JVM 会暂停所有正在执行的线程 (Stop the World)，在这个时间段内，所有除了回收垃圾的线程外，其他有关JAVA的程序，代码都会静止，反映到系统上，就会出现系统响应大幅度变慢，卡机等状态。

### 参考资料

http://tokyle.com/2018/11/02/JVM%E5%86%85%E5%AD%98%E6%80%A7%E8%83%BD%E9%97%AE%E9%A2%98%E5%AE%9A%E4%BD%8D/

https://juejin.im/post/5d6dd2915188252d43758ddb

---

### JVM 的内存会被正常 swap 吗

在 Linux 中，JVM 对其而言只是一个正常的进程，因此物理内存不够时，Linux 也会正常把其占用的内存 swap 掉，而不是出发 JVM 的 GC 机制。

### JVM 新建进程时申请的内存，会直接被标记为 unavailable 吗

首先我们尝试用 `java -Xmx1000m -Xms1000m Test` 运行一个不申请额外内存的程序，通过 `top` 可以看到：

```
 PID USER      PR  NI    VIRT    RES    SHR S  %CPU %MEM     TIME+ COMMAND 
6542 ppfish    20   0 3840884  24844  12088 S   0.0  0.1   0:00.11 java
```

换算一下 `VIRT = 3840884 kb = 3751 mb`，`RES = 24844 kb = 24 mb`

再运行一下 `java -Xmx256m -Xms256m Test`，可以看到

```
 PID USER      PR  NI    VIRT    RES    SHR S  %CPU %MEM     TIME+ COMMAND 
6584 ppfish    20   0 3051192  22400  12088 S   0.0  0.1   0:00.11 java
```

换算一下 `VIRT = 3051192 kb = 2979 mb`，`RES = 22400 kb = 22mb`

可以看到我们申请的堆空间的大小，主要是扩大了 `VIRT` 虚拟空间的大小，而 `RES` 即实际占用的物理内存没有明显变化。这也是为什么在运行这个程序以后，`free -m` 给出的 `available` 基本没有减小的原因。

随后我们在程序中申请一个大小为 `100 000 000` 的 `int` 数组，会发现，`free -h` 在运行前：

```
              total        used        free      shared  buff/cache   available
Mem:            15G        6.6G        9.0G         17M        223M        9.1G
Swap:           27G         87M         27G
```

运行后：

```
              total        used        free      shared  buff/cache   available
Mem:            15G        7.0G        8.7G         17M        223M        8.8G
Swap:           27G         87M         27G
```

在 `top` 中，有

```
 PID USER      PR  NI    VIRT    RES    SHR S  %CPU %MEM     TIME+ COMMAND 
 7392 ppfish    20   0 8086528 428856  12104 S   0.0  2.6   0:00.47 java
```

（这里为了装下这个数组我们开大了 `Xmx` 和 `Xms`）换算一下，有 `RES = 428856 kb = 418 mb`，基本是多了一个 `100 000 000` 数组的大小，也就是 `400 mb` 左右。因此我们可以得出结论，只有当堆中被真正放入对象后，才会占用物理内存，从而挤兑掉 `available`。

### 参考资料

https://cloud.tencent.com/developer/article/1491578

---

### JVM 的进程内存与 Page Cache 是独立的吗

Linux 的内存分为两种类型：Page Cache 和 应用程序内存。应用程序的内存会被 swap 出去，而 Page Cache 是由 Linux 后台的异步 Flush 策略刷盘。当内存满时，系统需要将一部分内存数据写入到磁盘当中去，通过 `swappiness`，可以决定优先 swap 还是清理 Page Cache。

“Linux有个很怪的癖好，当内存不足时，有很大机率不是把用作 IO 缓存的 Page Cache 收回，而是把冷的应用内存 Page Out 到磁盘上。当这段内存重新要被访问时，再把它重新 Page In 回内存（所谓的主缺页错误），这个过程进程是停顿的。”

### JVM 的 GC 会回收哪些内存，包括 Page Cache 吗

从上面的分析来看，应该是不会的。但是我们还是进行一系列实验，并探究如何对内存进行一系列监控。

### 参考资料

https://www.debugger.wiki/article/html/1562946445000314

---

### Java 运行内存设置参数

+ `-Xmx` Java Heap 最大值，默认为物理内存的 1/4

+ `-Xms` Java Heap 最小值

+ `-Xmn` Java Heap Young区大小

---

### 评价 JVM 内存的指标

+ 活跃数据的大小

根据 JVM 的垃圾回收机制，最活跃的对象会在若干次 `Minor GC` 之后被移动到老年代，因此我们可以