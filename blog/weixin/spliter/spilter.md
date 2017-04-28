## 为什么要粘包拆包

### 为什么要粘包
首先你得了解一下TCP/IP协议，在用户数据量非常小的情况下，比如极端情况下，一个字节，该TCP数据包的有效载荷非常低，传递100字节的数据，需要100次TCP传送，100次ACK，在应用及时性要求不高的情况下，将这100个有效数据拼接成一个数据包，那会缩短到一个TCP数据包，以及一个ack，有效载荷提高了，带宽也节省了

上面这个是极端情况，非极端情况，有可能两个数据包拼接成一个数据包，也有可能一个半的数据包拼接成一个数据包，也有可能两个半的数据包拼接成一个数据包

### 为什么要拆包
拆包和粘包是相对的，一端有了粘包，数据包发送到另外一端就需要将原始的数据包解析出来，比如，发送端将三个数据包粘成两个TCP数据包发送到接收端，接收端就需要根据应用协议将两个数据包拆分成两个数据包

还有一种拆包就是用户数据包超过了mss，那这么一个数据包在发送的时候必须拆分成一个个数据包，每个数据包用户数据部分就是mss的大小，接收端收到之后需要将这些数据包粘合起来之后，再拆开

## 拆包的原理
在没有netty的情况下，用户如果自己需要拆包，基本原理就是不断从TCP缓冲区中读取数据，每次读取完都需要判断是否是一个完整的数据包

1.如果当前读取的数据不足以拼接成一个完整的业务数据包，那就保留该数据，继续从tcp缓冲区中读取，直到得到一个完整的数据包
2.如果当前读到的数据加上已经读取的数据足够拼接成一个数据包，那就将已经读取的数据拼接上本次读取的数据，够成一个完整的业务数据包传递到业务逻辑，多余的数据仍然保留，以便和下次读到的数据尝试拼接


## 拆包的基类
netty 中的拆包也是如上这个原理，内部会有一个累加器，每次读取到数据都会不断累加，然后尝试对累加到的数据进行解码，解成一个完整的业务数据包，这个基类叫做 `ByteToMessageDecoder`，从名字也可以看出，该类的作用是尝试将字节转换成一条消息，下面我们先详细分析下这个类的作用

### 累加器
`ByteToMessageDecoder` 中定义了两个累加器

```java
public static final Cumulator MERGE_CUMULATOR = ...;
public static final Cumulator COMPOSITE_CUMULATOR = ...;
```

默认情况下，会使用 `MERGE_CUMULATOR`

```java
private Cumulator cumulator = MERGE_CUMULATOR;
```

`MERGE_CUMULATOR` 的原理是每次都将读取到的数据通过内存拷贝的方式，拼接到一个大的字节容器中，这个字节容器在 `ByteToMessageDecoder`中叫做 `cumulation`

```java
ByteBuf cumulation;
```

下面我们看一下 `MERGE_CUMULATOR` 是如何将新读取到的数据累加到字节容器里的

```java
public ByteBuf cumulate(ByteBufAllocator alloc, ByteBuf cumulation, ByteBuf in) {
        ByteBuf buffer;
        if (cumulation.writerIndex() > cumulation.maxCapacity() - in.readableBytes()
                || cumulation.refCnt() > 1) {
            buffer = expandCumulation(alloc, cumulation, in.readableBytes());
        } else {
            buffer = cumulation;
        }
        buffer.writeBytes(in);
        in.release();
        return buffer;
}
```
netty 中对 字节数据的操作，使得累加非常简单，通过一个简单的api调用 ` buffer.writeBytes(in);` 便将新数据累加到字节容器中，为了防止字节容器大小不够，在累加之前还进行了扩容处理

```java
static ByteBuf expandCumulation(ByteBufAllocator alloc, ByteBuf cumulation, int readable) {
        ByteBuf oldCumulation = cumulation;
        cumulation = alloc.buffer(oldCumulation.readableBytes() + readable);
        cumulation.writeBytes(oldCumulation);
        oldCumulation.release();
        return cumulation;
}
```
扩容也是一个内存拷贝操作，新增的大小即是新读取数据的大小

