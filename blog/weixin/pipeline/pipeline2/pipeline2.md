## 前言

[netty源码分析之pipeline(一)](http://www.jianshu.com/p/6efa9c5fa702)中，我们已经了解了pipeline在netty中所处的角色，像是一条流水线，控制着字节流的读写，本文，我们在这个基础上继续深挖pipeline的的其他细节

## 主要内容

接下来，本文分以下几个部分进行源码分析
1. netty中的Unsafe到底是干什么的
2. pipeline中的head
3. pipeline中的inBound事件传播
4. pipeline中的tail
5. pipeline中的outBound事件传播
6. pipeline 中异常的传播

##Unsafe到底是干什么的
之所以Unsafe放到pipeline中讲，是因为unsafe和pipeline密切相关，pipeline中的有关io的操作最终都是落地到unsafe，所以，有必要先讲讲unsafe


### 初识Unsafe 
顾名思义，unsafe是不安全的意思，就是告诉你不要在应用程序里面直接使用Unsafe以及他的衍生类对象。

netty官方的解释如下

>Unsafe operations that should never be called from user-code. These methods are only provided to implement the actual transport, and must be invoked from an I/O thread

Unsafe 在Channel定义，属于Channel的内部类，表明Unsafe和Channel密切相关

下面是unsafe接口的所有方法
 
 ```java
interface Unsafe {
    RecvByteBufAllocator.Handle recvBufAllocHandle();
    
    SocketAddress localAddress();
    SocketAddress remoteAddress();

    void register(EventLoop eventLoop, ChannelPromise promise);
    void bind(SocketAddress localAddress, ChannelPromise promise);
    void connect(SocketAddress remoteAddress, SocketAddress localAddress, ChannelPromise promise);
    void disconnect(ChannelPromise promise);
    void close(ChannelPromise promise);
    void closeForcibly();
    void beginRead();
    void write(Object msg, ChannelPromise promise);
    void flush();
    
    ChannelPromise voidPromise();
    ChannelOutboundBuffer outboundBuffer();
}
```
按功能可以分为分配内存，Socket四元组信息，注册事件循环，绑定网卡端口，Socket的连接和关闭，Socket的读写，看的出来，这些操作都是和jdk底层相关

### Unsafe 继承结构

[1.png]


`NioUnsafe` 在 `Unsafe`基础上增加了以下几个接口

```java
public interface NioUnsafe extends Unsafe {
    SelectableChannel ch();
    void finishConnect();
    void read();
    void forceFlush();
}
```
从增加的接口以及类名上来看，`NioUnsafe` 增加了可以访问底层jdk的`SelectableChannel`的功能，定义了从`SelectableChannel`读取数据的`read`方法

`AbstractUnsafe` 实现了大部分`Unsafe`的功能

`AbstractNioUnsafe` 主要是通过代理到其外部类`AbstractNioChannel`拿到了与jdk nio相关的一些信息，比如`SelectableChannel`，`SelectionKey`等等

`NioSocketChannelUnsafe`和`NioByteUnsafe`放到一起讲，其实现了IO的基本操作，读，和写，这些操作都与jdk底层相关

`NioMessageUnsafe`和 `NioByteUnsafe` 是处在同一层次的抽象，netty将一个新连接的建立也当作一个io操作来处理，这里的Message的含义我们可以当作是一个`SelectableChannel`，读的意思就是accept一个`SelectableChannel`，写的意思是针对一些无连接的协议，比如UDP来操作的，我们先不用关注

### Unsafe的分类
从以上继承结构来看，我们可以总结出两种类型的Unsafe分类，一个是与连接的字节数据读写相关的`NioByteUnsafe`，一个是与新连接建立操作相关的`NioMessageUnsafe`

> `NioByteUnsafe`中的读：委托到外部类NioSocketChannel

```java
protected int doReadBytes(ByteBuf byteBuf) throws Exception {
    final RecvByteBufAllocator.Handle allocHandle = unsafe().recvBufAllocHandle();
    allocHandle.attemptedBytesRead(byteBuf.writableBytes());
    return byteBuf.writeBytes(javaChannel(), allocHandle.attemptedBytesRead());
}
```
最后一行已经与jdk底层以及netty中的ByteBuf相关，将jdk的 `SelectableChannel`的字节数据读取到netty的`ByteBuf`中

> `NioMessageUnsafe`中的读：委托到外部类NioSocketChannel

```java
protected int doReadMessages(List<Object> buf) throws Exception {
    SocketChannel ch = javaChannel().accept();

    if (ch != null) {
        buf.add(new NioSocketChannel(this, ch));
        return 1;
    }
    return 0;
}
```
`NioMessageUnsafe` 的读操作很简单，就是调用jdk的`accept()`方法，新建立一条连接


> `NioByteUnsafe`中的写：委托到外部类NioSocketChannel

```java
@Override
protected int doWriteBytes(ByteBuf buf) throws Exception {
    final int expectedWrittenBytes = buf.readableBytes();
    return buf.readBytes(javaChannel(), expectedWrittenBytes);
}
```
最后一行已经与jdk底层以及netty中的ByteBuf相关，将netty的`ByteBuf`中的字节数据写到jdk的 `SelectableChannel`中

`NioMessageUnsafe` 的写，在tcp协议层面我们基本不会涉及，暂时忽略，udp协议的读者可以自己去研究一番~

关于`Unsafe`我们就先了解这么多 

##pipeline中的head
[netty源码分析之pipeline(一)](http://www.jianshu.com/p/6efa9c5fa702)中，我们了解到head节点在pipeline中第一个处理IO事件的节点，新连接接入和读事件在[reactor线程的第二个步骤](http://www.jianshu.com/p/467a9b41833e)被检测到

> NioEventLoop

```java
private void processSelectedKey(SelectionKey k, AbstractNioChannel ch) {
     final AbstractNioChannel.NioUnsafe unsafe = ch.unsafe();
     //新连接的已准备接入或者已存在的连接有数据可读
     if ((readyOps & (SelectionKey.OP_READ | SelectionKey.OP_ACCEPT)) != 0 || readyOps == 0) {
         unsafe.read();
     }
}
```
读操作直接依赖到unsafe来进行，新连接的接入在[netty源码分析之新连接接入全解析](http://www.jianshu.com/p/0242b1d4dd21)中已详细阐述，这里不再描述，下面将重点放到已存在的连接字节数据流的读写

> NioByteUnsafe

```java
@Override
public final void read() {
    final ChannelConfig config = config();
    final ChannelPipeline pipeline = pipeline();
    // 创建ByteBuf分配器
    final ByteBufAllocator allocator = config.getAllocator();
    final RecvByteBufAllocator.Handle allocHandle = recvBufAllocHandle();
    allocHandle.reset(config);

    ByteBuf byteBuf = null;
    do {
        // 分配一个ByteBuf
        byteBuf = allocHandle.allocate(allocator);
        // 将数据读取到分配的ByteBuf中去
        allocHandle.lastBytesRead(doReadBytes(byteBuf));
        if (allocHandle.lastBytesRead() <= 0) {
            byteBuf.release();
            byteBuf = null;
            close = allocHandle.lastBytesRead() < 0;
            break;
        }

        // 触发事件，将会引发pipeline的读事件传播
        pipeline.fireChannelRead(byteBuf);
        byteBuf = null;
    } while (allocHandle.continueReading());
    pipeline.fireChannelReadComplete();
}
```

同样，我抽出了核心代码，细枝末节先剪去，`NioByteUnsafe` 要做的事情可以简单地分为以下几个步骤
1. 拿到Channel的config之后拿到ByteBuf分配器，用分配器来分配一个ByteBuf，ByteBuf是netty里面的字节数据载体，后面读取的数据都读到这个对象里面
2. 将Channel中的数据读取到ByteBuf
3. 调用`fireChannelRead`触发pipeline的读事件，将读取到的ByteBuf进行事件的传播
4. 数据读完之后，调用 `pipeline.fireChannelRead(byteBuf);` 从head节点开始传播至整个pipeline

这里，我们的重点其实就是 `pipeline.fireChannelRead(byteBuf);`

> DefaultChannelPipeline

```java
final AbstractChannelHandlerContext head;
//...
head = new HeadContext(this);

public final ChannelPipeline fireChannelRead(Object msg) {
    AbstractChannelHandlerContext.invokeChannelRead(head, msg);
    return this;
}
```

结合这幅图
[2.png]

可以看到，数据从head节点开始流入，在进行下一步之前，我们先把head节点的功能过一遍

> HeadContext

```java
final class HeadContext extends AbstractChannelHandlerContext
        implements ChannelOutboundHandler, ChannelInboundHandler {

    private final Unsafe unsafe;

    HeadContext(DefaultChannelPipeline pipeline) {
        super(pipeline, null, HEAD_NAME, false, true);
        unsafe = pipeline.channel().unsafe();
        setAddComplete();
    }

    @Override
    public ChannelHandler handler() {
        return this;
    }

    @Override
    public void handlerAdded(ChannelHandlerContext ctx) throws Exception {
        // NOOP
    }

    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) throws Exception {
        // NOOP
    }

    @Override
    public void bind(
            ChannelHandlerContext ctx, SocketAddress localAddress, ChannelPromise promise)
            throws Exception {
        unsafe.bind(localAddress, promise);
    }

    @Override
    public void connect(
            ChannelHandlerContext ctx,
            SocketAddress remoteAddress, SocketAddress localAddress,
            ChannelPromise promise) throws Exception {
        unsafe.connect(remoteAddress, localAddress, promise);
    }

    @Override
    public void disconnect(ChannelHandlerContext ctx, ChannelPromise promise) throws Exception {
        unsafe.disconnect(promise);
    }

    @Override
    public void close(ChannelHandlerContext ctx, ChannelPromise promise) throws Exception {
        unsafe.close(promise);
    }

    @Override
    public void deregister(ChannelHandlerContext ctx, ChannelPromise promise) throws Exception {
        unsafe.deregister(promise);
    }

    @Override
    public void read(ChannelHandlerContext ctx) {
        unsafe.beginRead();
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
        unsafe.write(msg, promise);
    }

    @Override
    public void flush(ChannelHandlerContext ctx) throws Exception {
        unsafe.flush();
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        ctx.fireExceptionCaught(cause);
    }

    @Override
    public void channelRegistered(ChannelHandlerContext ctx) throws Exception {
        invokeHandlerAddedIfNeeded();
        ctx.fireChannelRegistered();
    }

    @Override
    public void channelUnregistered(ChannelHandlerContext ctx) throws Exception {
        ctx.fireChannelUnregistered();

        // Remove all handlers sequentially if channel is closed and unregistered.
        if (!channel.isOpen()) {
            destroy();
        }
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        ctx.fireChannelActive();

        readIfIsAutoRead();
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        ctx.fireChannelInactive();
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        ctx.fireChannelRead(msg);
    }

    @Override
    public void channelReadComplete(ChannelHandlerContext ctx) throws Exception {
        ctx.fireChannelReadComplete();

        readIfIsAutoRead();
    }

    private void readIfIsAutoRead() {
        if (channel.config().isAutoRead()) {
            channel.read();
        }
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
        ctx.fireUserEventTriggered(evt);
    }

    @Override
    public void channelWritabilityChanged(ChannelHandlerContext ctx) throws Exception {
        ctx.fireChannelWritabilityChanged();
    }
}
```

从head节点继承的两个接口看，TA既是一个ChannelHandlerContext，同时又属于inBound(最新代码已经加上这一接口)和outBound Handler

在传播读写事件的时候，head的功能只是简单地将事件传播下去，如`ctx.fireChannelRead(msg);`

在真正执行读写操作的时候，例如在调用`writeAndFlush()`等方法的时候，最终都会委托到unsafe执行，而当一次数据读完，`channelReadComplete`方法首先被调用，TA要做的事情除了将事件继续传播下去之外，还得继续向reactor线程注册读事件，即调用`readIfIsAutoRead()`, 我们简单跟一下

> HeadContext

```java
private void readIfIsAutoRead() {
    if (channel.config().isAutoRead()) {
        channel.read();
    }
}
```

> AbstractChannel

```java
@Override
public Channel read() {
    pipeline.read();
    return this;
}
```

默认情况下，Channel都是默认开启自动读取模式的，即只要Channel是active的，读完一波数据之后就继续向selector注册读事件，这样就可以连续不断得读取数据，最终，通过pipeline，还是传递到head节点

> HeadContext
```java
@Override
public void read(ChannelHandlerContext ctx) {
    unsafe.beginRead();
}
```
委托到了 `NioByteUnsafe`

> NioByteUnsafe

```java
@Override
public final void beginRead() {
    doBeginRead();
} 
```

> AbstractNioChannel

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

`doBeginRead()` 做的事情很简单，拿到处理过的`selectionKey`，然后如果发现该selectionKey若在某个地方被移除了`readInterestOp`操作，这里给他加上，事实上，标准的netty程序是不会走到这一行的，只有在三次握手成功之后，如下方法被调用


```java
public void channelActive(ChannelHandlerContext ctx) throws Exception {
    ctx.fireChannelActive();

    readIfIsAutoRead();
}
```
才会将`readInterestOp`注册到SelectionKey上，可结合 [netty源码分析之新连接接入全解析](http://www.jianshu.com/p/0242b1d4dd21) 来看

总结一点，head节点的作用就是作为pipeline的头节点开始传递读写事件，调用unsafe进行实际的读写操作，下面，进入pipeline中非常重要的一换，事件的传播

##pipeline中的inBound事件传播

在 [netty源码分析之新连接接入全解析](http://www.jianshu.com/p/0242b1d4dd21) 一文中，我们没有详细描述为啥`pipeline.fireChannelActive();`最终会调用到`AbstractNioChannel.doBeginRead()`，了解pipeline中的事件传播机制，你会发现相当简单

> DefaultChannelPipeline

```java
public final ChannelPipeline fireChannelActive() {
    AbstractChannelHandlerContext.invokeChannelActive(head);
    return this;
}
```

三次握手成功之后，`pipeline.fireChannelActive();`被调用，然后以head节点为参数，直接一个静态调用

> AbstractChannelHandlerContext

```java
static void invokeChannelActive(final AbstractChannelHandlerContext next) {
    EventExecutor executor = next.executor();
    if (executor.inEventLoop()) {
        next.invokeChannelActive();
    } else {
        executor.execute(new Runnable() {
            @Override
            public void run() {
                next.invokeChannelActive();
            }
        });
    }
}
```
首先，netty为了确保线程的安全性，将确保该操作在reactor线程中被执行，这里直接调用 `HeadContext.fireChannelActive()`方法

> HeadContext

```java
public void channelActive(ChannelHandlerContext ctx) throws Exception {
    ctx.fireChannelActive();

    readIfIsAutoRead();
}
```
我们先看 `ctx.fireChannelActive();`，跟进去之前我们先看下当前pipeline的情况
[2.png]

> AbstractChannelHandlerContext

```java
public ChannelHandlerContext fireChannelActive() {
    final AbstractChannelHandlerContext next = findContextInbound();
    invokeChannelActive(next);
    return this;
}
```

首先，调用 `findContextInbound()` 找到下一个inbound节点，由于当前pipeline的双向链表结构中既有inbound节点，又有outbound节点，让我们看看netty是怎么找到下一个inBound节点的

> AbstractChannelHandlerContext

```java
private AbstractChannelHandlerContext findContextInbound() {
    AbstractChannelHandlerContext ctx = this;
    do {
        ctx = ctx.next;
    } while (!ctx.inbound);
    return ctx;
}
```
这段代码很清楚地表明，netty寻找下一个inBound节点的过程是一个线性搜索的过程，他会遍历双向链表的下一个节点，直到下一个节点为inBound(关于inBound和outBound，[netty源码分析之pipeline(一)](http://www.jianshu.com/p/6efa9c5fa702)已有说明，这里不再详细分析)

找到下一个节点之后，执行 `invokeChannelActive(next);`，一个递归调用，知道最后一个inBound节点——tail节点

> TailContext

```java
@Override
public void channelActive(ChannelHandlerContext ctx) throws Exception { }
```

Tail节点的该方法为空，结束调用，同理，可以分析所有的inBound事件的传播，正常情况下，即用户如果不覆盖每个节点的事件传播操作，几乎所有的事件最后都落到tail节点，所以，我们有必要研究一下tail节点所具有的功能


##pipeline中的tail

```java
final class TailContext extends AbstractChannelHandlerContext implements ChannelInboundHandler {

    TailContext(DefaultChannelPipeline pipeline) {
        super(pipeline, null, TAIL_NAME, true, false);
        setAddComplete();
    }

    @Override
    public ChannelHandler handler() {
        return this;
    }

    @Override
    public void channelRegistered(ChannelHandlerContext ctx) throws Exception { }

    @Override
    public void channelUnregistered(ChannelHandlerContext ctx) throws Exception { }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception { }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception { }

    @Override
    public void channelWritabilityChanged(ChannelHandlerContext ctx) throws Exception { }

    @Override
    public void handlerAdded(ChannelHandlerContext ctx) throws Exception { }

    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) throws Exception { }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
        // This may not be a configuration error and so don't log anything.
        // The event may be superfluous for the current pipeline configuration.
        ReferenceCountUtil.release(evt);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        onUnhandledInboundException(cause);
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        onUnhandledInboundMessage(msg);
    }

    @Override
    public void channelReadComplete(ChannelHandlerContext ctx) throws Exception { }
}
```

正如我们前面所提到的，tail节点的大部分作用即终止事件的传播(方法体为空)，除此之外，有两个重要的方法我们必须提一下，`exceptionCaught()`和`channelRead()`

exceptionCaught

```java
protected void onUnhandledInboundException(Throwable cause) {
    try {
        logger.warn(
                "An exceptionCaught() event was fired, and it reached at the tail of the pipeline. " +
                        "It usually means the last handler in the pipeline did not handle the exception.",
                cause);
    } finally {
        ReferenceCountUtil.release(cause);
    }
}
```

异常传播的机制和inBound事件传播的机制一样，最终如果用户自定义节点没有处理的话，会落到tail节点，tail节点可不会简单地吞下这个异常，而是向你发出警告，相信使用netty的同学对这段警告不陌生吧？

> channelRead
```java
protected void onUnhandledInboundMessage(Object msg) {
    try {
        logger.debug(
                "Discarded inbound message {} that reached at the tail of the pipeline. " +
                        "Please check your pipeline configuration.", msg);
    } finally {
        ReferenceCountUtil.release(msg);
    }
}
```
tail节点在发现字节数据(ByteBuf)或者decoder之后的业务对象在pipeline流转过程中没有被消费，落到tail节点，tail节点就会给你发出一个警告，告诉你，我已经将你未处理的数据给丢掉了

总结一下，tail节点的作用就是结束事件传播，并且对一些重要的事件做一些善意提醒


## pipeline中的outBound事件传播
上一节中，我们在阐述tail节点的功能时，忽略了其父类`AbstractChannelHandlerContext`所具有的功能，这一节中，我们以最常见的writeAndFlush操作来看下pipeline中的outBound事件是如何向外传播的

典型的消息推送系统中，会有类似下面的一段代码

```java
Channel channel = getChannel(userInfo);
channel.writeAndFlush(pushInfo);
```
这段代码的含义就是根据用户信息拿到对应的Channel，然后给用户推送消息，跟进 `channel.writeAndFlush`

> NioSocketChannel

```java
public ChannelFuture writeAndFlush(Object msg) {
    return pipeline.writeAndFlush(msg);
}
```

从pipeline开始往外传播

```java
public final ChannelFuture writeAndFlush(Object msg) {
    return tail.writeAndFlush(msg);
}
```

Channel 中大部分outBound事件都是从tail开始往外传播, `writeAndFlush()`方法是tail继承而来的方法，我们跟进去

> AbstractChannelHandlerContext

```java
public ChannelFuture writeAndFlush(Object msg) {
    return writeAndFlush(msg, newPromise());
}

public ChannelFuture writeAndFlush(Object msg, ChannelPromise promise) {
    write(msg, true, promise);

    return promise;
}
```
这里提前说一点，netty中很多io操作都是异步操作，返回一个`ChannelFuture`给调用方，调用方拿到这个future可以在适当的实际拿到操作的结果，或者注册回调，后面的源码系列会深挖，这里就带过了，我们继续

> AbstractChannelHandlerContext

```java
private void write(Object msg, boolean flush, ChannelPromise promise) {
    AbstractChannelHandlerContext next = findContextOutbound();
    final Object m = pipeline.touch(msg, next);
    EventExecutor executor = next.executor();
    if (executor.inEventLoop()) {
        if (flush) {
            next.invokeWriteAndFlush(m, promise);
        } else {
            next.invokeWrite(m, promise);
        }
    } else {
        AbstractWriteTask task;
        if (flush) {
            task = WriteAndFlushTask.newInstance(next, m, promise);
        }  else {
            task = WriteTask.newInstance(next, m, promise);
        }
        safeExecute(executor, task, promise, m);
    }
}
```

netty为了保证程序的高效执行，所有的核心的操作都在reactor线程中处理，如果业务线程调用Channel的读写方法，netty会将该操作封装成一个task，随后在reactor线程中执行，参考 [netty源码分析之揭开reactor线程的面纱（三）](http://www.jianshu.com/p/58fad8e42379)，异步task的执行

这里我们为了不跑偏，假设是在reactor线程中(上面的这段例子其实是在业务线程中)，先调用`findContextOutbound()`方法找到下一个`outBound()`节点

> AbstractChannelHandlerContext

```java
private AbstractChannelHandlerContext findContextOutbound() {
    AbstractChannelHandlerContext ctx = this;
    do {
        ctx = ctx.prev;
    } while (!ctx.outbound);
    return ctx;
}
```

找outBound节点的过程和找inBound节点类似，反方向遍历pipeline中的双向链表，直到第一个outBound节点`next`，然后调用`next.invokeWriteAndFlush(m, promise)`


> AbstractChannelHandlerContext

```java
private void invokeWriteAndFlush(Object msg, ChannelPromise promise) {
    if (invokeHandler()) {
        invokeWrite0(msg, promise);
        invokeFlush0();
    } else {
        writeAndFlush(msg, promise);
    }
}
```

调用该节点的ChannelHandler的write方法，flush方法我们暂且忽略，后面会专门讲writeAndFlush的完整流程

> AbstractChannelHandlerContext

```java
private void invokeWrite0(Object msg, ChannelPromise promise) {
    try {
        ((ChannelOutboundHandler) handler()).write(this, msg, promise);
    } catch (Throwable t) {
        notifyOutboundHandlerException(t, promise);
    }
}
```

我们在使用outBound类型的ChannelHandler中，一般会继承 `ChannelOutboundHandlerAdapter`，所以，我们需要看看他的 `write`方法是怎么处理outBound事件传播的

> ChannelOutboundHandlerAdapter

```java
public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
    ctx.write(msg, promise);
}
```

很简单，他除了递归调用 `ctx.write(msg, promise);`之外，啥事也没干，在[netty源码分析之pipeline(一)](http://www.jianshu.com/p/6efa9c5fa702)我们已经知道，pipeline的双向链表结构中，最后一个outBound节点是head节点，因此数据最终会落地到TA的如下方法

> HeadContext

```java
public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
    unsafe.write(msg, promise);
}
```
这里，加深了我们对head节点的理解，即所有的数据写出都会经过head节点，我们在下一节会深挖，这里暂且到此为止


然而，实际情况下，outBound类的节点中会有一种特殊类型的节点叫encoder，它的作用是根据自定义编码规则将业务对象转换成ByteBuf，而这类encoder 一般继承自 `MessageToByteEncoder`

下面是一段
```java
public abstract class DataPacketEncoder extends MessageToByteEncoder<DatePacket> {

    @Override
    protected void encode(ChannelHandlerContext ctx, DatePacket msg, ByteBuf out) throws Exception {
        // 这里拿到业务对象msg的数据，然后调用 out.writeXXX()系列方法编码
    }
}
```

为什么业务代码只需要覆盖这里的encoder方法，就可以将业务对象转换成字节流写出去呢？通过前面的调用链条，我们需要查看一下其父类`MessageToByteEncoder`的write方法是怎么处理业务对象的

> MessageToByteEncoder

```java
public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
    ByteBuf buf = null;
    try {
        // 需要判断当前编码器能否处理这类对象
        if (acceptOutboundMessage(msg)) {
            I cast = (I) msg;
            // 分配内存
            buf = allocateBuffer(ctx, cast, preferDirect);
            try {
                encode(ctx, cast, buf);
            } finally {
                ReferenceCountUtil.release(cast);
            }
            // buf到这里已经装载着数据，于是把该buf往前丢，知道head节点
            if (buf.isReadable()) {
                ctx.write(buf, promise);
            } else {
                buf.release();
                ctx.write(Unpooled.EMPTY_BUFFER, promise);
            }
            buf = null;
        } else {
            // 如果不能处理，就将outBound事件继续往前面传播
            ctx.write(msg, promise);
        }
    } catch (EncoderException e) {
        throw e;
    } catch (Throwable e) {
        throw new EncoderException(e);
    } finally {
        if (buf != null) {
            buf.release();
        }
    }
```

先调用 `acceptOutboundMessage` 方法判断，该encoder是否可以处理msg对应的类的对象（暂不展开），通过之后，就强制转换，这里的泛型I对应的是`DataPacket`，转换之后，先开辟一段内存，调用`encode()`，即回到`DataPacketEncoder`中，将buf装满数据，最后，如果buf中被写了数据(`buf.isReadable()`)，就将该buf往前丢，一直传递到head节点，被head节点的unsafe消费掉

当然，如果当前encoder不能处理当前业务对象，就简单地将该业务对象向前传播，直到head节点，最后，都处理完之后，释放buf，避免堆外内存泄漏

##pipeline 中异常的传播

我们通常在业务代码中，会加入一个异常处理器，统一处理pipeline过程中的所有的异常，并且，一般该异常处理器需要加载自定义节点的最末尾，即


[3.png]

此类ExceptionHandler一般继承自 `ChannelDuplexHandler`，标识该节点既是一个inBound节点又是一个outBound节点，这样，他能够处理pipeline调用链中的所有的未处理异常，我们分别分析一下inBound事件和outBound事件过程中，ExceptionHandler是如何才处理这些异常的

### inBound异常的处理

我们以数据的读取为例，看下netty是如何传播在这个过程中发生的异常

我们前面已经知道，对于每一个节点的数据读取都会调用`AbstractChannelHandlerContext.iinvokeChannelRead()`方法

> AbstractChannelHandlerContext

```java
private void invokeChannelRead(Object msg) {
    try {
        ((ChannelInboundHandler) handler()).channelRead(this, msg);
    } catch (Throwable t) {
        notifyHandlerException(t);
    }
}
```

可以看到该节点最终委托到其内部的ChannelHandler处理channelRead，而在最外层catch整个Throwable，因此，我们在如下用户代码中的异常会被捕获


```java
public class BusinessHandler extends ChannelInboundHandlerAdapter {
    @Override
    protected void channelRead(ChannelHandlerContext ctx, Object data) throws Exception {
       //...
          throw new BusinessException(...); 
       //...
    }

}
```
上面这段业务代码中的 `BusinessException` 会被 `BusinessHandler`所在的节点捕获，进入到 `notifyHandlerException(t);`往下传播，我们看下它是如何传播的

> AbstractChannelHandlerContext

```java
private void notifyHandlerException(Throwable cause) {
    // 略去了非关键代码，读者可自行分析
    invokeExceptionCaught(cause);
}
```

```java
private void invokeExceptionCaught(final Throwable cause) {
    handler().exceptionCaught(this, cause);
}
```

可以看到，此Hander中异常优先由此Handelr中的`exceptionCaught`方法来处理，默认情况下，如果不覆写此Handler中的`exceptionCaught`方法，调用

> ChannelInboundHandlerAdapter

```java
public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause)
        throws Exception {
    ctx.fireExceptionCaught(cause);
}
```

> AbstractChannelHandlerContext

```java
public ChannelHandlerContext fireExceptionCaught(final Throwable cause) {
    invokeExceptionCaught(next, cause);
    return this;
}
```

到了这里，已经很清楚了，如果我们在自定义Handler中没有处理异常，那么默认情况下该异常将一直传递下去，遍历每一个节点，知道最后一个自定义异常处理器ExceptionHandler来终结，收编异常

> Exceptionhandler

```java
public Exceptionhandler extends ChannelDuplexHandler {
    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause)
            throws Exception {
        // 处理该异常，并终止异常的传播
    }
}
```
到了这里，你应该知道为什么异常处理器要加在pipeline的最后了吧？


### outBound异常的处理

然而对于outBound事件传播过程中所发生的异常，该`Exceptionhandler`照样能完美处理，为什么？

我们以前面提到的`writeAndFlush`方法为例，来看看outBound事件传播过程中的异常最后是如何落到`Exceptionhandler`中去的

前面我们知道，`channel.writeAndFlush()`方法最终也会调用到节点的 `invokeFlush0()`方法（write机制比较复杂，我们留到后面的文章中将）

> AbstractChannelHandlerContext

```java
private void invokeWriteAndFlush(Object msg, ChannelPromise promise) {
    if (invokeHandler()) {
        invokeWrite0(msg, promise);
        invokeFlush0();
    } else {
        writeAndFlush(msg, promise);
    }
}


private void invokeFlush0() {
    try {
        ((ChannelOutboundHandler) handler()).flush(this);
    } catch (Throwable t) {
        notifyHandlerException(t);
    }
}
```



而`invokeFlush0()`会委托其内部的ChannelHandler的flush方法，我们一般实现的即是ChannelHandler的flush方法

```java
private void invokeFlush0() {
    try {
        ((ChannelOutboundHandler) handler()).flush(this);
    } catch (Throwable t) {
        notifyHandlerException(t);
    }
}
```

好，假设在当前节点在flush的过程中发生了异常，都会被 `notifyHandlerException(t);`捕获，该方法会和inBound事件传播过程中的异常传播方法一样，也是轮流找下一个异常处理器，而如果异常处理器在pipeline最后面的话，一定会被执行到，这就是为什么该异常处理器也能处理outBound异常的原因

关于为啥 `ExceptionHandler` 既能处理inBound，又能处理outBound类型的异常的原因，总结一点就是，在任何节点中发生的异常都会往下一个节点传递


## 总结
最后，老样子，我们做下总结

1.一个Channel对应一个Unsafe，Unsafe处理底层操作，NioServerSocketChannel对应NioMessageUnsafe, NioSocketChannel对应NioByteUnsafe
2.inBound事件从head节点传播到tail节点，outBound事件从tail节点传播到head节点
3.异常传播只会往后传播，不像outBound事件一样会往前传播




