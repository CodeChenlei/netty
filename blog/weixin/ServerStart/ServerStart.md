## background
netty 是一个异步事件驱动的网络通信层框架，其官方文档的解释为

> Netty is a NIO client server framework which enables quick and easy development of network applications such as protocol servers and clients. It greatly simplifies and streamlines network programming such as TCP and UDP socket server.

我们在新美大消息推送系统sailfish(日均推送消息量50亿)，新美大移动端代理优化系统shark(日均吞吐量30亿)中，均选择了netty作为底层网络通信框架。

既然两大如此重要的系统底层都使用到了netty，所以必然要对netty的机制，甚至源码了然入心，后面，我会把我从netty源码里所学到的毫无保留地介绍给你。


## why netty
netty底层基于jdk的NIO，那么我们为什么不直接基于jdk的nio或者其他nio框架呢，下面是我总结出来的原因

1.使用jdk自带的nio需要了解太多的概念，编程复杂
2.netty底层IO模型随意切换，而这一切只需要做微小的改动
3.netty自带的拆包解包的机制让你从nio的细节中脱离出来，让你只需要关心业务逻辑
4.netty解决了jdk的很多包括空轮训在内的bug
5.netty底层对线程，selector做了很多细小的优化，精心设计的reactor线程做到非常高效的并发处理
6.自带各种协议栈让你处理任何一种通用协议都几乎不用亲自动手
7.netty社区活跃，遇到问题随时邮件列表或者issue
8.netty已被各大rpc框架，消息中间件，分布式通信中间件等经过线上的广泛验证，健壮性无比强大

## dive into netty
了解了这么多，今天我们就从一个例子出来，开始我们的netty源码之旅，本篇主要讲述的是netty是如何绑定端口，启动服务的，启动服务的过程中，你将会了解到netty各大核心组件，我先不会细讲这些组件，而是会告诉你各大组件是怎么串起来组成netty的核心
### example
本文基于netty4.1.6.Final，下面是一个非常简单的服务端启动代码
```java
public final class SimpleServer {

    public static void main(String[] args) throws Exception {
        EventLoopGroup bossGroup = new NioEventLoopGroup(1);
        EventLoopGroup workerGroup = new NioEventLoopGroup();

        try {
            ServerBootstrap b = new ServerBootstrap();
            b.group(bossGroup, workerGroup)
                    .channel(NioServerSocketChannel.class)
                    .handler(new SimpleServerHandler())
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        public void initChannel(SocketChannel ch) throws Exception {
                        }
                    });

            ChannelFuture f = b.bind(8888).sync();

            f.channel().closeFuture().sync();
        } finally {
            bossGroup.shutdownGracefully();
            workerGroup.shutdownGracefully();
        }
    }

    private static class SimpleServerHandler extends ChannelInboundHandlerAdapter {
        @Override
        public void channelActive(ChannelHandlerContext ctx) throws Exception {
            System.out.println("channelActive");
        }

        @Override
        public void channelRegistered(ChannelHandlerContext ctx) throws Exception {
            System.out.println("channelRegistered");
        }

        @Override
        public void handlerAdded(ChannelHandlerContext ctx) throws Exception {
            System.out.println("handlerAdded");
        }
    }
}
```
`EventLoopGroup` 已经在我的其他文章中详细剖析过，说白了，就是一个死循环，不停地干活

`ServerBootstrap` 是服务端的一个启动辅助类，通过给他设置一系列参数来绑定端口启动服务

`group(bossGroup, workerGroup)` 我们需要两种类型的人干活，一个是老板，一个是工人，老板负责从外面接活，接到的活分配给工人干，放到这里，`bossGroup`的作用就是不断地accept到新的连接，将新的连接丢给`workerGroup`来处理

`.channel(NioServerSocketChannel.class)` 表示服务端启动的是nio相关的channel，channel在netty里面是一大核心概念，可以理解为一条channel就是一个连接或者一个服务端bind，后面会细说

