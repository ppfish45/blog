这篇参考的是 `release-1.6.0` 版本源码。

![img](https://ask.qcloudimg.com/http-save/6834658/obxfc4h01u.png)

这里有一张很不错的图介绍了 kubelet 启动的流程。

### kubelet.go

+ `main()`

    该文件包含 `main` 函数，为整个 kubelet 的入口。

    ```
    func main() {
        rand.Seed(time.Now().UnixNano())

        command := app.NewKubeletCommand()
        logs.InitLogs()
        defer logs.FlushLogs()

        if err := command.Execute(); err != nil {
            os.Exit(1)
        }
    }
    ```

    `app.NewKubeletCommand()` 的实现在 `app\server.go`，其基于了 Golang 的 `cobra` 包，创建了一个 `*cobra.Command` 对象，附带有启动 kubelet 的默认参数。

    `cobra` 包可以参考这篇：https://www.cnblogs.com/sparkdev/p/10856077.html


### server.go

+ `NewKubeletCommand()`

    该文件包含 `NewKubeletCommand()`，是 kubelet 启动的关键函数。主要逻辑为：

    + 解析命令行参数；

    + 为 kubelet 初始化 feature gates 参数；
 
    + 加载 kubelet 配置文件；
 
    + 校验配置文件中的参数；
 
    + 检查 kubelet 是否启用动态配置功能；
 
    + 初始化 `kubeletDeps`，`kubeletDeps`s 包含 kubelet 运行所必须的配置，是为了实现 dependency injection，其目的是为了把 kubelet 依赖的组件对象作为参数传进来，这样可以控制 kubelet 的行为；
    
    + 调用 `Run()` 方法

    函数开头时定义了 `kubeletConfig` 和 `kubeletFlags`。

    ```
    kubeletFlags := options.NewKubeletFlags()
    kubeletConfig, err := options.NewKubeletConfiguration()
    ```

    为了保证 flag 的优先级处理正确，它摒弃了 `cobra` 的 flag 解析模块。

    ```
    ...

    // The Kubelet has special flag parsing requirements to enforce flag precedence rules,
    // so we do all our parsing manually in Run, below.
    // DisableFlagParsing=true provides the full set of flags passed to the kubelet in the
    // `args` arg to Run, without Cobra's interference.

    DisableFlagParsing: true,

    ...
    ```

    我们可以主要关注 `cmd` 这个 `*cobra.Command` 中的 `Run` 项。可以省略中间关于命令行处理和 `kubeletConfig` 以及 `kubeletFlags` 读入的细节，一切处理完毕后，我们可以看到

    ```
    // construct a KubeletServer from kubeletFlags and kubeletConfig

    kubeletServer := &options.KubeletServer{
        KubeletFlags:         *kubeletFlags,
        KubeletConfiguration: *kubeletConfig,
    }

    // use kubeletServer to construct the default KubeletDeps

    kubeletDeps, err := UnsecuredDependencies(kubeletServer)
    if err != nil {
        klog.Fatal(err)
    }
    ```

    这里引入了 `KubeletServer`，在 `options.go` 中会提到。而 `KubeletDeps` 则保存了 kubelet 各个重要组件的对象，这个从 `UnsecuredDependencies()` 这个函数中可见一斑。

    ```
    return &kubelet.Dependencies{
		Auth:                nil, // default does not enforce auth[nz]
		CAdvisorInterface:   nil, // cadvisor.New launches background processes (bg http.ListenAndServe, and some bg cleaners), not set here
		Cloud:               nil, // cloud provider might start background processes
		ContainerManager:    nil,
		DockerClientConfig:  dockerClientConfig,
		KubeClient:          nil,
		HeartbeatClient:     nil,
		EventClient:         nil,
		HostUtil:            hu,
		Mounter:             mounter,
		Subpather:           subpather,
		OOMAdjuster:         oom.NewOOMAdjuster(),
		OSInterface:         kubecontainer.RealOS{},
		VolumePlugins:       ProbeVolumePlugins(),
		DynamicPluginProber: GetDynamicPluginProber(s.VolumePluginDir, pluginRunner),
		TLSOptions:          tlsOptions}, nil
    ```

    其中一些组件的功能：

    + CAdvisorInterface：提供 cAdvisor 接口功能的组件，用来获取监控信息
    + DockerClientConfig：docker 客户端，用来和 docker 交互
    + KubeClient：apiserver 客户端，用来和 api server 通信
    + Mounter：执行 mount 相关操作
    + VolumePlugins：volume 插件，执行 volume 设置工作

    完成所有的配置之后，运行 kubelet。

    ```
    // run the kubelet

    klog.V(5).Infof("KubeletConfiguration: %#v", kubeletServer.KubeletConfiguration)
    if err := Run(kubeletServer, kubeletDeps, stopCh); err != nil {
        klog.Fatal(err)
    }
    ```

    我们顺藤摸瓜，可以继续分析 `Run` 这个函数。

+ `Run()`

    这是**真正**运行 kubelet 的函数，看到这里我们可以发现之前的函数主要是用来构建 `KubeletServer` 和 `KubeletDeps`。函数不长，让我们来分析一下

    ```
    // Run runs the specified KubeletServer with the given Dependencies. This should never exit.
    // The kubeDeps argument may be nil - if so, it is initialized from the settings on KubeletServer.
    // Otherwise, the caller is assumed to have set up the Dependencies object and a default one will
    // not be generated.

    func Run(s *options.KubeletServer, kubeDeps *kubelet.Dependencies, stopCh <-chan struct{}) error {
        // To help debugging, immediately log version
        klog.Infof("Version: %+v", version.Get())
        if err := initForOS(s.KubeletFlags.WindowsService); err != nil {
            return fmt.Errorf("failed OS init: %v", err)
        }
        if err := run(s, kubeDeps, stopCh); err != nil {
            return fmt.Errorf("failed to run Kubelet: %v", err)
        }
        return nil
    }
    ```

    其中 `initForOS` 是检查操作系统，且如果系统是 Windows 的话，需要进行一些特殊的处理。经过这一步骤终于到了真正 (*2) 的 `run()` 函数。

+ `run()`

    这一步的主要逻辑是：

    + 为 kubelet 设置默认的 FeatureGates，kubelet 所有的 FeatureGates 可以通过命令参数查看，k8s 中处于 Alpha 状态的 FeatureGates 在组件启动时默认关闭，处于 Beta 和 GA 状态的默认开启；

        ```
        // Set global feature gates based on the value on the initial KubeletServer
        err = utilfeature.DefaultMutableFeatureGate.SetFromMap(s.KubeletConfiguration.FeatureGates)
        if err != nil {
            return err
        }
        ```

    + 校验 kubelet 的参数；

        ```
        // validate the initial KubeletServer (we set feature gates first, because this validation depends on feature gates)
        if err := options.ValidateKubeletServer(s); err != nil {
            return err
        }
        ```

    + 尝试获取 kubelet 的 lock file，需要在 kubelet 启动时指定 `--exit-on-lock-contention` 和 `--lock-file`，该功能处于 Alpha 版本默认为关闭状态；

        ```
        // Obtain Kubelet Lock File
        if s.ExitOnLockContention && s.LockFilePath == "" {
            return errors.New("cannot exit on lock file contention: no lock file specified")
        }
        done := make(chan struct{})
        if s.LockFilePath != "" {
            klog.Infof("acquiring file lock on %q", s.LockFilePath)
            if err := flock.Acquire(s.LockFilePath); err != nil {
                return fmt.Errorf("unable to acquire file lock on %q: %v", s.LockFilePath, err)
            }
            if s.ExitOnLockContention {
                klog.Infof("watching for inotify events for: %v", s.LockFilePath)
                if err := watchForLockfileContention(s.LockFilePath, done); err != nil {
                    return err
                }
            }
        }
        ```

    + 将当前的配置文件注册到 http server `/configz` URL 中；

        ```
        // Register current configuration with /configz endpoint
        err = initConfigz(&s.KubeletConfiguration)
        if err != nil {
            klog.Errorf("unable to register KubeletConfiguration with configz, error: %v", err)
        }
        ```

    + 检查 kubelet 启动模式是否为 standalone 模式，此模式下不会和 apiserver 交互，主要用于 kubelet 的调试。

        ```
        // About to get clients and such, detect standaloneMode
        standaloneMode := true
        if len(s.KubeConfig) > 0 {
            standaloneMode = false
        }
        ```

    + 初始化 kubeDeps，kubeDeps 中包含 kubelet 的一些依赖，主要有 `KubeClient`、`EventClient`、`HeartbeatClient`、`Auth`、`cadvisor`、`ContainerManager`；

        ```
        if kubeDeps == nil {
            kubeDeps, err = UnsecuredDependencies(s)
            if err != nil {
                return err
            }
        }

        if kubeDeps.Cloud == nil {
            if !cloudprovider.IsExternal(s.CloudProvider) {
                cloud, err := cloudprovider.InitCloudProvider(s.CloudProvider, s.CloudConfigFile)
                if err != nil {
                    return err
                }
                if cloud == nil {
                    klog.V(2).Infof("No cloud provider specified: %q from the config file: %q\n", s.CloudProvider, s.CloudConfigFile)
                } else {
                    klog.V(2).Infof("Successfully initialized cloud provider: %q from the config file: %q\n", s.CloudProvider, s.CloudConfigFile)
                }
                kubeDeps.Cloud = cloud
            }
        }

        hostName, err := nodeutil.GetHostname(s.HostnameOverride)
        if err != nil {
            return err
        }
        nodeName, err := getNodeName(kubeDeps.Cloud, hostName)
        if err != nil {
            return err
        }
        ```

    + 如果是 standalone 模式，则将所有的 Client 设置成 `nil`，并初始化几个 KubeDeps

        ```
        switch {
        case standaloneMode:
            kubeDeps.KubeClient = nil
            kubeDeps.EventClient = nil
            kubeDeps.HeartbeatClient = nil
            klog.Warningf("standalone mode, no API client")

        case kubeDeps.KubeClient == nil, kubeDeps.EventClient == nil, kubeDeps.HeartbeatClient == nil:
            clientConfig, closeAllConns, err := buildKubeletClientConfig(s, nodeName)
            if err != nil {
                return err
            }
            if closeAllConns == nil {
                return errors.New("closeAllConns must be a valid function other than nil")
            }
            kubeDeps.OnHeartbeatFailure = closeAllConns

            kubeDeps.KubeClient, err = clientset.NewForConfig(clientConfig)
            if err != nil {
                return fmt.Errorf("failed to initialize kubelet client: %v", err)
            }

            // make a separate client for events
            eventClientConfig := *clientConfig
            eventClientConfig.QPS = float32(s.EventRecordQPS)
            eventClientConfig.Burst = int(s.EventBurst)
            kubeDeps.EventClient, err = v1core.NewForConfig(&eventClientConfig)
            if err != nil {
                return fmt.Errorf("failed to initialize kubelet event client: %v", err)
            }

            // make a separate client for heartbeat with throttling disabled and a timeout attached
            heartbeatClientConfig := *clientConfig
            heartbeatClientConfig.Timeout = s.KubeletConfiguration.NodeStatusUpdateFrequency.Duration
            // if the NodeLease feature is enabled, the timeout is the minimum of the lease duration and status update frequency
            if utilfeature.DefaultFeatureGate.Enabled(features.NodeLease) {
                leaseTimeout := time.Duration(s.KubeletConfiguration.NodeLeaseDurationSeconds) * time.Second
                if heartbeatClientConfig.Timeout > leaseTimeout {
                    heartbeatClientConfig.Timeout = leaseTimeout
                }
            }
            heartbeatClientConfig.QPS = float32(-1)
            kubeDeps.HeartbeatClient, err = clientset.NewForConfig(&heartbeatClientConfig)
            if err != nil {
                return fmt.Errorf("failed to initialize kubelet heartbeat client: %v", err)
            }
        }
        ```

    + 初始化 Auth

        ```
        if kubeDeps.Auth == nil {
            auth, err := BuildAuth(nodeName, kubeDeps.KubeClient, s.KubeletConfiguration)
            if err != nil {
                return err
            }
            kubeDeps.Auth = auth
        }
        ```

    + 设置 `cgroupRoots`

        ```
        var cgroupRoots []string

        cgroupRoots = append(cgroupRoots, cm.NodeAllocatableRoot(s.CgroupRoot, s.CgroupDriver))
        kubeletCgroup, err := cm.GetKubeletContainer(s.KubeletCgroups)
        if err != nil {
            klog.Warningf("failed to get the kubelet's cgroup: %v.  Kubelet system container metrics may be missing.", err)
        } else if kubeletCgroup != "" {
            cgroupRoots = append(cgroupRoots, kubeletCgroup)
        }

        runtimeCgroup, err := cm.GetRuntimeContainer(s.ContainerRuntime, s.RuntimeCgroups)
        if err != nil {
            klog.Warningf("failed to get the container runtime's cgroup: %v. Runtime system container metrics may be missing.", err)
        } else if runtimeCgroup != "" {
            // RuntimeCgroups is optional, so ignore if it isn't specified
            cgroupRoots = append(cgroupRoots, runtimeCgroup)
        }

        if s.SystemCgroups != "" {
            // SystemCgroups is optional, so ignore if it isn't specified
            cgroupRoots = append(cgroupRoots, s.SystemCgroups)
        }
        ```

    + 初始化 cAdvisor

        ```
        if kubeDeps.CAdvisorInterface == nil {
            imageFsInfoProvider := cadvisor.NewImageFsInfoProvider(s.ContainerRuntime, s.RemoteRuntimeEndpoint)
            kubeDeps.CAdvisorInterface, err = cadvisor.New(imageFsInfoProvider, s.RootDirectory, cgroupRoots, cadvisor.UsingLegacyCadvisorStats(s.ContainerRuntime, s.RemoteRuntimeEndpoint))
            if err != nil {
                return err
            }
        }
        ```

    + 初始化 ContainerManager

        ```
        if kubeDeps.ContainerManager == nil {
            if s.CgroupsPerQOS && s.CgroupRoot == "" {
                klog.Info("--cgroups-per-qos enabled, but --cgroup-root was not specified.  defaulting to /")
                s.CgroupRoot = "/"
            }

            ...

            if err != nil {
                return err
            }
        }
        ```

    + 检查是否为 root 权限启动

        ```
        if err := checkPermissions(); err != nil {
            klog.Error(err)
        }
        ```

    + 为 kubelet 进程设置 oom 分数，默认为 -999，分数范围为 `[-1000, 1000]`，越小越不容易被 kill 掉

        ```
        oomAdjuster := kubeDeps.OOMAdjuster
        if err := oomAdjuster.ApplyOOMScoreAdj(0, int(s.OOMScoreAdj)); err != nil {
            klog.Warning(err)
        }
        ```

    + 调用 `RunKubelet()` 方法

        ```
        if err := RunKubelet(s, kubeDeps, s.RunOnce); err != nil {
            return err
        }
        ```

    + 检查 kubelet 是否启动了动态配置功能；

        ```
        // If the kubelet config controller is available, and dynamic config is enabled, start the config and status sync loops
        if utilfeature.DefaultFeatureGate.Enabled(features.DynamicKubeletConfig) && len(s.DynamicConfigDir.Value()) > 0 &&
            kubeDeps.KubeletConfigController != nil && !standaloneMode && !s.RunOnce {
            if err := kubeDeps.KubeletConfigController.StartSync(kubeDeps.KubeClient, kubeDeps.EventClient, string(nodeName)); err != nil {
                return err
            }
        }
        ```

    + 启动 Healthz http server；

        ```
        if s.HealthzPort > 0 {
            mux := http.NewServeMux()
            healthz.InstallHandler(mux)
            go wait.Until(func() {
                err := http.ListenAndServe(net.JoinHostPort(s.HealthzBindAddress, strconv.Itoa(int(s.HealthzPort))), mux)
                if err != nil {
                    klog.Errorf("Starting healthz server failed: %v", err)
                }
            }, 5*time.Second, wait.NeverStop)
        }

        if s.RunOnce {
            return nil
        }
        ```

    + 如果使用 systemd 启动，通知 systemd kubelet 已经启动；

        ```
        // If systemd is used, notify it that we have started
	    go daemon.SdNotify(false, "READY=1")
        ```

    + 接收退出信号

        ```
        select {
            case <-done:
                break
            case <-stopCh:
                break
        }

        return nil
        ```
+ 


### options.go

+ `KubeletServer`

    ```
    // KubeletServer encapsulates all of the parameters necessary for starting up
    // a kubelet. These can either be set via command line or directly.
    type KubeletServer struct {
        KubeletFlags
        kubeletconfig.KubeletConfiguration
    }
    ```

    其中 `KubeletFlags` 是各种 kubelet 运行的基本参数，例如 `NodeIP`，`ProviderID`，`RootDirectory` 等等。而 `kubeletconfig.KubeletConfiguration` 是命令行中所有可以配置的参数。因此我们可以理解 `KubeletServer` 即为一个保存了 kubelet 运行所有配置信息的结构体。

### 参考资料

https://cloud.tencent.com/developer/article/1564951