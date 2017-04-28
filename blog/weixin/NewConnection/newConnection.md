## 本文收获
1.netty如何接受新的请求
2.netty如何给新请求分配reactor线程
3.netty如何给每个新连接增加ChannelHandler


## 前序背景
读这篇文章之前，需要掌握一些前提知识，包括netty中的[reactor线程](http://www.jianshu.com/p/0d0eece6d467)，以及[服务端启动过程](http://www.jianshu.com/p/c5068caab217)
不掌握这些也没关系，下面我会带你简单地回顾一下

### netty中的reactor线程
netty中最核心的东西都是两种类型的reactor线程了，一种类型的reactor线程是boos线程组，专门用来接受新的连接，然后封装成channel对象扔给worker线程组

不管是boos线程还是worker线程，所做的事情均分为一下三个步骤

1. 轮询注册在selector上的IO事件
2. 处理IO事件
3. 执行异步task

对于boos线程来说，第一步轮询出来的基本都是 accept  事件，表示有新的连接，而worker线程轮询出来的基本都是read/write事件，表示网络的读写事件


### 服务端启动

服务端启动过程是在用户线程中开启，此时，将会启动boos线程，将接受新连接的过程封装成一个channel，对应的pipeline会按顺序处理新建立的连接(关于pipeline我后面会开篇详细分析)


## 新连接的建立

简单来说，新连接的建立可以分为两个步骤
1.检测到有新的连接
2.将新的连接注册到worker线程组

### 检测到有新连接进入
我们已经知道，当服务端绑启动之后，服务端的channel已经注册到boos reactor线程中，reactor不断检测有新的事件，直到检测出有accept事件发生

> NioEventLoop.java

```java
private void processSelectedKey(SelectionKey k, AbstractNioChannel ch) {
    final AbstractNioChannel.NioUnsafe unsafe = ch.unsafe();
    int readyOps = k.readyOps();
    if ((readyOps & (SelectionKey.OP_READ | SelectionKey.OP_ACCEPT)) != 0 || readyOps == 0) {
        unsafe.read();
    }
}
```

上面这段代码是[reactor线程三部曲中的第二部曲](http://www.jianshu.com/p/467a9b41833e)，表示boos reactor线程已经轮询到 `SelectionKey.OP_ACCEPT` 事件，表示有新的连接进入，此时将调用channel的 `unsafe`来进行实际的操作

关于 `unsafe`，这篇文章我不打算细讲，下面是netty作者对于unsafe的解释

>Unsafe operations that should never be called from user-code. These methods are only provided to implement the actual transport.

你只需要了解一个大概的概念，就是所有的channel底层都会有一个与unsafe绑定，每种类型的channel实际的操作都由unsafe来实现

而从上一篇文章，[服务端的启动过程](http://www.jianshu.com/p/c5068caab217)中，我们已经知道，服务端对应的channel的unsafe是 `NioMessageUnsafe`，那么，我们进入到TA的`read`方法，进入到第二个步骤

### 注册到reactor线程

> NioMessageUnsafe.java

```java
private final List<Object> readBuf = new ArrayList<Object>();

public void read() {
    assert eventLoop().inEventLoop();
    final ChannelPipeline pipeline = pipeline();
    final RecvByteBufAllocator.Handle allocHandle = unsafe().recvBufAllocHandle();
    do {
        int localRead = doReadMessages(readBuf);
        if (localRead == 0) {
            break;
        }
        if (localRead < 0) {
            closed = true;
            break;
        }
    } while (allocHandle.continueReading());
    int size = readBuf.size();
    for (int i = 0; i < size; i ++) {
        pipeline.fireChannelRead(readBuf.get(i));
    }
    readBuf.clear();
    pipeline.fireChannelReadComplete();
}
```
我省去了非关键部分的代码，可以看到，一上来，就用一条断言确定该read方法必须是reactor线程调用，然后拿到channel对应的pipeline和 `RecvByteBufAllocator.Handle`(先不解释)

接下来，调用 `doReadMessages` 方法不断地读取消息，用 `readBuf` 作为容器，这里，其实可以才到读取的是一个个连接，然后调用 `pipeline.fireChannelRead()`，将每条新的连接经过一层服务端channel的洗礼

之后清除容器，触发 `pipeline.fireChannelReadComplete()`，整个过程清晰明了，不含一丝杂质，下面我们具体看下这三个方法

1.doReadMessages(List)
2.pipeline.fireChannelRead(NioSocketChannel)
3.pipeline.fireChannelReadComplete(NioSocketChannel)


### 1.doReadMessages()

```java
protected int doReadMessages(List<Object> buf) throws Exception {
    SocketChannel ch = javaChannel().accept();

    try {
        if (ch != null) {
            buf.add(new NioSocketChannel(this, ch));
            return 1;
        }
    } catch (Throwable t) {
        logger.warn("Failed to create a new channel from an accepted socket.", t);

        try {
            ch.close();
        } catch (Throwable t2) {
            logger.warn("Failed to close a socket.", t2);
        }
    }

    return 0;
}
```
我们终于窥探的netty调用jdk底层nio的角落 `javaChannel().accept();`，由于netty中reactor线程扫描到有accept事件发生，因此，这里的`accept`方法是立即返回的，返回jdk底层nio创建的一条channel

netty将jdk的 `SocketChannel` 封装成自定义的 `NioSocketChannel`，加入到list里面，这样外层就可以遍历该list，做后续处理

从[上篇文章](http://www.jianshu.com/p/c5068caab217)中，我们已经知道服务端的创建过程中会创建netty中一系列的核心组件，包括pipeline,unsafe等等，那么，接受一条新连接的时候是否也会创建这一系列的组件呢？

带着这个疑问，我们跟进去

> NioSocketChannel.java

```java
public NioSocketChannel(Channel parent, SocketChannel socket) {
    super(parent, socket);
    config = new NioSocketChannelConfig(this, socket.socket());
}
```

我们重点分析 `super(parent, socket)`，config相关的分析我们放到后面的文章中
 
 `NioSocketChannel`的父类为 `AbstractNioByteChannel`

> AbstractNioByteChannel.java
```java
protected AbstractNioByteChannel(Channel parent, SelectableChannel ch) {
    super(parent, ch, SelectionKey.OP_READ);
}
```

这里，我们看到jdk nio里面熟悉的影子—— `SelectionKey.OP_READ`，一般在原生的jdk nio编程中，也会注册这样一个事件，表示对channel的读感兴趣

我们继续往上，追踪到`AbstractNioByteChannel`的父类 `AbstractNioChannel`, 这里，我相信读了[上篇文章](http://www.jianshu.com/p/c5068caab217)的你对于这部分代码肯定是有印象的

```java
protected AbstractNioChannel(Channel parent, SelectableChannel ch, int readInterestOp) {
    super(parent);
    this.ch = ch;
    this.readInterestOp = readInterestOp;
    try {
        ch.configureBlocking(false);
    } catch (IOException e) {
        try {
            ch.close();
        } catch (IOException e2) {
            if (logger.isWarnEnabled()) {
                logger.warn(
                        "Failed to close a partially initialized socket.", e2);
            }
        }
        throw new ChannelException("Failed to enter non-blocking mode.", e);
    }
}
```
回忆一下即可知道，在创建服务端channel的时候，最终也会进入到这个方法，`super(parent)`, 便是在`AbstractChannel`中创建一系列和该channel绑定的组件，如下

```java
protected AbstractChannel(Channel parent) {
    this.parent = parent;
    id = newId();
    unsafe = newUnsafe();
    pipeline = newChannelPipeline();
}
```
而这里的 `readInterestOp` 表示该channel关心的事件是 `SelectionKey.OP_READ`，后续会将该事件注册到selector

到了这里，我终于可以将netty里面最常用的channel的结构图放给你看




这里的继承关系有所简化，当前，我们只需要了解这么多就够了，首先
1.channel 继承 Comparable 表示channel是一个可以比较的对象
2.channel 继承AttributeMap表示channel是可以绑定属性的对象，在用户代码中，我们经常使用channel.attr(...)方法就是来源于此
3.ChannelOutboundInvoker是4.1.x版本新加的抽象，表示一条channel可以进行的操作
4.DefaultAttributeMap用于AttributeMap抽象的默认方法,后面channel继承了直接使用
5.AbstractChannel用于实现channel的大部分方法，其中我们最熟悉的就是其构造函数中，创建出一条channel的基本组件
6.AbstractNioChannel基于AbstractChannel做了nio相关的一些操作，保存jdk底层的 `SelectableChannel`，并且在构造函数中设置channel为非阻塞
7.最后，就是两大channel，NioServerSocketChannel，NioSocketChannel对应着服务端接受新连接和连接读写过程

读到这，关于channel的整体框架你基本已经了解了一大半了

好了，让我们退栈，继续之前的源码分析，在创建出一条 `NioSocketChannel`之后，放置的容器里面之后，就开始进行下一步操作

### 2.pipeline.fireChannelRead(NioSocketChannel)
> AbstractNioMessageChannel.java

```java
pipeline.fireChannelRead(NioSocketChannel);
```

在没有正式介绍pipeline之前，请让我简单介绍一下pipeline这个组件

在netty的各种类型的channel中，都会包含一个pipeline，字面意思是管道，我们可以理解为一条流水线工艺，流水线工艺有起点，有结束，中间还有各种个样的流水线关卡，一件物品，在流水线起点开始处理，经过各个流水线关卡的加工，最终到流水线结束

对应到netty里面，流水线的开始就是`HeadContxt`，流水线的结束就是`TailConext`，`HeadContxt`中调用`Unsafe`做具体的操作，`TailConext`中用于向用户抛出pipeline中未处理异常以及对未处理消息的警告，关于pipeline的具体分析我们后面再详细探讨

通过前面一篇文章，我们已经知道在服务端处理新连接的pipeline中，已经自动添加了一个pipeline处理器 `ServerBootstrapAcceptor`, 并已经将用户代码中设置的一系列的参数传入了构造函数，接下来，我们就来看下`ServerBootstrapAcceptor`


> ServerBootstrapAcceptor.java

```java
 private static class ServerBootstrapAcceptor extends ChannelInboundHandlerAdapter {
    private final EventLoopGroup childGroup;
    private final ChannelHandler childHandler;
    private final Entry<ChannelOption<?>, Object>[] childOptions;
    private final Entry<AttributeKey<?>, Object>[] childAttrs;

    ServerBootstrapAcceptor(
            EventLoopGroup childGroup, ChannelHandler childHandler,
            Entry<ChannelOption<?>, Object>[] childOptions, Entry<AttributeKey<?>, Object>[] childAttrs) {
        this.childGroup = childGroup;
        this.childHandler = childHandler;
        this.childOptions = childOptions;
        this.childAttrs = childAttrs;
    }

    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        final Channel child = (Channel) msg;

        child.pipeline().addLast(childHandler);

        for (Entry<ChannelOption<?>, Object> e: childOptions) {
            try {
                if (!child.config().setOption((ChannelOption<Object>) e.getKey(), e.getValue())) {
                    logger.warn("Unknown channel option: " + e);
                }
            } catch (Throwable t) {
                logger.warn("Failed to set a channel option: " + child, t);
            }
        }

        for (Entry<AttributeKey<?>, Object> e: childAttrs) {
            child.attr((AttributeKey<Object>) e.getKey()).set(e.getValue());
        }

        try {
            childGroup.register(child).addListener(new ChannelFutureListener() {
                @Override
                public void operationComplete(ChannelFuture future) throws Exception {
                    if (!future.isSuccess()) {
                        forceClose(child, future.cause());
                    }
                }
            });
        } catch (Throwable t) {
            forceClose(child, t);
        }
    }
```
前面的 `pipeline.fireChannelRead(NioSocketChannel);` 最终通过head->unsafe->ServerBootstrapAcceptor的调用链，调用到这里的 `ServerBootstrapAcceptor`  的`channelRead`方法

而 `channelRead` 一上来就把这里的msg强制转换为 `Channel`, 为什么这里可以强制转换？读者可以思考一下

然后，拿到该channel，也就是我们之前new出来的 `NioSocketChannel`对应的pipeline，将用户代码中的 `childHandler`，添加到pipeline，这里的 `childHandler` 在用户代码中的体现为

```java
ServerBootstrap b = new ServerBootstrap();
b.group(bossGroup, workerGroup)
 .channel(NioServerSocketChannel.class)
 .childHandler(new ChannelInitializer<SocketChannel>() {
     @Override
     public void initChannel(SocketChannel ch) throws Exception {
         ChannelPipeline p = ch.pipeline();
         p.addLast(new EchoServerHandler());
     }
 });
```
其实对应的是 `ChannelInitializer`，到了这里，`NioSocketChannel`中pipeline对应的处理器为 head->ChannelInitializer->tail


接着，设置 `NioSocketChannel` 对应的 attr和option，然后进入到 `childGroup.register(child)`，这里的childGroup就是我们在启动代码中new出来的`NioEventLoopGroup`，具体可以参考[这篇文章](http://www.jianshu.com/p/512e983eedf5)

我们进入到`NioEventLoopGroup`的`register`方法，代理到其父类`MultithreadEventLoopGroup`

> MultithreadEventLoopGroup.java

```java
public ChannelFuture register(Channel channel) {
    return next().register(channel);
}
```

这里又扯出来一个 next()方法, 我们跟进去

> MultithreadEventLoopGroup.java

```java
@Override
public EventLoop next() {
    return (EventLoop) super.next();
}
```

回到其父类
> MultithreadEventExecutorGroup.java

```java
@Override
public EventExecutor next() {
    return chooser.next();
}
```

这里的chooser对应的类为 `EventExecutorChooser`，字面意思为事件执行器选择器，放到我们这里的上下文中的作用就是从worker reactor线程组中选择一个reactor线程


```java
public interface EventExecutorChooserFactory {

    /**
     * Returns a new {@link EventExecutorChooser}.
     */
    EventExecutorChooser newChooser(EventExecutor[] executors);

    /**
     * Chooses the next {@link EventExecutor} to use.
     */
    @UnstableApi
    interface EventExecutorChooser {

        /**
         * Returns the new {@link EventExecutor} to use.
         */
        EventExecutor next();
    }
}
```


关于chooser的具体创建我不打算展开，相信前面几篇文章中的源码阅读技巧可以帮助你找出choose的始末，这里，我们只需要知道，chooser的实现有两种


```java
public final class DefaultEventExecutorChooserFactory implements EventExecutorChooserFactory {

    public static final DefaultEventExecutorChooserFactory INSTANCE = new DefaultEventExecutorChooserFactory();

    private DefaultEventExecutorChooserFactory() { }

    @SuppressWarnings("unchecked")
    @Override
    public EventExecutorChooser newChooser(EventExecutor[] executors) {
        if (isPowerOfTwo(executors.length)) {
            return new PowerOfTowEventExecutorChooser(executors);
        } else {
            return new GenericEventExecutorChooser(executors);
        }
    }

    private static boolean isPowerOfTwo(int val) {
        return (val & -val) == val;
    }

    private static final class PowerOfTowEventExecutorChooser implements EventExecutorChooser {
        private final AtomicInteger idx = new AtomicInteger();
        private final EventExecutor[] executors;

        PowerOfTowEventExecutorChooser(EventExecutor[] executors) {
            this.executors = executors;
        }

        @Override
        public EventExecutor next() {
            return executors[idx.getAndIncrement() & executors.length - 1];
        }
    }

    private static final class GenericEventExecutorChooser implements EventExecutorChooser {
        private final AtomicInteger idx = new AtomicInteger();
        private final EventExecutor[] executors;

        GenericEventExecutorChooser(EventExecutor[] executors) {
            this.executors = executors;
        }

        @Override
        public EventExecutor next() {
            return executors[Math.abs(idx.getAndIncrement() % executors.length)];
        }
    }
}
```

默认情况下，chooser通过 `DefaultEventExecutorChooserFactory`被创建，在创建reactor线程选择器的时候，会判断reactor线程的个数，如果是2的幂，就创建`PowerOfTowEventExecutorChooser`，否则，创建`GenericEventExecutorChooser`

两种类型的选择器在选择reactor线程的时候，通过Round-Robin的方式选择reactor线程，唯一不同的是，`PowerOfTowEventExecutorChooser`是通过与运算，而`GenericEventExecutorChooser`是通过取余运算，与运算的效率要高于求余运算


选择完一个reactor线程，即 `NioEventLoop` 之后，我们回到注册的地方

```java
public ChannelFuture register(Channel channel) {
    return next().register(channel);
}
```

进入到

> SingleThreadEventLoop.java

```java
@Override
public ChannelFuture register(Channel channel) {
    return register(new DefaultChannelPromise(channel, this));
}
```

其实，这里已经和服务端启动的过程一样了，详细步骤可以参考[服务端启动详解](http://www.jianshu.com/p/c5068caab217)这篇文章，我们直接跳到关键的地方


> AbstractNioChannel.java

```java
private void register0(ChannelPromise promise) {
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
}
```
这里的方法其实和服务端启动过程一样，先是调用 `doRegister();`做真正的注册过程，如下

```java
protected void doRegister() throws Exception {
    boolean selected = false;
    for (;;) {
        try {
            selectionKey = javaChannel().register(eventLoop().selector, 0, this);
            return;
        } catch (CancelledKeyException e) {
            if (!selected) {
                eventLoop().selectNow();
                selected = true;
            } else {
                throw e;
            }
        }
    }
}
```

到了这里，就将该条channel绑定到一个`selector`上去了，一个selector被一个reactor线程使用，后续该channel的事件轮询，以及事件处理，异步task执行都是由此reactor线程来负责

绑定完reactor线程之后，调用 `pipeline.invokeHandlerAddedIfNeeded()`

前面我们说到，到目前为止`NioSocketChannel` 的pipeline中有三个处理器，head->ChannelInitializer->tail，最终会调用到 `ChannelInitializer` 的 `handlerAdded` 方法
```java
public void handlerAdded(ChannelHandlerContext ctx) throws Exception {
    if (ctx.channel().isRegistered()) {
        initChannel(ctx);
    }
}
```

`handlerAdded`方法调用 `initChannel` 方法之后，调用`remove(ctx);`将自身删除

> AbstractNioChannel.java

```java
private boolean initChannel(ChannelHandlerContext ctx) throws Exception {
    if (initMap.putIfAbsent(ctx, Boolean.TRUE) == null) { 
        try {
            initChannel((C) ctx.channel());
        } catch (Throwable cause) {
            exceptionCaught(ctx, cause);
        } finally {
            remove(ctx);
        }
        return true;
    }
    return false;
}
```

而这里的 `initChannel` 方法，即是一个用户方法的回调，比如下面这段用户代码

> 用户代码

```java
ServerBootstrap b = new ServerBootstrap();
b.group(bossGroup, workerGroup)
 .channel(NioServerSocketChannel.class)
 .option(ChannelOption.SO_BACKLOG, 100)
 .handler(new LoggingHandler(LogLevel.INFO))
 .childHandler(new ChannelInitializer<SocketChannel>() {
     @Override
     public void initChannel(SocketChannel ch) throws Exception {
         ChannelPipeline p = ch.pipeline();
         p.addLast(new LoggingHandler(LogLevel.INFO));
         p.addLast(new EchoServerHandler());
     }
 });
```
完了之后，`NioSocketChannel`绑定的pipeline的处理器就包括 head->LoggingHandler->EchoServerHandler->tail



接下来，我们还剩下这些代码没有分析完


> AbstractNioChannel.java

```java
private void register0(ChannelPromise promise) {
    // ..
    pipeline.fireChannelRegistered();
    if (isActive()) {
        if (firstRegistration) {
            pipeline.fireChannelActive();
        } else if (config().isAutoRead()) {
            beginRead();
        }
    }
}
```
`pipeline.fireChannelRegistered();`，其实没有干啥有意义的事情，最终无非是再调用一下业务pipeline中每个处理器的 `ChannelHandlerAdded`方法

`isActive()`在连接已经建立的情况下返回true，所以进入方法块，进入到 `pipeline.fireChannelActive();`，这里的分析和[netty源码分析之服务端启动全解析](http://www.jianshu.com/p/c5068caab217)分析中的一样，详细步骤先省略，我们直接进入到关键区域


> AbstractNioChannel.java

```java
@Override
protected void doBeginRead() throws Exception {
    // Channel.read() or ChannelHandlerContext.read() was called
    final SelectionKey selectionKey = this.selectionKey;
    if (!selectionKey.isValid()) {
        return;
    }

    readPending = true;

    final int interestOps = selectionKey.interestOps();
    if ((interestOps & readInterestOp) == 0) {
        selectionKey.interestOps(interestOps | readInterestOp);
    }
}
```
你应该还记得前面 `register0()` 方法的时候，向selector注册的事件代码是0，而 `readInterestOp`对应的事件代码是 `SelectionKey.OP_READ`，参考前文中创建 `NioSocketChannel` 的过程
 
那这里其实就是将 `SelectionKey.OP_READ`事件注册到selector中去，表示这条通道已经可以开始处理read事件了

## 总结

至此，netty中关于新连接的处理已经向你展示完了，我们做下总结

1.boos reactor线程轮询到有新的连接进入
2.创建 `NioSocketChannel`以及一系列的netty核心组件
3.将该条连接通过chooser，选择一条worker reactor线程绑定上去
4.注册读事件，开始新连接的读写

下篇文章将深挖netty中的核心组件 `pipeline`，敬请期待