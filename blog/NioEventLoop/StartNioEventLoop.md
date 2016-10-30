Server的事件循环线程在哪开启？

我们目光转向 `ServerBootstrap` 类的bind方法
```java
public class AbstractBootstrap {
    public ChannelFuture bind(int inetPort) {
        return bind(new InetSocketAddress(inetPort));
    }
    
    public ChannelFuture bind(SocketAddress localAddress) {
        // ... 
        return doBind(localAddress);
    }
    
    private ChannelFuture doBind(final SocketAddress localAddress) {
        final ChannelFuture regFuture = initAndRegister();
        // ... 
    }
    
    final ChannelFuture initAndRegister() {
        // ...
        // 这里的group返回的是NioEventLoopGroup(why?netty中的Bootstrap)，于是接下来我们需要跳转到NioEventLoopGroup的register方法
        ChannelFuture regFuture = config().group().register(channel);
        // ... 
    }
}
```

发现 `NioEventLoopGroup的` 继承了 `MultithreadEventLoopGroup` 的 `register` 方法
```java
public class MultithreadEventLoopGroup {
    @Override
    public ChannelFuture register(Channel channel) {
        return next().register(channel);
    }
}
```
我们发现`MultithreadEventLoopGroup`把 `register` 方法委托到 `next()` 返回的对象，那么这个对象又是啥呢？(why?netty中的事件循环组和事件循环的关系以及如何选择一个事件循环)

接下来我们就跳到 `SingleThreadEventLoop` 的 `register` 方法
```java
public abstract class SingleThreadEventLoop extends SingleThreadEventExecutor implements EventLoop {
   @Override
   public ChannelFuture register(Channel channel) {
       return register(new DefaultChannelPromise(channel, this));
   }

   @Override
   public ChannelFuture register(final ChannelPromise promise) {
       ObjectUtil.checkNotNull(promise, "promise");
       promise.channel().unsafe().register(this, promise);
       return promise;
   } 
}
```

最终我们发现这里又 `register` 方法委托到 `unsafe()` 返回的对象，在这里简单介绍一下，netty 里面每个`Channel`对象都会有一个
`Unsafe`对象和TA一一对应(why?netty里面channel对应的unsafe))，这里返回其实是`Unsafe`对象的一个子类，叫`NioMessageUnsafe`
进入到这个类，我们发现他继承了`AbstractUnsafe`的register方法，于是我们继续跟进

```java
protected abstract class AbstractUnsafe implements Unsafe {
    @Override
    public final void register(EventLoop eventLoop, final ChannelPromise promise) {
    // ...
       if (eventLoop.inEventLoop()) {
           register0(promise);
       } else {
           try {
               eventLoop.execute(new Runnable() {
                   @Override
                   public void run() {
                       register0(promise);
                   }
               });
           } catch (Throwable t) {
               // ...
           }
       }
    } 
}
```
首先判断当前线程是否是事件循环的线程，刚启动的时候，事件循环还未创建（why?netty中的事件循环的线程到底是如何创建出来的），所以进入到 `eventLoop.execute(Runnable command)`,
于是乎我们又不得不跳转到 `eventLoop`这个类对应的方法中去，至于为啥这里的 `eventLoop` 是 `NioEventLoop`, (why?netty是如何使用到NioEventLoop这个类的)，好，我们继续
`NioEventLoop`继承了 `SingleThreadEventExecutor` 的 `execute` 方法

```java
public abstract class SingleThreadEventExecutor extends AbstractScheduledEventExecutor implements OrderedEventExecutor {
    @Override
    public void execute(Runnable task) {
        // ...
        // 这里没有在事件循环中
        boolean inEventLoop = inEventLoop();
        if (inEventLoop) {
            addTask(task);
        } else {
            // 开启线程
            startThread();
            // ...
        }
        // ...
    }
    
    private void startThread() {
        // ...
        doStartThread();
        // ...
    }
   
    // 这个方法我们详细介绍一下
    private void doStartThread() {
        // 1. 一个事件循环使用一个`thread` field 保存TA所对应的线程
        assert thread == null;
        // 这里的executor到底是啥，(见这篇文章)
        executor.execute(new Runnable() {
            @Override
            public void run() {
                // 2. 一旦这个线程被启动，那么就将该线程保存起来
                thread = Thread.currentThread();
                // ...
                try {
                    // 3. 这里是最重要的方法，线程启动起来之后，最终会调用到这个方法,我们接下来就跟进, 后面的方法暂时
                    SingleThreadEventExecutor.this.run();
                    // ...
                } catch (Throwable t) {
                    // ...
                } finally {
                    // ...
                }
            }
        });
    } 
}
```

最终我们发现，这个方法调回了 `NioEventLoop` 的`run`方法

```java
public class NioEventLoop {
        @Override
        protected void run() {
            for (;;) {
                // ...
                    final int ioRatio = this.ioRatio;
                    if (ioRatio == 100) {
                        try {
                            processSelectedKeys();
                        } finally {
                            // Ensure we always run tasks.
                            runAllTasks();
                        }
                    } else {
                        final long ioStartTime = System.nanoTime();
                        try {
                            processSelectedKeys();
                        } finally {
                            // Ensure we always run tasks.
                            final long ioTime = System.nanoTime() - ioStartTime;
                            runAllTasks(ioTime * (100 - ioRatio) / ioRatio);
                        }
                    }
                // ...
            }
        }
}

```
到了这里，我们终于发现有个死循环和事件循环这个词对的上了，这里主要就是根据用户设置的 `ioRatio` 来分配cpu和io的时间，不管比率设置得如何，netty处理的过程都是先处理一段时间的io，再处理一段时间的task，所以，叫事件循环！
最后，关于netty线程个数的设置，可以(why?netty中事件循环线程的设置)

总结：netty里面的Server端的事件循环是通过bind方法，层层递进，启动起来的