如果你对netty的reactor线程不了解，建议先看下上一篇文章[netty源码分析之揭开reactor线程的面纱（一）](http://www.jianshu.com/p/0d0eece6d467)，这里再把reactor中的三个步骤的图贴一下


![reactor线程](http://upload-images.jianshu.io/upload_images/1357217-8b450be2e4982081.png?imageMogr2/auto-orient/strip%7CimageView2/2/w/1240)

我们已经了解到netty reactor线程的第一步是轮询出注册在selector上面的IO事件（select），那么接下来就要处理这些IO事件（process selected keys），本篇文章我们将一起来探讨netty处理IO事件的细节

我们进入到reactor线程的 `run` 方法，找到处理IO事件的代码，如下

```java
processSelectedKeys()；
```

跟进去

```java
private void processSelectedKeys() {
    if (selectedKeys != null) {
        processSelectedKeysOptimized(selectedKeys.flip());
    } else {
        processSelectedKeysPlain(selector.selectedKeys());
    }
}
```

我们发现处理IO事件，netty有两种选择，从名字上看，一种是处理优化过的selectedKeys，一种是正常的处理

我们对优化过的selectedKeys的处理稍微展开一下，看看netty是如何优化的，我们查看 `selectedKeys` 被引用过的地方，有如下代码

```java
private SelectedSelectionKeySet selectedKeys;

private Selector NioEventLoop.openSelector() {
    //...
    final SelectedSelectionKeySet selectedKeySet = new SelectedSelectionKeySet();
    // selectorImplClass -> sun.nio.ch.SelectorImpl
    Field selectedKeysField = selectorImplClass.getDeclaredField("selectedKeys");
    Field publicSelectedKeysField = selectorImplClass.getDeclaredField("publicSelectedKeys");
    selectedKeysField.setAccessible(true);
    publicSelectedKeysField.setAccessible(true);
    selectedKeysField.set(selector, selectedKeySet);
    publicSelectedKeysField.set(selector, selectedKeySet);
    //...
    selectedKeys = selectedKeySet;
}
```
首先，selectedKeys是一个 `SelectedSelectionKeySet` 类对象，在`NioEventLoop` 的 `openSelector` 方法中创建，之后就通过反射将selectedKeys与 `sun.nio.ch.SelectorImpl` 中的两个field绑定

`sun.nio.ch.SelectorImpl` 中我们可以看到，这两个field其实是两个HashSet

```java
// Public views of the key sets
private Set<SelectionKey> publicKeys;             // Immutable
private Set<SelectionKey> publicSelectedKeys;     // Removal allowed, but not addition
protected SelectorImpl(SelectorProvider sp) {
    super(sp);
    keys = new HashSet<SelectionKey>();
    selectedKeys = new HashSet<SelectionKey>();
    if (Util.atBugLevel("1.4")) {
        publicKeys = keys;
        publicSelectedKeys = selectedKeys;
    } else {
        publicKeys = Collections.unmodifiableSet(keys);
        publicSelectedKeys = Util.ungrowableSet(selectedKeys);
    }
}
```


selector在调用`select()`族方法的时候，如果有IO事件发生，就会往里面的两个field中塞相应的`selectionKey`(具体怎么塞有待研究)，即相当于往一个hashSet中add元素，既然netty通过反射将jdk中的两个field替换掉，那我们就应该意识到是不是netty自定义的`SelectedSelectionKeySet`在`add`方法做了某些优化呢？

带着这个疑问，我们进入到 `SelectedSelectionKeySet` 类中探个究竟

```java
final class SelectedSelectionKeySet extends AbstractSet<SelectionKey> {

    private SelectionKey[] keysA;
    private int keysASize;
    private SelectionKey[] keysB;
    private int keysBSize;
    private boolean isA = true;

    SelectedSelectionKeySet() {
        keysA = new SelectionKey[1024];
        keysB = keysA.clone();
    }

    @Override
    public boolean add(SelectionKey o) {
        if (o == null) {
            return false;
        }

        if (isA) {
            int size = keysASize;
            keysA[size ++] = o;
            keysASize = size;
            if (size == keysA.length) {
                doubleCapacityA();
            }
        } else {
            int size = keysBSize;
            keysB[size ++] = o;
            keysBSize = size;
            if (size == keysB.length) {
                doubleCapacityB();
            }
        }

        return true;
    }

    private void doubleCapacityA() {
        SelectionKey[] newKeysA = new SelectionKey[keysA.length << 1];
        System.arraycopy(keysA, 0, newKeysA, 0, keysASize);
        keysA = newKeysA;
    }

    private void doubleCapacityB() {
        SelectionKey[] newKeysB = new SelectionKey[keysB.length << 1];
        System.arraycopy(keysB, 0, newKeysB, 0, keysBSize);
        keysB = newKeysB;
    }

    SelectionKey[] flip() {
        if (isA) {
            isA = false;
            keysA[keysASize] = null;
            keysBSize = 0;
            return keysA;
        } else {
            isA = true;
            keysB[keysBSize] = null;
            keysASize = 0;
            return keysB;
        }
    }

    @Override
    public int size() {
        if (isA) {
            return keysASize;
        } else {
            return keysBSize;
        }
    }

    @Override
    public boolean remove(Object o) {
        return false;
    }

    @Override
    public boolean contains(Object o) {
        return false;
    }

    @Override
    public Iterator<SelectionKey> iterator() {
        throw new UnsupportedOperationException();
    }
}
```
该类其实很简单，继承了 `AbstractSet`，说明该类可以当作一个set来用，但是底层使用两个数组来交替使用，在`add`方法中，判断当前使用哪个数组，找到对应的数组，然后经历下面三个步骤
1.将SelectionKey塞到该数组的逻辑尾部
2.更新该数组的逻辑长度+1
3.如果该数组的逻辑长度等于数组的物理长度，就将该数组扩容

我们可以看到，待程序跑过一段时间，等数组的长度足够长，每次在轮询到nio事件的时候，netty只需要O(1)的时间复杂度就能将 `SelectionKey` 塞到 set中去，而jdk底层使用的hashSet需要O(lgn)的时间复杂度

这里关于为何使用两个数组循环交替使用，其实我也是很费解，思考了很久，查找`SelectedSelectionKeySet` 所有使用的地方，我觉得使用一个数组就能够达到优化目的，并且不用每次都判断使用哪个数组，所以对于该问题，我提了一个issue给netty官方，官方也给出了答复说会跟进，issue链接：https://github.com/netty/netty/issues/6058#

关于netty对`SelectionKeySet`的优化我们暂时就跟这么多，下面我们继续跟netty对IO事件的处理，转到`processSelectedKeysOptimized`

```java
 private void processSelectedKeysOptimized(SelectionKey[] selectedKeys) {
     for (int i = 0;; i ++) {
         // 1.取出IO事件以及对应的channel
         final SelectionKey k = selectedKeys[i];
         if (k == null) {
             break;
         }
         selectedKeys[i] = null;
         final Object a = k.attachment();
         // 2.处理该channel
         if (a instanceof AbstractNioChannel) {
             processSelectedKey(k, (AbstractNioChannel) a);
         } else {
             NioTask<SelectableChannel> task = (NioTask<SelectableChannel>) a;
             processSelectedKey(k, task);
         }
         // 3.判断是否该再来次轮询
         if (needsToSelectAgain) {
             for (;;) {
                 i++;
                 if (selectedKeys[i] == null) {
                     break;
                 }
                 selectedKeys[i] = null;
             }
             selectAgain();
             selectedKeys = this.selectedKeys.flip();
             i = -1;
         }
     }
 }
```
我们可以将该过程分为以下三个步骤
> 1.取出IO事件以及对应的netty channel类

这里其实也能体会到优化过的 `SelectedSelectionKeySet` 的好处，遍历的时候遍历的是数组，相对jdk原生的`HashSet`效率有所提高

拿到当前SelectionKey之后，将`selectedKeys[i]`置为null，这里简单解释一下这么做的理由：想象一下这种场景，假设一个NioEventLoop平均每次轮询出N个IO事件，高峰期轮询出3*N个事件，那么`selectedKeys`的物理长度要大于等于3*N，如果每次处理这些key，不置`selectedKeys[i]`为空，那么高峰期一过，这些保存在数组尾部的`selectedKeys[i]`对应的`SelectionKey`将一直无法被回收，`SelectionKey`对应的对象可能不大，但是要知道，它可是有attachment的，这里的attachment具体是什么下面会讲到，但是有一点我们必须清楚，attachment可能很大，这样一来，这些元素是GC root可达的，很容易造成gc不掉，内存泄漏就发生了

这个bug在 `4.0.19.Final`版本中被修复，建议使用netty的项目升级到最新版本^^


> 2.处理该channel

拿到对应的attachment之后，netty做了如下判断
```java
if (a instanceof AbstractNioChannel) {
    processSelectedKey(k, (AbstractNioChannel) a);
} 
```
源码读到这，我们需要思考为啥会有这么一条判断，凭什么说attachment可能会是 `AbstractNioChannel`对象？

我们的思路应该是找到底层selector, 然后在selector调用register方法的时候，看一下注册到selector上的对象到底是什么鬼，我们使用intellij的全局搜索引用功能，最终在 `AbstractNioChannel`中搜索到如下方法


```java
protected void doRegister() throws Exception {
    // ...
    selectionKey = javaChannel().register(eventLoop().selector, 0, this);
    // ...
}
```
`javaChannel()` 返回netty类`AbstractChannel`对应的jdk底层channel对象
```java
protected SelectableChannel javaChannel() {
    return ch;
}
```


我们查看到SelectableChannel方法，结合netty的 `doRegister()` 方法，我们不难推论出，netty的轮询注册机制其实是将`AbstractNioChannel`内部的jdk类`SelectableChannel`对象注册到jdk类`Selctor`对象上去，并且将`AbstractNioChannel`作为`SelectableChannel`对象的一个attachment附属上，这样再jdk轮询出某条`SelectableChannel`有IO事件发生时，就可以直接取出`AbstractNioChannel`进行后续操作

下面是jdk中的register方法


```java
     //*
     //* @param  sel
     //*         The selector with which this channel is to be registered
     //*
     //* @param  ops
     //*         The interest set for the resulting key
     //*
     //* @param  att
     //*         The attachment for the resulting key; may be <tt>null</tt>
public abstract SelectionKey register(Selector sel, int ops, Object att)
        throws ClosedChannelException;
```
由于篇幅原因，详细的 `processSelectedKey(SelectionKey k, AbstractNioChannel ch)` 过程我们单独写一篇文章来详细展开，这里就简单说一下
1.对于boss NioEventLoop来说，轮询到的是基本上就是连接事件，后续的事情就通过他的pipeline将连接扔给一个worker NioEventLoop处理
2.对于worker NioEventLoop来说，轮询到的基本上都是io读写事件，后续的事情就是通过他的pipeline将读取到的字节流传递给每个channelHandler来处理

上面处理attachment的时候，还有个else分支，我们也来分析一下
else部分的代码如下
```java
NioTask<SelectableChannel> task = (NioTask<SelectableChannel>) a;
processSelectedKey(k, task);
```
说明注册到selctor上的attachment还有另外一中类型，就是 `NioTask`，NioTask主要是用于当一个 `SelectableChannel` 注册到selector的时候，执行一些任务

NioTask的定义
```java
public interface NioTask<C extends SelectableChannel> {
    void channelReady(C ch, SelectionKey key) throws Exception;
    void channelUnregistered(C ch, Throwable cause) throws Exception;
}
```
由于`NioTask` 在netty内部没有使用的地方，这里不过多展开

> 3.判断是否该再来次轮询

```java
if (needsToSelectAgain) {
    for (;;) {
        i++;
        if (selectedKeys[i] == null) {
            break;
        }
        selectedKeys[i] = null;
    }
    selectAgain();
    selectedKeys = this.selectedKeys.flip();
    i = -1;
}
```
我们回忆一下netty的reactor线程经历前两个步骤，分别是抓取产生过的IO事件以及处理IO事件，每次在抓到IO事件之后，都会将 needsToSelectAgain 重置为false，那么什么时候needsToSelectAgain会重新被设置成true呢？

还是和前面一样的思路，我们使用intellij来帮助我们查看needsToSelectAgain被使用的地方，在NioEventLoop类中，只有下面一处将needsToSelectAgain设置为true

> NioEventLoop.java

```java
void cancel(SelectionKey key) {
    key.cancel();
    cancelledKeys ++;
    if (cancelledKeys >= CLEANUP_INTERVAL) {
        cancelledKeys = 0;
        needsToSelectAgain = true;
    }
}
```
继续查看 `cancel` 函数被调用的地方

> AbstractChannel.java

```java
@Override
protected void doDeregister() throws Exception {
    eventLoop().cancel(selectionKey());
}
```
不难看出，在channel从selector上移除的时候，调用cancel函数将key取消，并且当被去掉的key到达 `CLEANUP_INTERVAL` 的时候，设置needsToSelectAgain为true,`CLEANUP_INTERVAL`默认值为256

```java
private static final int CLEANUP_INTERVAL = 256;
```
也就是说，对于每个NioEventLoop而言，每隔256个channel从selector上移除的时候，就标记 needsToSelectAgain 为true，我们还是跳回到上面这段代码

```java
if (needsToSelectAgain) {
    for (;;) {
        i++;
        if (selectedKeys[i] == null) {
            break;
        }
        selectedKeys[i] = null;
    }
    selectAgain();
    selectedKeys = this.selectedKeys.flip();
    i = -1;
}
```

每满256次，就会进入到if的代码块，首先，将selectedKeys的内部数组全部清空，方便被jvm垃圾回收，然后重新调用`selectAgain`重新填装一下 `selectionKey`

```java
private void selectAgain() {
    needsToSelectAgain = false;
    try {
        selector.selectNow();
    } catch (Throwable t) {
        logger.warn("Failed to update SelectionKeys.", t);
    }
}
```
netty这么做的目的我想应该是每隔256次channel断线，重新清理一下selectionKey，保证现存的SelectionKey及时有效

到这里，我们初次阅读源码的时候对reactor的第二个步骤的了解已经足够了。总结一下：netty的reactor线程第二步做的事情为处理IO事件，netty使用数组替换掉jdk原生的HashSet来保证IO事件的高效处理，每个SelectionKey上绑定了netty类`AbstractChannel`对象作为attachment，在处理每个SelectionKey的时候，就可以找到`AbstractChannel`，然后通过pipeline的方式将处理串行到ChannelHandler，回调到用户方法

下一篇文章，我们将一起来看下netty中reactor线程中最后一步，`runTasks`，你将了解到netty中异步执行任务机制的细节，尽请期待

欢迎关注微信公众号：**netty技术内幕**（长按图片可以识别二维码）


![netty技术内幕](http://upload-images.jianshu.io/upload_images/1357217-e71677663583c3c4.png?imageMogr2/auto-orient/strip%7CimageView2/2/w/1240)