## 拆包抽象
累加器原理清楚之后，下面我们会到主流程，目光集中在 `channelRead` 方法

```java
@Override
public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
    if (msg instanceof ByteBuf) {
        CodecOutputList out = CodecOutputList.newInstance();
        try {
            ByteBuf data = (ByteBuf) msg;
            first = cumulation == null;
            if (first) {
                cumulation = data;
            } else {
                cumulation = cumulator.cumulate(ctx.alloc(), cumulation, data);
            }
            callDecode(ctx, cumulation, out);
        } catch (DecoderException e) {
            throw e;
        } catch (Throwable t) {
            throw new DecoderException(t);
        } finally {
            if (cumulation != null && !cumulation.isReadable()) {
                numReads = 0;
                cumulation.release();
                cumulation = null;
            } else if (++ numReads >= discardAfterReads) {
                numReads = 0;
                discardSomeReadBytes();
            }

            int size = out.size();
            decodeWasNull = !out.insertSinceRecycled();
            fireChannelRead(ctx, out, size);
            out.recycle();
        }
    } else {
        ctx.fireChannelRead(msg);
    }
}
```
方法体不长不短，可以分为一下几个逻辑步骤

1.累加数据
2.将累加到的数据传递给业务进行解码
3.清理字节容器
4.传递业务数据包给业务解码器处理

### 1.累加数据

如果是当前累加器没有数据，就直接跳过内存拷贝，直接将字节容器的指针指向新读取的数据，否则，调用累加器累加数据

```java
ByteBuf data = (ByteBuf) msg;
first = cumulation == null;
if (first) {
    cumulation = data;
} else {
    cumulation = cumulator.cumulate(ctx.alloc(), cumulation, data);
}
```

### 2.将累加到的数据传递给业务进行解码
到这一步，字节容器里的数据已是目前未解码部分的所有的数据了

```java
CodecOutputList out = CodecOutputList.newInstance();
callDecode(ctx, cumulation, out);
```

`callDecode` 将尝试将字节容器的数据解码成业务协议数据包塞到业务数据容器`out`中

```java
protected void callDecode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
    while (in.isReadable()) {
        int oldInputLength = in.readableBytes();
        decode(ctx, in, out);
        if (out.size() == 0) {
            // 解码器未读取任何数据
            if (oldInputLength == in.readableBytes()) {
                break;
            } else {
             // 解码器已读取部分数据，还需要继续
                continue;
            }
        }

        if (oldInputLength == in.readableBytes()) {
            throw new DecoderException(
                    StringUtil.simpleClassName(getClass()) +
                    ".decode() did not read anything but decoded a message.");
        }

        if (isSingleDecode()) {
            break;
        }
    }
}
```
我将原始代码做了一些精简，在解码之前，先记录一下字节容器中有多少字节待解码，然后调用抽象函数 `decode` 进行解码

```java
protected abstract void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception;
```
netty中对各种用户协议的支持就体现在这个抽象函数中，传进去的是以用户协议字节开头的当前读取到的所有的数据，以及业务协议包容器，所有的业务解码最终都是实现了该抽象方法

业务拆包完成之后，如果发现并没有解到一个业务数据包，这个时候有两种情况

1.一个是解码器什么数据也没读取，可能数据还不够业务拆包器处理，直接break等待新的数据
2.解码器已读取部分数据，说明解码器仍然在工作，继续解码

业务拆包完成之后，如果发现已经解到了数据包，但是，发现并没有读取任何数据，这个时候就会抛出一个Runtime异常 `DecoderException`，告诉你，你什么数据都没读取，却解析出一个业务数据包，这是有问题的


### 3.清理字节容器
业务拆包完成之后，只是从字节容器中取走了数据，但是这部分空间对于字节容器来说依然保留着，而字节容器每次累加字节数据的时候都是将字节数据追加到尾部，如果不对字节容器做清理，那么时间一长就会OOM


