## 前言
在前面的文章中，我们已经详细阐述了事件和异常传播在netty中的实现,([netty源码分析之pipeline(一)](http://www.jianshu.com/p/6efa9c5fa702),[netty源码分析之pipeline(二)](http://www.jianshu.com/p/087b7e9a27a2))，其中有一类事件我们在实际编码中用得最多，那就是 `write`或者`writeAndFlush`，也就是我们今天的主要内容

## 主要内容
本文会分以下几个部分阐述一个java对象，最后是如何转变成字节流，写到网络中去的

1. pipeline中的标准链表结构
2. java对象编码过程
3. write：写队列
4. flush：刷新写队列
5. writeAndFlush: 写队列并刷新


### pipeline中的标准链表结构
一个标准的pipeline链式结构如下(我们省去了异常处理Handler)
[1.png]

数据从head节点流入，先拆包，然后解码成业务对象，最后经过业务Handler处理，调用write，将结果对象写出去。而写的过程先通过tail节点，然后通过encoder节点将对象编码成ByteBuf，最后将该ByteBuf对象传递到head节点，调用底层的Unsafe写到jdk底层管道


### java对象编码过程
为什么我们在pipeline中添加了encoder节点，java对象就转换成netty可以处理的ByteBuf，写到管道里？

我们先看下调用`write`的code

> BusinessHandler
```java
 protected void channelRead0(ChannelHandlerContext ctx, Request request) throws Exception {
    Response response = doBusiness(request);
    
    if (response != null) {
        ctx.channel().write(response);
    }
 }
```

业务处理器接受到请亲之后，做一些处理，返回一个`Response`，然后，[response在pipeline中传递]()，落到 `Encoder`节点，下面是 `Encoder` 的处理流程

> Encoder

```java
public class Encoder extends MessageToByteEncoder<Response> {
    @Override
    protected void encode(ChannelHandlerContext ctx, Response response, ByteBuf out) throws Exception {
        out.writeByte(response.getVersion());
        out.writeInt(4 + response.getData().length);
        out.writeBytes(response.getData());
    }
}
```

Encoder的处理流程很简单，按照简单自定义协议，将java对象 `Response` 的写到传入的参数 `out`中，这个`out`到底是什么？

为了回答这个问题，我们需要了解到 `Response`  对象，从 `BusinessHandler` 传入到 `MessageToByteEncoder`的时候，首先是传入到 `write` 方法

> MessageToByteEncoder

```java
@Override
public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
    ByteBuf buf = null;
    try {
        // 判断当前Handelr是否能处理写入的消息
        if (acceptOutboundMessage(msg)) {
            @SuppressWarnings("unchecked")
            // 强制换换
            I cast = (I) msg;
            // 分配一段ButeBuf
            buf = allocateBuffer(ctx, cast, preferDirect);
            try {
            // 调用encode，这里就调回到  `Encoder` 这个Handelr中    
                encode(ctx, cast, buf);
            } finally {
                // 既然自定义java对象转换成ByteBuf了，那么这个对象就已经无用了，释放掉
                // (当传入的msg类型是ByteBuf的时候，就不需要自己手动释放了)
                ReferenceCountUtil.release(cast);
            }
            // 如果buf中写入了数据，就把buf传到下一个节点
            if (buf.isReadable()) {
                ctx.write(buf, promise);
            } else {
            // 否则，释放buf，将空数据传到下一个节点    
                buf.release();
                ctx.write(Unpooled.EMPTY_BUFFER, promise);
            }
            buf = null;
        } else {
            // 如果当前节点不能处理传入的对象，直接扔给下一个节点处理
            ctx.write(msg, promise);
        }
    } catch (EncoderException e) {
        throw e;
    } catch (Throwable e) {
        throw new EncoderException(e);
    } finally {
        // 当buf在pipeline中处理完之后，释放
        if (buf != null) {
            buf.release();
        }
    }
}
```
其实，这一小节的内容，在前面的博文中，已经提到过，这里，我们详细阐述一下Encoder是如何处理传入的java对象的

1.判断当前Handler是否能处理写入的消息，如果能处理，进入下面的流程，否则，直接扔给下一个节点处理
2.将对象强制转换成`Encoder`可以处理的 `Response`对象
3.分配一个ByteBuf
4.调用encoder，即进入到 `Encoder` 的 `encode`方法，该方法是用户代码，用户将数据写入ByteBuf
5.既然自定义java对象转换成ByteBuf了，那么这个对象就已经无用了，释放掉，(当传入的msg类型是ByteBuf的时候，就不需要自己手动释放了)
6.如果buf中写入了数据，就把buf传到下一个节点，否则，释放buf，将空数据传到下一个节点
7.最后，当buf在pipeline中处理完之后，释放节点

总结一点就是，`Encoder`节点分配一个ByteBuf，调用`encode`方法，将java对象根据自定义协议写入到ByteBuf，然后再把ByteBuf传入到下一个节点，在我们的例子中，最终会传入到head节点

> HeadContext

```java
public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
    unsafe.write(msg, promise);
}
```
这里的msg就是前面在`Encoder`节点中，载有java对象数据的自定义ByteBuf对象，进入下一节

### write：写队列
> AbstractChannel

```java
@Override
public final void write(Object msg, ChannelPromise promise) {
    assertEventLoop();

    ChannelOutboundBuffer outboundBuffer = this.outboundBuffer;

    int size;
    try {
        msg = filterOutboundMessage(msg);
        size = pipeline.estimatorHandle().size(msg);
        if (size < 0) {
            size = 0;
        }
    } catch (Throwable t) {
        safeSetFailure(promise, t);
        ReferenceCountUtil.release(msg);
        return;
    }

    outboundBuffer.addMessage(msg, size, promise);
}
```

1.首先，调用 `assertEventLoop` 确保该方法的调用是在reactor线程中，关于reactor线程可以查看我前面的文章
2.然后，调用 `filterOutboundMessage()` 方法，将待写入的对象过滤，把非`ByteBuf`对象和`FileRegion`过滤，把所有的非直接内存转换成直接内存`DirectBuffer`

> AbstractNioByteChannel

```java
@Override
protected final Object filterOutboundMessage(Object msg) {
    if (msg instanceof ByteBuf) {
        ByteBuf buf = (ByteBuf) msg;
        if (buf.isDirect()) {
            return msg;
        }

        return newDirectBuffer(buf);
    }

    if (msg instanceof FileRegion) {
        return msg;
    }

    throw new UnsupportedOperationException(
            "unsupported message type: " + StringUtil.simpleClassName(msg) + EXPECTED_TYPES);
}
```
3.接下来，估算出需要写入的ByteBuf的size
4.最后，调用 `ChannelOutboundBuffer` 的`addMessage(msg, size, promise)` 方法，所以，接下来，我们需要重点看一下这个方法干了什么事情
 

> ChannelOutboundBuffer

```java
public void addMessage(Object msg, int size, ChannelPromise promise) {
    // 创建一个待写出的消息节点
    Entry entry = Entry.newInstance(msg, size, total(msg), promise);
    if (tailEntry == null) {
        flushedEntry = null;
        tailEntry = entry;
    } else {
        Entry tail = tailEntry;
        tail.next = entry;
        tailEntry = entry;
    }
    if (unflushedEntry == null) {
        unflushedEntry = entry;
    }

    incrementPendingOutboundBytes(size, false);
}
```
想要理解上面这段代码，必须得掌握写缓存中的几个消息指针，如下图

[2.png]

ChannelOutboundBuffer 里面的数据结构是一个单链表结构，每个节点是一个 `Entry`，`Entry` 里面包含了待写出`ByteBuf` 以及消息回调 `promise`，下面分别是三个指针的作用

1.flushedEntry 指针表示第一个被写到操作系统Socket缓冲区中的节点
2.unFlushedEntry 指针表示第一个未被写入到操作系统Socket缓冲区中的节点
3.tailEntry指针表示ChannelOutboundBuffer缓冲区的最后一个节点

初次调用 `addMessage` 之后，各个指针的情况为
[3.png]

`fushedEntry`指向空，`unFushedEntry`和 `tailEntry` 都指向新加入的节点
 
第二次调用 `addMessage`之后，各个指针的情况为

[4.png]

第n次调用 `addMessage`之后，各个指针的情况为

[5.png]

可以看到，调用n此`addMessage`，flushedEntry指针一直指向NULL，表示现在还未有节点写出到Socket缓冲区，而`unFushedEntry`之后有n个节点，表示当前还有n个节点尚未写出到Socket缓冲区中去

### flush：刷新写队列
不管调用`channel.flush()`，还是`ctx.flush()`，最终都会落地到pipeline中的head节点

> HeadContext

```java
@Override
public void flush(ChannelHandlerContext ctx) throws Exception {
    unsafe.flush();
}
```

之后进入到`AbstractUnsafe`

> AbstractUnsafe

```java
public final void flush() {
   assertEventLoop();

   ChannelOutboundBuffer outboundBuffer = this.outboundBuffer;
   if (outboundBuffer == null) {
       return;
   }

   outboundBuffer.addFlush();
   flush0();
}
```

flush方法中，先调用

> ChannelOutboundBuffer

```java
public void addFlush() {
    Entry entry = unflushedEntry;
    if (entry != null) {
        if (flushedEntry == null) {
            flushedEntry = entry;
        }
        do {
            flushed ++;
            if (!entry.promise.setUncancellable()) {
                int pending = entry.cancel();
                decrementPendingOutboundBytes(pending, false, true);
            }
            entry = entry.next;
        } while (entry != null);
        unflushedEntry = null;
    }
}
```

可以结合前面的图来看，首先拿到 `unflushedEntry` 指针，然后将 `flushedEntry` 指向`unflushedEntry`所指向的节点，调用完毕之后，三个指针的情况如下所示


[6.png]


接下来，调用 `flush0();`

> AbstractUnsafe
 
```java
protected void flush0() {
    doWrite(outboundBuffer);
}
```
发现这里的核心代码就一个 doWrite，继续跟

> AbstractNioByteChannel

```java
protected void doWrite(ChannelOutboundBuffer in) throws Exception {
    int writeSpinCount = -1;

    boolean setOpWrite = false;
    for (;;) {
        // 拿到第一个需要flush的节点的数据
        Object msg = in.current();

        if (msg instanceof ByteBuf) {
            // 强转为ByteBuf，若发现没有数据可读，直接删除该节点
            ByteBuf buf = (ByteBuf) msg;

            boolean done = false;
            long flushedAmount = 0;
            // 拿到自旋锁迭代次数
            if (writeSpinCount == -1) {
                writeSpinCount = config().getWriteSpinCount();
            }
            // 自旋，将当前节点写出
            for (int i = writeSpinCount - 1; i >= 0; i --) {
                int localFlushedAmount = doWriteBytes(buf);
                if (localFlushedAmount == 0) {
                    setOpWrite = true;
                    break;
                }

                flushedAmount += localFlushedAmount;
                if (!buf.isReadable()) {
                    done = true;
                    break;
                }
            }

            in.progress(flushedAmount);

            // 写完之后，将当前节点删除
            if (done) {
                in.remove();
            } else {
                break;
            }
        } 
    }
}
```

这里略微有点复杂，我们分析一下

1.第一步，调用`current()`先拿到第一个需要flush的节点的数据

> ChannelOutBoundBuffer

```java
public Object current() {
    Entry entry = flushedEntry;
    if (entry == null) {
        return null;
    }

    return entry.msg;
}
```

2.第二步,拿到自旋锁的迭代次数

```java
if (writeSpinCount == -1) {
    writeSpinCount = config().getWriteSpinCount();
}
```

关于为什么要用自旋锁，netty的文档已经解释得很清楚，这里不过多解释

> ChannelConfig

```java
/**
 * Returns the maximum loop count for a write operation until
 * {@link WritableByteChannel#write(ByteBuffer)} returns a non-zero value.
 * It is similar to what a spin lock is used for in concurrency programming.
 * It improves memory utilization and write throughput depending on
 * the platform that JVM runs on.  The default value is {@code 16}.
 */
int getWriteSpinCount();
```

3.自旋的方式将ByteBuf写出到jdk nio的Channel

```java
for (int i = writeSpinCount - 1; i >= 0; i --) {
    int localFlushedAmount = doWriteBytes(buf);
    if (localFlushedAmount == 0) {
        setOpWrite = true;
        break;
    }

    flushedAmount += localFlushedAmount;
    if (!buf.isReadable()) {
        done = true;
        break;
    }
}
```

`doWriteBytes` 方法跟进去

```java
protected int doWriteBytes(ByteBuf buf) throws Exception {
    final int expectedWrittenBytes = buf.readableBytes();
    return buf.readBytes(javaChannel(), expectedWrittenBytes);
}
```
我们发现，出现了 `javaChannel()`，表明已经进入到了jdk nio Channel的领域，有关netty中ByteBuf的介绍不打算在这里展开



4.删除该节点

节点的数据已经写入完毕，接下来就需要删除该节点

> ChannelOutBoundBuffer

```java
public boolean remove() {
    Entry e = flushedEntry;
    Object msg = e.msg;

    ChannelPromise promise = e.promise;
    int size = e.pendingSize;

    removeEntry(e);

    if (!e.cancelled) {
        ReferenceCountUtil.safeRelease(msg);
        safeSuccess(promise);
    }

    // recycle the entry
    e.recycle();

    return true;
}
```

首先拿到当前被flush掉的节点(flushedEntry所指)，然后拿到该节点的回调对象 `ChannelPromise`, 调用 `removeEntry()`方法移除该节点

```java
private void removeEntry(Entry e) {
    if (-- flushed == 0) {
        flushedEntry = null;
        if (e == tailEntry) {
            tailEntry = null;
            unflushedEntry = null;
        }
    } else {
        flushedEntry = e.next;
    }
}
```

这里的remove是逻辑移除，只是将flushedEntry指针移到下个节点，调用完毕之后，节点图示如下


[7.png]


随后，释放该节点数据的内存，调用 `safeSuccess` 进行回调，用户代码可以在回调里面做一些记录，下面是一段Example

> 用户代码
```java
ctx.write(xx).addListener(new GenericFutureListener<Future<? super Void>>() {
    @Override
    public void operationComplete(Future<? super Void> future) throws Exception {
       // 回调 
    }
})
```

最后，调用 `recycle`方法，将当前节点回收



### writeAndFlush: 写队列并刷新

理解了write和flush这两个过程，`writeAndFlush` 也就不难了

writeAndFlush在某个Handler中被调用之后，最终会落到 `TailContext` 节点，见 [netty源码分析之pipeline(二)](http://www.jianshu.com/p/087b7e9a27a2)

> TailContext

```java
public final ChannelFuture writeAndFlush(Object msg) {
    return tail.writeAndFlush(msg);
}

public ChannelFuture writeAndFlush(Object msg) {
    return writeAndFlush(msg, newPromise());
}

public ChannelFuture writeAndFlush(Object msg, ChannelPromise promise) {
    write(msg, true, promise);

    return promise;
}

private void write(Object msg, boolean flush, ChannelPromise promise) {
    AbstractChannelHandlerContext next = findContextOutbound();
    EventExecutor executor = next.executor();
    if (executor.inEventLoop()) {
        if (flush) {
            next.invokeWriteAndFlush(m, promise);
        } else {
            next.invokeWrite(m, promise);
        }
    } 
}
```

可以看到，最终，通过一个boolean变量，表示是调用 `invokeWriteAndFlush`，还是 `invokeWrite`，`invokeWrite`便是我们上文中的`write`过程


```java
private void invokeWriteAndFlush(Object msg, ChannelPromise promise) {
    invokeWrite0(msg, promise);
    invokeFlush0();
}
```

可以看到，最终调用的底层方法和单独调用 `write` 和 `flush` 是一样的


```java
private void invokeWrite(Object msg, ChannelPromise promise) {
        invokeWrite0(msg, promise);
}

private void invokeFlush(Object msg, ChannelPromise promise) {
        invokeFlush0(msg, promise);
}
```

由此看来，`invokeWriteAndFlush`基本等价于`write`方法之后再来一次`flush` 


## 总结
1.pipeline中的编码器原理是创建一个ByteBuf,将java对象转换为ByteBuf，然后再把ByteBuf继续向前传递
2.调用write方法并没有将数据写到Socket缓冲区中，而是写到了一个单向链表的数据结构中，flush才是真正的写出
3.writeAndFlush等价于先将数据写到netty的缓冲区，再将netty缓冲区中的数据写到Socket缓冲区中，写的过程与并发编程类似，用自旋锁保证写成功
4.netty中的缓冲区中的ByteBuf为DirectByteBuf