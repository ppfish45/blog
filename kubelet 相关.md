### 什么是 kubelet

kubelet 是在每个 Node 节点上运行的主要 “节点代理”。它向 apiserver 注册节点时可以使用<u>主机名（hostname）</u>；可以提供用于覆盖主机名的参数；还可以执行特定于某云服务商的逻辑。

kubelet 是基于 PodSpec 工作，每个 PosSpec 是描述 Pod 的 YAML 或 JSON。kubelet 接受通过各种机制（<u>主要是通过 apiserver</u>）提供的一组 PodSpec，并确保这些 PodSpec 中描述的容器处于运行状态且运行状况良好。

（kubelet 的主要功能就是定时从<u>某个地方</u>获取节点上 pod/container 的<u>期望状态</u>（运行什么容器、运行的副本数量、网络或者存储如何配置等等），并调用对应的容器平台接口达到这个状态。）

### Container 和 Pod

在 kubernetes 的设计中，最基本的管理单位是 pod，而不是 container。pod 是 kubernetes  在容器上的一层封装，由一组运行在<u>同一主机的一个或者多个容器组成</u>。如果把容器比喻成传统机器上的一个进程（它可以执行任务，对外提供某种功能），那么  pod 可以类比为<u>传统的主机</u>：它包含了多个容器，为它们提供共享的一些资源。

之所以费功夫提供这一层封装，主要是因为容器推荐的用法是里面只运行一个进程，*而一般情况下某个应用都由多个组件构成的*。pod 中所有的容器最大的特性也是最大的好处就是共享了很多资源，比如网络空间。pod 下所有容器共享网络和端口空间，也就是它们之间可以通过 `localhost` 访问和通信，对外的通信方式也是一样的，省去了很多容器通信的麻烦。

除了网络之外，定义在 pod 里的 volume 也可以 mount 到多个容器里，以实现共享的目的。

最后，定义在 pod 的资源限制（比如 CPU 和 Memory） 也是所有容器共享的。

### 当一个 Pod 创建时，kubelet 它经历了什么

通过 kubectl 命令创建一个 Pod 后，经过 kubectl -> apiserver -> controller -> scheduler，所有的变化还只停留在 etcd 中。而 kubelet 会通过轮询的方式向 apiserver 获取属于它这个 node 的 pod 清单。在这之后才真正开始创建那些需要被新增的 pod。

![img](https://qiankunli.github.io/public/upload/kubernetes/kubelet_overview.png)

### kubelet 如何检查容器正常运行

1. 在容器中执行某个 shell 命令，根据 exit code 判断是否正常
2. 向某个 url 发送 GET 请求，根据 response code 判断

### kubelet 如何监控 Node 的资源使用情况

在每个 Node 中会有一个 Pod 运行 cAdvisor 用来监控整个 Node 的资源使用情况。默认情况下，你可以在 `http://<host_ip>:4194` 地址看到 cAdvisor 的管理界面。


**参考资料**

https://cizixs.com/2016/10/25/kubernetes-intro-kubelet/
https://qiankunli.github.io/2018/12/31/kubernetes_source_kubelet.html