正常情况下，其实每次读取完数据，netty都会在下面这个方法中将字节容器清理，只不过，当发送端发送数据过快，`channelReadComplete`可能会很久才被调用一次

```java
public void channelReadComplete(ChannelHandlerContext ctx) throws Exception {
    numReads = 0;
    discardSomeReadBytes();
    if (decodeWasNull) {
        decodeWasNull = false;
        if (!ctx.channel().config().isAutoRead()) {
            ctx.read();
        }
    }
    ctx.fireChannelReadComplete();
}
```

这里顺带插一句，如果一次数据读取完毕之后(可能接收端一边收，发送端一边发，这里的读取完毕指的是接收端在某个时间不再接受到数据为止），发现任然没有解析到一个完整的用户数据包，即使该channel的设置为非自动读取，也会触发一次读取操作 `ctx.read()`,该操作会重新向selector注册op_read事件，以便于下一次能读到数据之后拼接成一个完整的数据包


为了防止发送端发送数据过快，netty会在每次读取到一次数据，业务拆包之后对字节字节容器做清理，清理部分的代码如下 

```java
if (cumulation != null && !cumulation.isReadable()) {
    numReads = 0;
    cumulation.release();
    cumulation = null;
} else if (++ numReads >= discardAfterReads) {
    numReads = 0;
    discardSomeReadBytes();
}
```

如果字节容器当前已无数据可读取，直接销毁字节容器，并且标注一下当前字节容器一次数据也没读取

如果连续16次，字节容器中仍然有未被业务拆包器读取的数据，那就做一次压缩，有效数据段整体移到容器首部

discard之前，字节累加器中的数据分布
 
```java
+--------------+----------+----------+
|   readed     | unreaded | writable | 
+--------------+----------+----------+
```
 
discard之后，字节容器中的数据分布
```java
+----------+-------------------------+
| unreaded |      writable           | 
+----------+-------------------------+
```
这样字节容器又可以承载更多的数据了


### 4.传递业务数据包给业务解码器处理

以上三个步骤完成之后，就可以将拆成的包丢到业务解码器处理了，代码如下

```java
int size = out.size();
decodeWasNull = !out.insertSinceRecycled();
fireChannelRead(ctx, out, size);
out.recycle();
```
期间用一个成员变量 `decodeWasNull` 来标识本次读取数据是否解析到一个业务数据包，然后调用 `fireChannelRead` 将解析到的业务数据包都传递到后续的handler

```java
static void fireChannelRead(ChannelHandlerContext ctx, CodecOutputList msgs, int numElements) {
    for (int i = 0; i < numElements; i ++) {
        ctx.fireChannelRead(msgs.getUnsafe(i));
    }
}
```

这样就可以把一个个完整的业务数据包传递到后续的业务解码器进行解码，随后处理业务逻辑

## 行拆包
下面，以一个具体的例子来看看业netty中的务拆包器是如何来拆包的

这个类叫做 `LineBasedFrameDecoder`，基于行的拆包器，他可以同时处理 `\n`以及`\r\n`两种类型的行分隔符，核心方法都在继承的 `decode` 方法中

```java
protected final void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
    Object decoded = decode(ctx, in);
    if (decoded != null) {
        out.add(decoded);
    }
}
```
netty 中自带的解码器都是如上这种模板，其实可以加一层，把这这层模板抽取出来的，不知道为什么netty没有这么做，我们接着跟进去，代码比较长，分模块给你展示

###1.找到换行符位置 
```java
final int eol = findEndOfLine(buffer);

private static int findEndOfLine(final ByteBuf buffer) {
    int i = buffer.forEachByte(ByteProcessor.FIND_LF);
    if (i > 0 && buffer.getByte(i - 1) == '\r') {
        i--;
    }
    return i;
}

ByteProcessor FIND_LF = new IndexOfProcessor((byte) '\n');

```
for循环遍历，找到第一个 `\n` 的位置,如果`\n`前面的字符为`\r`，那就返回`\r`的位置

###2 非丢弃模式
接下来，netty会判断，当前解包是否属于丢弃模式，用一个成员变量来标识

```java
private boolean discarding;
```

第一次解包不在丢弃模式，于是进入以下环节

####2.1非丢弃模式下找到行分隔符
```java
// 1.计算分隔符和包长度
final ByteBuf frame;
final int length = eol - buffer.readerIndex();
final int delimLength = buffer.getByte(eol) == '\r'? 2 : 1;

// 丢弃异常数据
if (length > maxLength) {
    buffer.readerIndex(eol + delimLength);
    fail(ctx, length);
    return null;
}

// 取包的时候是否包括分隔符
if (stripDelimiter) {
    frame = buffer.readRetainedSlice(length);
    buffer.skipBytes(delimLength);
} else {
    frame = buffer.readRetainedSlice(length + delimLength);
}
return frame;
```
1.首先，新建一个帧，计算一下当前包的长度和分隔符的长度（因为有两种分隔符）
2.然后判断一下需要解包的长度是否大于该解包器允许的最大长度(`maxLength`)，这个参数在构造函数中被传递进来，如超出允许的最大长度，就将这段数据抛弃，返回null
3.最后，将一个完整的数据包取出，如果构造本解包器的时候指定 `stripDelimiter`为false，即解析出来的包包含分隔符，默认为不包含分隔符
  
  
####2.2非丢弃模式下未找到分隔符
像前文开头部分所说的那样，本次字符容器里面的数据可能不足以拼接成一个完整的业务数据包，进入如下流程处理

```java
final int length = buffer.readableBytes();
if (length > maxLength) {
    discardedBytes = length;
    buffer.readerIndex(buffer.writerIndex());
    discarding = true;
    if (failFast) {
        fail(ctx, "over " + discardedBytes);
    }
}
return null;
```
 
首先取得当前字节容器的可读字节个数，接着，判断一下是否已经超过可允许的最大长度，如果没有找过，那么直接返回null，字节容器中的数据没有任何改变，否则，就需要进入丢弃模式

使用一个成员变量 `discardedBytes` 来表示已经丢弃了多少数据，然后将字节容器的读指针移到写指针，意味着丢弃这一部分数据，设置成员变量`discarding`为true，如果设置了`failFast`，那么直接异常，默认情况下`failFast`为false

###3 丢弃模式
如果解包的时候处在丢弃模式，也会有两种情况发生

####3.1丢弃模式下找到行分隔符

在丢弃模式下，如果找到分隔符，那可以将分隔符之前的都丢弃掉

```java
final int length = discardedBytes + eol - buffer.readerIndex();
final int delimLength = buffer.getByte(eol) == '\r'? 2 : 1;
buffer.readerIndex(eol + delimLength);
discardedBytes = 0;
discarding = false;
if (!failFast) {
    fail(ctx, length);
}
```
计算出分隔符的长度之后，直接把分隔符之前的数据全部丢弃，当然丢弃的字符也包括分隔符，经过这么一次丢弃，后面就有可能是正常的数据包，下一次解包的时候就会进入正常的解包流程了


####3.2丢弃模式下未找到行分隔符

这种情况比较简单，因为当前还在丢弃模式，没有找到行分隔符意味着当前一个完整的数据包还没丢弃完，当前读取的数据是丢弃的一部分，所以直接丢弃
```java
discardedBytes += buffer.readableBytes();
buffer.readerIndex(buffer.writerIndex());
```

## 特定分隔符拆包
这个类叫做 `DelimiterBasedFrameDecoder`，读者可以完全根据行解包器的思路去分析这个解包器，这里不在赘述，有问题可以留言

## 总结
netty中的解包器的过程其实是和你自己去解包过程一样，只不过TA将解包过程中逻辑比较独立的部分抽象出来变成几个不同层次的类，方便各种协议的扩展，我们平时在写代码过程中，也必须培养这种抽象能力，这样你的coding水平才会不断提高，完。