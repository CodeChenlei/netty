上两篇博文([一](http://www.jianshu.com/p/0d0eece6d467),[二](http://www.jianshu.com/p/467a9b41833e))已经描述了netty的reactor线程前两个步骤所处理的工作，在这里，我们还是用这张图片来回顾一下


简单总结一下reactor线程的总体框架
1. 轮询出IO事件
2. 处理IO事件
3. 处理任务队列

这篇文章，我们会深入剖析reactor线程的第三个步骤-处理任务队列，读完本篇文章，你将了解到netty的异步task机制，定时任务的处理逻辑，这些细节可以更好地帮助你写出netty应用


## netty中的task的常见使用场景
netty中任务队列承载着用户自定义task以及以及非reactor线程对channel的io事件的处理，我们取三种典型的使用场景来分析


### 用户自定义普通任务
```java
ctx.channel().eventLoop().execute(new Runnable() {
    @Override
    public void run() {
        //...
    }
});
```

我们跟进`execute`方法，看重点

```java
@Override
public void execute(Runnable task) {
    //...
    addTask(task);
    //...
}
```

`execute`方法调用 `addTask`方法
```java
protected void addTask(Runnable task) {
    // ...
    if (!offerTask(task)) {
        reject(task);
    }
}
```

然后调用`offerTask`方法，如果offer失败，那就调用`reject`方法，通过默认的 `RejectedExecutionHandler` 直接抛出异常
```java
final boolean offerTask(Runnable task) {
    // ...
    return taskQueue.offer(task);
}
```
跟到`offerTask`方法，基本上task就落地了，netty内部使用一个`taskQueue`将task保存起来，那么这个`taskQueue`又是何方神圣？

我们查看 `taskQueue` 定义的地方和被初始化的地方 

```java
private final Queue<Runnable> taskQueue;


taskQueue = newTaskQueue(this.maxPendingTasks);

@Override
protected Queue<Runnable> newTaskQueue(int maxPendingTasks) {
    // This event loop never calls takeTask()
    return PlatformDependent.newMpscQueue(maxPendingTasks);
}

```
我们发现 `taskQueue`在NioEventLoop中默认是mpsc队列，mpsc队列，即多生产者单消费者队列，netty使用mpsc，方便的将外部线程的task聚集，在reactor线程内部用单线程来串行执行，我们可以借鉴netty的任务执行模式来处理类似多线程数据上报，定时聚合的应用

这里，先说明一下，在本节讨论的任务场景中，所有代码的执行都是在reactor线程中的，所以，所有调用 `inEventLoop()` 的地方都返回true，既然都是在reactor线程中执行，那么其实这里的mpsc队列其实没有发挥真正的作用，mpsc大显身手的地方在第二种场景


### 非当前reactor线程调用channel的各种方法
```java
// non reactor thread
channel.write(...)
```
上面一种情况在push系统中比较常见，一般在业务线程里面，根据用户的标识，找到对应的channel，然后向该用户推送消息，就会进入到这种场景


关于channel.write()类方法的调用链，后面会单独拉出一篇文章来深入剖析，这里，我们只需要知道，最终write方法串至以下方法

> AbstractChannelHandlerContext.java
```java
private void write(Object msg, boolean flush, ChannelPromise promise) {
    // ...
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
外部线程在调用`write`的时候，`executor.inEventLoop()`会返回false，直接进入到else分支，将write封装成一个`WriteTask`, 然后调用 `safeExecute`方法
```java
private static void safeExecute(EventExecutor executor, Runnable runnable, ChannelPromise promise, Object msg) {
    // ...
    executor.execute(runnable);
    // ...
}
```
加下来的调用链就进入到第一种场景了，但是和第一种场景有个明显的区别就是，第一种场景的调用链的发起线程是reactor线程，第二种场景的调用链的发起线程是用户线程，用户线程可能会有很多个，但是前面所提到的 `taskQueue` 只属于reactor线程，多个线程并发写`taskQueue`可能出现线程同步问题，netty使用mpsc queue高效地解决了这个问题


### 用户自定义定时任务
```java
ctx.channel().eventLoop().schedule(new Runnable() {
    @Override
    public void run() {

    }
}, 60, TimeUnit.SECONDS);

```
跟进`schedule`方法
```java
public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
//...
    return schedule(new ScheduledFutureTask<Void>(
            this, command, null, ScheduledFutureTask.deadlineNanos(unit.toNanos(delay))));
} 
```
通过 `ScheduledFutureTask`, 将用户自定义任务再次包装成一个netty内部的任务

```java
<V> ScheduledFuture<V> schedule(final ScheduledFutureTask<V> task) {
    // ...
    scheduledTaskQueue().add(task);
    // ...
    return task;
}
```
到了这里，我们有点似曾相识，在非定时任务的处理中，netty通过一个mpsc队列将任务落地，这里，是否也有一个类似的队列来承载这类定时任务呢？带着这个疑问，我们继续向前

```java
Queue<ScheduledFutureTask<?>> scheduledTaskQueue() {
    if (scheduledTaskQueue == null) {
        scheduledTaskQueue = new PriorityQueue<ScheduledFutureTask<?>>();
    }
    return scheduledTaskQueue;
}
```
`scheduledTaskQueue()` 方法，会返回一个优先级队列，然后调用 `add` 方法将定时任务加入到队列中去，但是，这里为什么要使用优先级队列，而不需要考虑多线程的并发？

因为我们现在讨论的场景，调用链的发起方是reactor线程，不会存在多线程并发些 `scheduledTaskQueue` 的问题

但是，万一有的用户在reactor之外执行定时任务呢？虽然这类场景很少见，但是netty作为一个无比健壮的高性能io框架，必须要考虑到这种情况。

对此，netty的处理是，如果是在外部线程调用schedule，netty将添加定时任务的逻辑封装成一个普通的task，也就是第二种场景，这样，对 `PriorityQueue`的访问就变成单线程，即只有reactor线程

> 完整的schedule方法
```java
<V> ScheduledFuture<V> schedule(final ScheduledFutureTask<V> task) {
    if (inEventLoop()) {
        scheduledTaskQueue().add(task);
    } else {
        // 进入到场景二
        execute(new Runnable() {
            @Override
            public void run() {
                scheduledTaskQueue().add(task);
            }
        });
    }
    return task;
}
```
在阅读源码细节的过程中，我们应该多问几个为什么？比如这里，为什么定时任务要保存在优先级队列中，我们可以先不看源码，来思考一下优先级对列的特性

优先级队列是按一定的顺序来排列内部元素的，内部元素必须是可以比较的，联系到这里每个元素都是定时任务，那就说明定时任务是可以比较的，那么到底有哪些地方可以比较？

每个任务都有一个下一次执行的截止时间，截止时间是可以比较的，截止时间相同的情况下，任务添加的顺序也是可以比较的，阅读源码的过程中，一定要多和自己对话！

带着猜想，我们研究与一下`ScheduledFutureTask`，抽取出关键部分

```java
final class ScheduledFutureTask<V> extends PromiseTask<V> implements ScheduledFuture<V> {
    private static final AtomicLong nextTaskId = new AtomicLong();
    private static final long START_TIME = System.nanoTime();

    static long nanoTime() {
        return System.nanoTime() - START_TIME;
    }

    private final long id = nextTaskId.getAndIncrement();
    /* 0 - no repeat, >0 - repeat at fixed rate, <0 - repeat with fixed delay */
    private final long periodNanos;

    @Override
    public int compareTo(Delayed o) {
        //...
    }

    // 精简过的代码
    @Override
    public void run() {
    }
```


这里，我们一眼就找到了`compareTo` 方法，`cmd+u`跳转到实现的接口，发现就是`Comparable`接口
```java
public int compareTo(Delayed o) {
    if (this == o) {
        return 0;
    }

    ScheduledFutureTask<?> that = (ScheduledFutureTask<?>) o;
    long d = deadlineNanos() - that.deadlineNanos();
    if (d < 0) {
        return -1;
    } else if (d > 0) {
        return 1;
    } else if (id < that.id) {
        return -1;
    } else if (id == that.id) {
        throw new Error();
    } else {
        return 1;
    }
}
```
进入到方法体内部，我们发现，两个定时任务的比较，确实是先比较任务的截止时间，截止时间相同的情况下，再比较id，即任务添加的顺序，如果id再相同的话，就抛Error

这样，在执行定时任务的时候，就能保证最近截止时间的任务先执行

下面，我们再来看下netty是如何来保证各种定时任务的执行的，netty里面的定时任务分以下三种

1.若干时间后执行一次
2.每隔一段时间执行一次
3.每次执行结束，隔一定时间再执行一次

netty使用一个 `periodNanos` 来区分这三种情况，正如netty的注释那样
```java
/* 0 - no repeat, >0 - repeat at fixed rate, <0 - repeat with fixed delay */
    private final long periodNanos;
```
了解这些背景之后，我们来看下netty是如何来处理这三种不同类型的定时任务的

```java
public void run() {
    if (periodNanos == 0) {
        V result = task.call();
        setSuccessInternal(result);
    } else { 
        task.call();
        long p = periodNanos;
        if (p > 0) {
            deadlineNanos += p;
        } else {
            deadlineNanos = nanoTime() - p;
        }
            scheduledTaskQueue.add(this);
        }
    }
}
```

`if (periodNanos == 0)` 对应 `若干时间后执行一次` 的定时任务类型，执行完了该任务就结束了，进入到else代码块，先执行任务，然后再区分是那种类型的任务，`periodNanos`大于0，表示是以固定频率执行某个任务，和任务的执行时间无关，那么就设置该任务的下一次截止时间为上次的截止时间加上间隔时间`periodNanos`，否则，就是每次任务执行完毕之后，相隔多长时间之后再次执行，截止时间为当前时间加上间隔时间，`-p`就表示加上一个正的间隔时间，最后，将当前任务对象再次加入到队列，实现任务的不断执行

netty内部的任务添加机制了解地差不多之后，我们就可以查看reactor是如何来调度这些任务的

## reactor线程task的调度

首先，我们将目光转向最外层的外观代码

```java
runAllTasks(long timeoutNanos);
```
顾名思义，这行代码表示了尽量在一定的时间内，将所有的任务都取出来run一遍。`timeoutNanos` 表示该方法最多执行这么长时间，netty为什么要这么做？我们可以想一想，reactor线程如果在此停留的时间过长，那么将积攒许多的IO事件无法处理(见reactor线程的前面两个步骤)，最终导致大量客户端请求阻塞，因此，默认情况下，netty将控制内部队列的执行时间

好，我们继续跟进

```java
protected boolean runAllTasks(long timeoutNanos) {
    fetchFromScheduledTaskQueue();
    Runnable task = pollTask();
    //...

    final long deadline = ScheduledFutureTask.nanoTime() + timeoutNanos;
    long runTasks = 0;
    long lastExecutionTime;
    for (;;) {
        safeExecute(task);
        runTasks ++;
        if ((runTasks & 0x3F) == 0) {
            lastExecutionTime = ScheduledFutureTask.nanoTime();
            if (lastExecutionTime >= deadline) {
                break;
            }
        }

        task = pollTask();
        if (task == null) {
            lastExecutionTime = ScheduledFutureTask.nanoTime();
            break;
        }
    }

    afterRunningAllTasks();
    this.lastExecutionTime = lastExecutionTime;
    return true;
}
```

这段代码便是reactor执行task的所有逻辑，可以拆解成下面几个步骤

1. 从scheduledTaskQueue转移定时任务到taskQueue(mpsc queue)
2. 计算本次任务循环的截止时间
3. 执行任务
4. 收尾

按照这个步骤，我们一步步来分析下
### 从scheduledTaskQueue转移定时任务到taskQueue(mpsc queue)

首先调用  `fetchFromScheduledTaskQueue()`方法，将到期的定时任务加载到mpsc queue里面

```java
private boolean fetchFromScheduledTaskQueue() {
    long nanoTime = AbstractScheduledEventExecutor.nanoTime();
    Runnable scheduledTask  = pollScheduledTask(nanoTime);
    while (scheduledTask != null) {
        if (!taskQueue.offer(scheduledTask)) {
            // No space left in the task queue add it back to the scheduledTaskQueue so we pick it up again.
            scheduledTaskQueue().add((ScheduledFutureTask<?>) scheduledTask);
            return false;
        }
        scheduledTask  = pollScheduledTask(nanoTime);
    }
    return true;
}
```
可以看到，netty在把任务从scheduledTaskQueue转移到taskQueue的时候还是非常小心的，当taskQueue无法offer的时候，需要把从scheduledTaskQueue里面取出来的任务重新添加回去

从scheduledTaskQueue从拉取一个定时任务的逻辑如下，传入的参数`nanoTime`为当前时间(其实是当前纳秒减去`ScheduledFutureTask`类被加载的纳秒个数)
```java
protected final Runnable pollScheduledTask(long nanoTime) {
    assert inEventLoop();

    Queue<ScheduledFutureTask<?>> scheduledTaskQueue = this.scheduledTaskQueue;
    ScheduledFutureTask<?> scheduledTask = scheduledTaskQueue == null ? null : scheduledTaskQueue.peek();
    if (scheduledTask == null) {
        return null;
    }

    if (scheduledTask.deadlineNanos() <= nanoTime) {
        scheduledTaskQueue.remove();
        return scheduledTask;
    }
    return null;
}
```
可以看到，每次 `pollScheduledTask` 的时候，只有当当前任务的截止时间已经到了，才会取出来

### 计算本次任务循环的截止时间

```java
     Runnable task = pollTask();
     //...
    final long deadline = ScheduledFutureTask.nanoTime() + timeoutNanos;
    long runTasks = 0;
    long lastExecutionTime;
```
这一步将取出第一个任务，用reactor线程传入的超时时间 `timeoutNanos` 来计算出当前任务循环的deadline，并且使用了`runTasks`，`lastExecutionTime`来时刻记录任务的状态
    
### 循环执行任务

```java
for (;;) {
    safeExecute(task);
    runTasks ++;
    if ((runTasks & 0x3F) == 0) {
        lastExecutionTime = ScheduledFutureTask.nanoTime();
        if (lastExecutionTime >= deadline) {
            break;
        }
    }

    task = pollTask();
    if (task == null) {
        lastExecutionTime = ScheduledFutureTask.nanoTime();
        break;
    }
}
```
首先调用`safeExecute`来确保任务安全执行，忽略任何异常
```java
protected static void safeExecute(Runnable task) {
    try {
        task.run();
    } catch (Throwable t) {
        logger.warn("A task raised an exception. Task: {}", task, t);
    }
}
```
然后将已运行任务 `runTasks` 加一，每隔`0x3F`任务，即每执行完64个任务之后，判断当前时间是否超过本次reactor任务循环的截止时间了，如果超过，那就break掉，如果没有超过，那就继续指定，可以看到，netty对性能的优化考虑地相当的周到，假设netty任务队列里面如果有海量小任务，如果每次都要执行完任务都要判断一下是否到截止时间，那么效率是比较低下的

### 收尾
```java
afterRunningAllTasks();
this.lastExecutionTime = lastExecutionTime;
```
收尾工作很简单，调用一下 `afterRunningAllTasks` 方法
```java
/**
 * Invoked before returning from {@link #runAllTasks()} and {@link #runAllTasks(long)}.
 */
@UnstableApi
protected void afterRunningAllTasks() { }
```
`@UnstableApi`标注的描述如下
>  Indicates a public API that can change at any time (even in minor/bugfix releases).

不过多解释，你自己可以写一个类继承`NioEventLoop`，覆盖该方法，在每次reactor执行完task loop之后做一些定制化操作

`this.lastExecutionTime = lastExecutionTime;`简单记录一下任务执行的时间，搜了一下该field的引用，发现这个field并没有使用过，只是每次不停地赋值，赋值，赋值...，改天再去向netty官方提个issue...


如果你读到这觉得很轻松，那么恭喜你，你对netty的task机制已经非常熟练了，也恭喜一下我，把这些机制给你将清楚了，我们最后再来一次总结，以tips的方式

* 当前reactor线程调用当前eventLoop执行任务，直接执行，否则，添加到任务队列稍后执行
* netty内部的任务分为普通任务和定时任务，分别落地到MpscQueue和PriorityQueue
* netty每次执行任务循环之前，会将已经到期的定时任务从PriorityQueue转移到MpscQueue
* netty每隔64个任务检查一下是否该退出任务循环

完。