`.handler(new SimpleServerHandler()` 表示服务器启动过程中，需要经过哪些流程，这里`SimpleServerHandler`最终的顶层接口为`ChannelHander`，又是netty的一大核心概念，表示数据流经过的处理器，可以理解为流水线上的每一道关卡

`childHandler(new ChannelInitializer<SocketChannel>)...`表示一条新的连接进来之后，该怎么处理，也就是上面所说的，老板如何给工人配活

`ChannelFuture f = b.bind(8888).sync();` 这里就是真正的启动过程了，绑定8888端口，等待服务器启动完毕，才会进入下行代码

`f.channel().closeFuture().sync();` 等待服务端关闭socket

`bossGroup.shutdownGracefully(); workerGroup.shutdownGracefully();` 关闭两组死循环


上述代码可以很轻松地再本地跑起来，最终控制台的输出为：
```java
handlerAdded
channelRegistered
channelActive
```
关于为什么会顺序输出这些，深入分析之后其实很easy
### 深入细节

`ServerBootstrap` 一系列的参数配置其实没啥好讲的，无非就是使用[method chaining](https://en.wikipedia.org/wiki/Method_chaining#Java)的方式将启动服务器需要的参数保存到filed。我们的重点落入到下面这段代码

```java
b.bind(8888).sync();
```
这里说一句：我们刚开始看源码，对细节没那么清楚的情况下可以借助IDE的debug功能，step by step，one step one test或者二分test的方式，来确定哪行代码是最终启动服务的入口，在这里，我们已经确定了是bind方法是入口，我们跟进去，分析

```java
public ChannelFuture bind(int inetPort) {
    return bind(new InetSocketAddress(inetPort));
} 
```
通过端口号创建一个 `InetSocketAddress`，然后继续bind

```java
public ChannelFuture bind(SocketAddress localAddress) {
    validate();
    if (localAddress == null) {
        throw new NullPointerException("localAddress");
    }
    return doBind(localAddress);
}
```
`validate()` 验证服务启动需要的必要参数，然后调用`doBind()`

```java
private ChannelFuture doBind(final SocketAddress localAddress) {
    //...
    final ChannelFuture regFuture = initAndRegister();
    //...
    final Channel channel = regFuture.channel();
    //...
    doBind0(regFuture, channel, localAddress, promise);
    //...
    return promise;
}
```
这里，我去掉了细枝末节，让我们专注于核心方法，其实就两大核心一个是 `initAndRegister()`，以及`doBind0()`

其实，从方法名上面我们已经可以略窥一二，init->初始化，register->注册，那么要注册到什么上面去了，联系到nio里面轮训器的注册，可能是把某个东西初始化好了之后注册到selector上面去，最后bind，像是在本地绑定端口号，带着这些猜测，我们深入下去

#### initAndRegister()
```java
final ChannelFuture initAndRegister() {
    Channel channel = null;
    // ...
    channel = channelFactory.newChannel();
    //...
    init(channel);
    //...
    ChannelFuture regFuture = config().group().register(channel);
    //...
    return regFuture;
}
```
我们还是专注于核心代码，抛开边角料，我们看到 `initAndRegister()` 做了几件事情
1.new一个channel
2.init这个channel
3.将这个channel register到某个对象

我们逐步分析这三件事情
##### 1.new一个channel
我们首先要搞懂channel的定义，netty官方对channel的描述如下
>  A nexus to a network socket or a component which is capable of I/O operations such as read, write, connect, and bind

这里的channel，由于是在服务启动的时候创建，我们可以和普通Socket编程中的ServerSocket对应上。

我们发现这条channel是通过一个 `channelFactory` new出来的，`channelFactory` 的接口很简单

```java
public interface ChannelFactory<T extends Channel> extends io.netty.bootstrap.ChannelFactory<T> {
    /**
     * Creates a new channel.
     */
    @Override
    T newChannel();
}
```
就一个方法，我们查看channelFactory被初始化的地方
> AbstractBootstrap.java
```java
public B channelFactory(ChannelFactory<? extends C> channelFactory) {
    if (channelFactory == null) {
        throw new NullPointerException("channelFactory");
    }
    if (this.channelFactory != null) {
        throw new IllegalStateException("channelFactory set already");
    }

    this.channelFactory = channelFactory;
    return (B) this;
}
```
在这里被赋值，我们层层回溯，查看该函数被调用的地方，发现最终是在这个函数中，ChannelFactory被new出
```java
public B channel(Class<? extends C> channelClass) {
    if (channelClass == null) {
        throw new NullPointerException("channelClass");
    }
    return channelFactory(new ReflectiveChannelFactory<C>(channelClass));
}
```
这里，外层函数调用`channel(channelClass)`方法的时候，将`channelClass`作为`ReflectiveChannelFactory`的构造函数创建出一个`ReflectiveChannelFactory`

而这个外层函数我们发现是在设置 `ServerBootstrap` 的channel，也就是下面这段代码的时候被调用

```java
.channel(NioServerSocketChannel.class);
```

然后回到本节最开始
```java
channelFactory.newChannel();
```
我们就可以推断出，最终是调用到 `ReflectiveChannelFactory.newChannel()` 方法，跟进

```java
public class ReflectiveChannelFactory<T extends Channel> implements ChannelFactory<T> {

    private final Class<? extends T> clazz;

    public ReflectiveChannelFactory(Class<? extends T> clazz) {
        if (clazz == null) {
            throw new NullPointerException("clazz");
        }
        this.clazz = clazz;
    }

    @Override
    public T newChannel() {
        try {
            return clazz.newInstance();
        } catch (Throwable t) {
            throw new ChannelException("Unable to create Channel from class " + clazz, t);
        }
    }
}
```
`clazz.newInstance();`，原来是通过反射的方式来创建一个对象，而这个class就是我们在`ServerBootstrap`中传入的`NioServerSocketChannel.class`

结果，绕了一圈，最终创建channel相当于调用默认构造函数new出一个 `NioServerSocketChannel`对象

这里提一下，读源码，设计到细节，有两种读的方式，一种是回溯，比如用到某个对象的时候可以逐层追溯，一定会找到该对象的最开始被创建的代码区块，还有一种方式就是自顶向下，逐层分析，一般用在分析某个具体的方法，庖丁解牛，最后拼接出完整的流程

接下来我们就可以将重心放到 `NioServerSocketChannel`的默认构造函数

```java
private static final SelectorProvider DEFAULT_SELECTOR_PROVIDER = SelectorProvider.provider();
public NioServerSocketChannel() {
    this(newSocket(DEFAULT_SELECTOR_PROVIDER));
}
```

```java

private static ServerSocketChannel newSocket(SelectorProvider provider) {
    //...
    return provider.openServerSocketChannel();
}
```
通过`SelectorProvider.openServerSocketChannel()`创建一条server端channel，然后进入到以下方法

```java
public NioServerSocketChannel(ServerSocketChannel channel) {
    super(null, channel, SelectionKey.OP_ACCEPT);
    config = new NioServerSocketChannelConfig(this, javaChannel().socket());
}
```
这里第一行代码就跑到父类里面去了，第二行，new出来一个 `NioServerSocketChannelConfig`，其顶层接口为 `ChannelConfig`，netty官方的描述如下

> A set of configuration properties of a {@link Channel}.

基本可以判定，`ChannelConfig` 也是netty里面的一大核心模块，初次看源码，看到这里，我们大可不必深挖这个对象，而是在用到的时候再回来深究，只要记住，这个对象在创建`NioServerSocketChannel`对象的时候被创建即可

我们继续追踪到 `NioServerSocketChannel` 的父类
> AbstractNioMessageChannel.java

```java
protected AbstractNioMessageChannel(Channel parent, SelectableChannel ch, int readInterestOp) {
    super(parent, ch, readInterestOp);
}
```
继续往上追
> AbstractNioChannel.java
```java
protected AbstractNioChannel(Channel parent, SelectableChannel ch, int readInterestOp) {
    super(parent);
    this.ch = ch;
    this.readInterestOp = readInterestOp;
    //...
    ch.configureBlocking(false);
    //...
}
```
这里，简单地将前面 `provider.openServerSocketChannel();` 创建出来的 `ServerSocketChannel` 保存到filed，然后调用`ch.configureBlocking(false);`设置该channel为非阻塞模式，标准的jdk nio编程的玩法

这里的 `readInterestOp` 即前面层层传入的 `SelectionKey.OP_ACCEPT`，接下来重点分析 `super(parent);`(这里的parent其实是null，由前面写死传入)

> AbstractChannel.java

```java
protected AbstractChannel(Channel parent) {
    this.parent = parent;
    id = newId();
    unsafe = newUnsafe();
    pipeline = newChannelPipeline();
}
```
到了这里，又new出来三大组件，赋值到成员变量，分别为

```java
id = newId();
protected ChannelId newId() {
    return DefaultChannelId.newInstance();
}
```
id是netty中每条channel的唯一标识，这里不细展开，接着

```java
unsafe = newUnsafe();
protected abstract AbstractUnsafe newUnsafe();
```
查看Unsafe的定义
> Unsafe operations that should never be called from user-code. These methods  are only provided to implement the actual transport, and must be invoked from an I/O thread

成功捕捉netty的又一大组件，我们可以先不用管TA是干嘛的，只需要知道这里的 `newUnsafe`最终是调用到最底层类`NioServerSocketChannel`

最后
```java
pipeline = newChannelPipeline();

protected DefaultChannelPipeline newChannelPipeline() {
    return new DefaultChannelPipeline(this);
}

protected DefaultChannelPipeline(Channel channel) {
        this.channel = ObjectUtil.checkNotNull(channel, "channel");
        succeededFuture = new SucceededChannelFuture(channel, null);
        voidPromise =  new VoidChannelPromise(channel, true);

        tail = new TailContext(this);
        head = new HeadContext(this);

        head.next = tail;
        tail.prev = head;
}

```
初次看这段代码，可能并不知道 `DefaultChannelPipeline` 是干嘛用的，我们仍然使用上面的方式，查看顶层接口`ChannelPipeline`的定义

> A list of ChannelHandlers which handles or intercepts inbound events and outbound operations of a Channel
从该类的文档中可以看出，该接口基本上又是netty的一大核心模块

到了这里，我们总算把一个服务端channel创建完毕了，将这些细节串起来的时候，我们顺带提取出netty的几大基本组件，先总结如下

* Channel
* ChannelConfig
* ChannelId 
* Unsafe
* Pipeline

初次看代码的时候，我们的目标是跟到服务器启动的那一行代码，因为，我们先把以上这几个组建记下来，等代码跟完，我们就可以自顶向下，逐层分析，我会放到后面源码系列中去深入到每个组件

总结一下，用户调用方法 `Bootstrap.bind(port)` 第一步就是通过反射的方式new一个NioServerSocketChannel对象，并且在new的过程中创建了一系列的核心组件，仅此而已，并无他，真正的启动我们还需要继续跟


##### 2.init这个channel
到了这里，你最好跳到文章最开始的地方，第一步newChannel完毕，这里就对这个channel做init，init方法具体干啥，我们深入

```java
@Override
void init(Channel channel) throws Exception {
    final Map<ChannelOption<?>, Object> options = options0();
    synchronized (options) {
        channel.config().setOptions(options);
    }

    final Map<AttributeKey<?>, Object> attrs = attrs0();
    synchronized (attrs) {
        for (Entry<AttributeKey<?>, Object> e: attrs.entrySet()) {
            @SuppressWarnings("unchecked")
            AttributeKey<Object> key = (AttributeKey<Object>) e.getKey();
            channel.attr(key).set(e.getValue());
        }
    }

    ChannelPipeline p = channel.pipeline();

    final EventLoopGroup currentChildGroup = childGroup;
    final ChannelHandler currentChildHandler = childHandler;
    final Entry<ChannelOption<?>, Object>[] currentChildOptions;
    final Entry<AttributeKey<?>, Object>[] currentChildAttrs;
    synchronized (childOptions) {
        currentChildOptions = childOptions.entrySet().toArray(newOptionArray(childOptions.size()));
    }
    synchronized (childAttrs) {
        currentChildAttrs = childAttrs.entrySet().toArray(newAttrArray(childAttrs.size()));
    }

    p.addLast(new ChannelInitializer<Channel>() {
        @Override
        public void initChannel(Channel ch) throws Exception {
            final ChannelPipeline pipeline = ch.pipeline();
            ChannelHandler handler = config.handler();
            if (handler != null) {
                pipeline.addLast(handler);
            }

            ch.eventLoop().execute(new Runnable() {
                @Override
                public void run() {
                    pipeline.addLast(new ServerBootstrapAcceptor(
                            currentChildGroup, currentChildHandler, currentChildOptions, currentChildAttrs));
                }
            });
        }
    });
}

```
初次看到这个方法，可能会觉得，哇塞，老长了，这可这么看，还记得我们前面所说的吗，庖丁解牛，逐步拆解，最后归一，下面是我的拆解步骤

1.设置option和attr

```java
final Map<ChannelOption<?>, Object> options = options0();
    synchronized (options) {
        channel.config().setOptions(options);
    }

    final Map<AttributeKey<?>, Object> attrs = attrs0();
    synchronized (attrs) {
        for (Entry<AttributeKey<?>, Object> e: attrs.entrySet()) {
            @SuppressWarnings("unchecked")
            AttributeKey<Object> key = (AttributeKey<Object>) e.getKey();
            channel.attr(key).set(e.getValue());
        }
    }
```
通过这里我们可以看到，这里先调用`options0()`以及`attrs0()`，然后将得到的options和attrs注入到channelConfig或者channel中，关于option和attr是干嘛用的，其实你现在不用了解得那么深入，只需要查看最顶层接口`ChannelOption`以及查看一下channel的具体继承关系，就可以了解，我把这两个也放到后面的源码分析系列再讲


2.设置新接入channel的option和attr
```java
final EventLoopGroup currentChildGroup = childGroup;
final ChannelHandler currentChildHandler = childHandler;
final Entry<ChannelOption<?>, Object>[] currentChildOptions;
final Entry<AttributeKey<?>, Object>[] currentChildAttrs;
synchronized (childOptions) {
    currentChildOptions = childOptions.entrySet().toArray(newOptionArray(childOptions.size()));
}
synchronized (childAttrs) {
    currentChildAttrs = childAttrs.entrySet().toArray(newAttrArray(childAttrs.size()));
}
```
这里，和上面类似，只不过不是设置当前channel的这两个属性，而是对应到新进来连接对应的channel，由于我们这篇文章只关心到server如何启动，接入连接放到下一篇文章中详细剖析

3.加入新连接处理器

```java
p.addLast(new ChannelInitializer<Channel>() {
        @Override
        public void initChannel(Channel ch) throws Exception {
            final ChannelPipeline pipeline = ch.pipeline();
            ChannelHandler handler = config.handler();
            if (handler != null) {
                pipeline.addLast(handler);
            }

            ch.eventLoop().execute(new Runnable() {
                @Override
                public void run() {
                    pipeline.addLast(new ServerBootstrapAcceptor(
                            currentChildGroup, currentChildHandler, currentChildOptions, currentChildAttrs));
                }
            });
        }
    });
```
到了最后一步，`p.addLast()`向serverChannel的流水线处理器中加入了一个 `ServerBootstrapAcceptor`，从名字上就可以看出来，这是一个接入器，专门接受新请求，我们先不做过多分析

我们总结一下，发现其实init也没有启动服务，只是初始化了一些基本的配置和属性，以及在pipeline上加入了一个接入器，用来专门接受新连接，我们还得继续往下跟

##### 3.将这个channel register到某个对象
这一步，我们是分析如下方法

```java
ChannelFuture regFuture = config().group().register(channel);
```
调用到 `NioEventLoop` 中的register

```java
@Override
public ChannelFuture register(Channel channel) {
    return register(new DefaultChannelPromise(channel, this));
}
```

```java
@Override
public ChannelFuture register(final ChannelPromise promise) {
    ObjectUtil.checkNotNull(promise, "promise");
    promise.channel().unsafe().register(this, promise);
    return promise;
}
```
好了，到了这一步，还记得这里的`unsafe()`返回的应该是什么对象吗？不记得的话可以看下前面关于unsafe的描述，或者最快的方式就是debug到这边，跟到register方法里面，看看是哪种类型的unsafe 

我们跟进去之后发现是

> AbstractUnsafe.java

```java
@Override
public final void register(EventLoop eventLoop, final ChannelPromise promise) {
    // ...
    AbstractChannel.this.eventLoop = eventLoop;
    // ...
    register0(promise);
}
```
这里我们依然只需要focus重点，先将EventLoop事件循环器绑定到该NioServerSocketChannel上，然后调用 `register0()`

```java
private void register0(ChannelPromise promise) {
    try {
        boolean firstRegistration = neverRegistered;
        doRegister();
        neverRegistered = false;
        registered = true;

        pipeline.invokeHandlerAddedIfNeeded();

        safeSetSuccess(promise);
        pipeline.fireChannelRegistered();
        if (isActive()) {
            if (firstRegistration) {
                pipeline.fireChannelActive();
            } else if (config().isAutoRead()) {
                beginRead();
            }
        }
    } catch (Throwable t) {
        closeForcibly();
        closeFuture.setClosed();
        safeSetFailure(promise, t);
    }
}
```
这一段其实也很清晰，先调用 `doRegister();`，具体干啥待会再讲，然后调用`invokeHandlerAddedIfNeeded()`, 于是乎，控制台第一行打印出来的就是
```java
handlerAdded
```
关于最终是如何调用到的，我们后面详细剖析pipeline的时候再讲

然后调用 `pipeline.fireChannelRegistered();` 调用之后，控制台的显示为
```java
handlerAdded
channelRegistered
```
继续往下跟
```java
if (isActive()) {
    if (firstRegistration) {
        pipeline.fireChannelActive();
    } else if (config().isAutoRead()) {
        beginRead();
    }
}
```
读到这，你可能会想当然地以为，控制台最后一行是
```java
pipeline.fireChannelActive();
```
这行代码输出的，然后我们还需要看一下 `isActive()` 方法
```java
@Override
public boolean isActive() {
    return javaChannel().socket().isBound();
}
```
最终调用到jdk中
> ServerSocket.java

```java
    /**
     * Returns the binding state of the ServerSocket.
     *
     * @return true if the ServerSocket succesfuly bound to an address
     * @since 1.4
     */
    public boolean isBound() {
        // Before 1.3 ServerSockets were always bound during creation
        return bound || oldImpl;
    }
```
这里`isBound()`返回false，因为从目前看来，我们并没有将一个ServerSocket绑定到一个address，所以 `isActive()` 返回false，我们没有成功进入到`pipeline.fireChannelActive();`方法，那么最后一行到底是谁输出的呢，我们有点抓狂，其实，只要熟练运用IDE，要定位函数调用栈，无比简单
 
下面是我用intellij定位函数调用的具体方法





我们先在用户方法处打一个断点，然后debug，运行到这一行之后，intellij自动给我们拉起了调用栈，我们唯一要做的事，就是上下移动鼠标，就能看到函数的调用链

如果你看到方法的起点是一个线程Runnable的run方法，那么就在提交给Runnable方法之外打一个断点，去掉其他断点，继续debug，比如我们首次debug发现调用栈中的最近的一个Runnable如下

```java
if (!wasActive && isActive()) {
    invokeLater(new Runnable() {
        @Override
        public void run() {
            pipeline.fireChannelActive();
        }
    });
}
```
即栈顶指向了 `pipeline.fireChannelActive();`, 我们想看最初始的调用，就得跳出来，断点打到 `if (!wasActive && isActive())`，因为netty里面很多任务执行都是异步线程即reactor线程调用的(具体可以看reactor线程三部曲中的最后一曲)，如果我们要查看最先发起的方法调用，我们必须得查看Runnable被提交的地方，逐次递归下去，就能找到那行"消失的代码"

最终，通过这种方式，终于找到了 `pipeline.fireChannelActive();` 的发起调用的代码，不巧，刚好是下面的`doBind0()`方法

#### doBind0()

```java
private static void doBind0(
            final ChannelFuture regFuture, final Channel channel,
            final SocketAddress localAddress, final ChannelPromise promise) {
        channel.eventLoop().execute(new Runnable() {
            @Override
            public void run() {
                if (regFuture.isSuccess()) {
                    channel.bind(localAddress, promise).addListener(ChannelFutureListener.CLOSE_ON_FAILURE);
                } else {
                    promise.setFailure(regFuture.cause());
                }
            }
        });
    }
```
我们发现，在调用`doBind0(...)`方法的时候，是通过包装一个Runnable进行异步化的，关于异步化task，可以看下我前面的文章，[netty源码分析之揭开reactor线程的面纱（二）](http://www.jianshu.com/p/467a9b41833e)

好，接下来我们进入到`channel.bind()`方法

> AbstractChannel.java

```java
@Override
public ChannelFuture bind(SocketAddress localAddress) {
    return pipeline.bind(localAddress);
}
```
发现是调用pipeline的bind方法
```java
@Override
public final ChannelFuture bind(SocketAddress localAddress) {
    return tail.bind(localAddress);
}
```
相信你对tail是什么不是很了解，可以翻到最开始，tail在创建pipeline的时候出现过，关于pipeline和tail对应的类，我后面源码系列会详细解说，这里，你要想知道接下来代码的走向，唯一一个比较好的方式就是debug 单步进入，篇幅原因，我就不详细展开

最后，我们来到了如下区域

> HeadContext.java

```java
@Override
public void bind(
        ChannelHandlerContext ctx, SocketAddress localAddress, ChannelPromise promise)
        throws Exception {
    unsafe.bind(localAddress, promise);
}
```

这里的unsafe就是前面提到的 `AbstractUnsafe`, 准确点，应该是 `NioMessageUnsafe`

我们进入到它的bind方法

```java
@Override
public final void bind(final SocketAddress localAddress, final ChannelPromise promise) {
    // ...
    boolean wasActive = isActive();
    // ...
    doBind(localAddress);

    if (!wasActive && isActive()) {
        invokeLater(new Runnable() {
            @Override
            public void run() {
                pipeline.fireChannelActive();
            }
        });
    }
    safeSetSuccess(promise);
}
```

显然按照正常流程，我们前面已经分析到 `isActive();` 方法返回false，进入到 `doBind()`之后，如果channel被激活了，就发起`pipeline.fireChannelActive();`调用，最终调用到用户方法，在控制台打印出了最后一行，所以到了这里，你应该清楚为什么最终会在控制台按顺序打印出那三行字了吧

`doBind()`方法也很简单

```java
protected void doBind(SocketAddress localAddress) throws Exception {
    if (PlatformDependent.javaVersion() >= 7) {
        //noinspection Since15
        javaChannel().bind(localAddress, config.getBacklog());
    } else {
        javaChannel().socket().bind(localAddress, config.getBacklog());
    }
}
```
最终调到了jdk里面的bind方法，这行代码过后，正常情况下，就真正进行了端口的绑定。

### summary
最后，我们来做下总结，netty启动一个服务所经过的流程
1.设置启动类参数，最重要的就是设置channel
2.创建server对应的channel，创建各大组件，包括ChannelConfig,ChannelId,ChannelPipeline,Unsafe等
3.初始化server对应的channel，设置一些attr，option，以及设置子channel的attr，option，给server的channel添加新channel接入器，并出发addHandler,register等事件
4.调用到jdk底层做端口绑定，并触发active事件

另外，文章中阅读源码的思路详细或许也可以给你带来一些帮助，完。



