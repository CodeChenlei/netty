/*
 * Copyright 2014 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package io.netty.channel;


import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.util.CharsetUtil;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.hamcrest.Matchers.*;
import static org.junit.Assert.*;

public class PendingWriteQueueTest {

    @Test
    public void testRemoveAndWrite() {
        assertWrite(new TestHandler() {
            @Override
            public void flush(ChannelHandlerContext ctx) throws Exception {
                assertFalse("Should not be writable anymore", ctx.channel().isWritable());

                ChannelFuture future = queue.removeAndWrite();
                future.addListener(new ChannelFutureListener() {
                    @Override
                    public void operationComplete(ChannelFuture future) throws Exception {
                        assertQueueEmpty(queue);
                    }
                });
                super.flush(ctx);
            }
        }, 1);
    }

    @Test
    public void testRemoveAndWriteAll() {
        assertWrite(new TestHandler() {
            @Override
            public void flush(ChannelHandlerContext ctx) throws Exception {
                assertFalse("Should not be writable anymore", ctx.channel().isWritable());

                ChannelFuture future = queue.removeAndWriteAll();
                future.addListener(new ChannelFutureListener() {
                    @Override
                    public void operationComplete(ChannelFuture future) throws Exception {
                        assertQueueEmpty(queue);
                    }
                });
                super.flush(ctx);
            }
        }, 3);
    }

    @Test
    public void testRemoveAndFail() {
        assertWriteFails(new TestHandler() {

            @Override
            public void flush(ChannelHandlerContext ctx) throws Exception {
                queue.removeAndFail(new TestException());
                super.flush(ctx);
            }
        }, 1);
    }

    @Test
    public void testRemoveAndFailAll() {
        assertWriteFails(new TestHandler() {
            @Override
            public void flush(ChannelHandlerContext ctx) throws Exception {
                queue.removeAndFailAll(new TestException());
                super.flush(ctx);
            }
        }, 3);
    }

    @Test
    public void shouldFireChannelWritabilityChangedAfterRemoval() {
        final AtomicReference<ChannelHandlerContext> ctxRef = new AtomicReference<ChannelHandlerContext>();
        final AtomicReference<PendingWriteQueue> queueRef = new AtomicReference<PendingWriteQueue>();
        final ByteBuf msg = Unpooled.copiedBuffer("test", CharsetUtil.US_ASCII);

        final EmbeddedChannel channel = new EmbeddedChannel(new ChannelInboundHandlerAdapter() {
            @Override
            public void handlerAdded(ChannelHandlerContext ctx) throws Exception {
                ctxRef.set(ctx);
                queueRef.set(new PendingWriteQueue(ctx));
            }

            @Override
            public void channelWritabilityChanged(ChannelHandlerContext ctx) throws Exception {
                final PendingWriteQueue queue = queueRef.get();

                final ByteBuf msg = (ByteBuf) queue.current();
                if (msg == null) {
                    return;
                }

                assertThat(msg.refCnt(), is(1));

                // This call will trigger another channelWritabilityChanged() event because the number of
                // pending bytes will go below the low watermark.
                //
                // If PendingWriteQueue.remove() did not remove the current entry before triggering
                // channelWritabilityChanged() event, we will end up with attempting to remove the same
                // element twice, resulting in the double release.
                queue.remove();

                assertThat(msg.refCnt(), is(0));
            }
        });

        channel.config().setWriteBufferLowWaterMark(1);
        channel.config().setWriteBufferHighWaterMark(3);

        final PendingWriteQueue queue = queueRef.get();

        // Trigger channelWritabilityChanged() by adding a message that's larger than the high watermark.
        queue.add(msg, channel.newPromise());

        channel.finish();

        assertThat(msg.refCnt(), is(0));
    }

    private static void assertWrite(ChannelHandler handler, int count) {
        final ByteBuf buffer = Unpooled.copiedBuffer("Test", CharsetUtil.US_ASCII);
        final EmbeddedChannel channel = new EmbeddedChannel(handler);
        channel.config().setWriteBufferLowWaterMark(1);
        channel.config().setWriteBufferHighWaterMark(3);

        ByteBuf[] buffers = new ByteBuf[count];
        for (int i = 0; i < buffers.length; i++) {
            buffers[i] = buffer.duplicate().retain();
        }
        assertTrue(channel.writeOutbound(buffers));
        assertTrue(channel.finish());
        channel.closeFuture().syncUninterruptibly();

        for (int i = 0; i < buffers.length; i++) {
            assertBuffer(channel, buffer);
        }
        buffer.release();
        assertNull(channel.readOutbound());
    }

    private static void assertBuffer(EmbeddedChannel channel, ByteBuf buffer) {
        ByteBuf written = channel.readOutbound();
        assertEquals(buffer, written);
        written.release();
    }

    private static void assertQueueEmpty(PendingWriteQueue queue) {
        assertTrue(queue.isEmpty());
        assertEquals(0, queue.size());
        assertNull(queue.current());
        assertNull(queue.removeAndWrite());
        assertNull(queue.removeAndWriteAll());
    }

    private static void assertWriteFails(ChannelHandler handler, int count) {
        final ByteBuf buffer = Unpooled.copiedBuffer("Test", CharsetUtil.US_ASCII);
        final EmbeddedChannel channel = new EmbeddedChannel(handler);
        ByteBuf[] buffers = new ByteBuf[count];
        for (int i = 0; i < buffers.length; i++) {
            buffers[i] = buffer.duplicate().retain();
        }
        try {
            assertFalse(channel.writeOutbound(buffers));
            fail();
        } catch (Exception e) {
            assertTrue(e instanceof TestException);
        }
        assertFalse(channel.finish());
        channel.closeFuture().syncUninterruptibly();

        buffer.release();
        assertNull(channel.readOutbound());
    }

    @Test
    public void testRemoveAndFailAllReentrantFailAll() {
        EmbeddedChannel channel = new EmbeddedChannel();
        final PendingWriteQueue queue = new PendingWriteQueue(channel.pipeline().firstContext());

        ChannelPromise promise = channel.newPromise();
        promise.addListener(new ChannelFutureListener() {
            @Override
            public void operationComplete(ChannelFuture future) throws Exception {
                queue.removeAndFailAll(new IllegalStateException());
            }
        });
        queue.add(1L, promise);

        ChannelPromise promise2 = channel.newPromise();
        queue.add(2L, promise2);
        queue.removeAndFailAll(new Exception());
        assertTrue(promise.isDone());
        assertFalse(promise.isSuccess());
        assertTrue(promise2.isDone());
        assertFalse(promise2.isSuccess());
        assertFalse(channel.finish());
    }

    @Test
    public void testRemoveAndFailAllReentrantWrite() {
        final List<Integer> failOrder = Collections.synchronizedList(new ArrayList<Integer>());
        EmbeddedChannel channel = new EmbeddedChannel();
        final PendingWriteQueue queue = new PendingWriteQueue(channel.pipeline().firstContext());

        ChannelPromise promise = channel.newPromise();
        final ChannelPromise promise3 = channel.newPromise();
        promise3.addListener(new ChannelFutureListener() {
            @Override
            public void operationComplete(ChannelFuture future) throws Exception {
                failOrder.add(3);
            }
        });
        promise.addListener(new ChannelFutureListener() {
            @Override
            public void operationComplete(ChannelFuture future) throws Exception {
                failOrder.add(1);
                queue.add(3L, promise3);
            }
        });
        queue.add(1L, promise);

        ChannelPromise promise2 = channel.newPromise();
        promise2.addListener(new ChannelFutureListener() {
            @Override
            public void operationComplete(ChannelFuture future) throws Exception {
                failOrder.add(2);
            }
        });
        queue.add(2L, promise2);
        queue.removeAndFailAll(new Exception());
        assertTrue(promise.isDone());
        assertFalse(promise.isSuccess());
        assertTrue(promise2.isDone());
        assertFalse(promise2.isSuccess());
        assertTrue(promise3.isDone());
        assertFalse(promise3.isSuccess());
        assertFalse(channel.finish());
        assertEquals(1, (int) failOrder.get(0));
        assertEquals(2, (int) failOrder.get(1));
        assertEquals(3, (int) failOrder.get(2));
    }

    @Test
    public void testRemoveAndWriteAllReentrance() {
        EmbeddedChannel channel = new EmbeddedChannel();
        final PendingWriteQueue queue = new PendingWriteQueue(channel.pipeline().firstContext());

        ChannelPromise promise = channel.newPromise();
        promise.addListener(new ChannelFutureListener() {
            @Override
            public void operationComplete(ChannelFuture future) throws Exception {
                queue.removeAndWriteAll();
            }
        });
        queue.add(1L, promise);

        ChannelPromise promise2 = channel.newPromise();
        queue.add(2L, promise2);
        queue.removeAndWriteAll();
        channel.flush();
        assertTrue(promise.isSuccess());
        assertTrue(promise2.isSuccess());
        assertTrue(channel.finish());

        assertEquals(1L, (long) channel.readOutbound());
        assertEquals(2L, (long) channel.readOutbound());
        assertNull(channel.readOutbound());
        assertNull(channel.readInbound());
    }

    // See https://github.com/netty/netty/issues/3967
    @Test
    public void testCloseChannelOnCreation() {
        EmbeddedChannel channel = new EmbeddedChannel(new ChannelInboundHandlerAdapter());
        ChannelHandlerContext context = channel.pipeline().firstContext();
        channel.close().syncUninterruptibly();

        final PendingWriteQueue queue = new PendingWriteQueue(context);

        IllegalStateException ex = new IllegalStateException();
        ChannelPromise promise = channel.newPromise();
        queue.add(1L, promise);
        queue.removeAndFailAll(ex);
        assertSame(ex, promise.cause());
    }

    private static class TestHandler extends ChannelDuplexHandler {
        protected PendingWriteQueue queue;
        private int expectedSize;

        @Override
        public void channelActive(ChannelHandlerContext ctx) throws Exception {
            super.channelActive(ctx);
            assertQueueEmpty(queue);
            assertTrue("Should be writable", ctx.channel().isWritable());
        }

        @Override
        public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
            queue.add(msg, promise);
            assertFalse(queue.isEmpty());
            assertEquals(++expectedSize, queue.size());
            assertNotNull(queue.current());
        }

        @Override
        public void handlerAdded(ChannelHandlerContext ctx) throws Exception {
            queue = new PendingWriteQueue(ctx);
        }
    }

    private static final class TestException extends Exception { }
}
