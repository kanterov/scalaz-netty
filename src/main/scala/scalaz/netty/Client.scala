/*
 * Copyright 2015 RichRelevance
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package scalaz
package netty

import concurrent._
import stream._
import syntax.monad._

import scodec.bits.ByteVector

import java.net.InetSocketAddress
import java.util.concurrent.ExecutorService
import java.util.concurrent.atomic.AtomicReference

import _root_.io.netty.bootstrap._
import _root_.io.netty.buffer._
import _root_.io.netty.channel._
import _root_.io.netty.channel.socket._
import _root_.io.netty.channel.socket.nio._
import _root_.io.netty.handler.codec._

private[netty] final class Client(channel: _root_.io.netty.channel.Channel, queue: BPAwareQueue[ByteVector], halt: AtomicReference[Cause]) {

  def read: Process[Task, ByteVector] = queue.dequeue(channel.config)

  def write(implicit pool: ExecutorService): Sink[Task, ByteVector] = {
    def inner(bv: ByteVector): Task[Unit] = {
      Task delay {
        val data = bv.toArray
        val buf = channel.alloc().buffer(data.length)
        buf.writeBytes(data)

        Netty toTask channel.writeAndFlush(buf)
      } join
    }

    // TODO termination
    Process constant (inner _)
  }

  def shutdown(implicit pool: ExecutorService): Process[Task, Nothing] = {
    val close = for {
      _ <- Netty toTask channel.close()
      _ <- queue.close
    } yield ()

    Process eval_ close causedBy halt.get
  }
}

private[netty] final class ClientHandler(queue: BPAwareQueue[ByteVector], halt: AtomicReference[Cause]) extends ChannelInboundHandlerAdapter {

  override def channelInactive(ctx: ChannelHandlerContext): Unit = {
    // if the connection is remotely closed, we need to clean things up on our side
    queue.close.run

    super.channelInactive(ctx)
  }

  override def channelRead(ctx: ChannelHandlerContext, msg: AnyRef): Unit = {
    val buf = msg.asInstanceOf[ByteBuf]
    val dst = Array.ofDim[Byte](buf.capacity())
    buf.getBytes(0, dst)
    val bv = ByteVector.view(dst)

    buf.release()

    val channelConfig = ctx.channel.config

    //this could be run async too, but then we introduce some latency. It's better to run this on the netty worker thread as enqueue uses Strategy.Sequential
    queue.enqueueOne(channelConfig, bv).run
  }

  override def exceptionCaught(ctx: ChannelHandlerContext, t: Throwable): Unit = {
    halt.set(Cause.Error(t))
    queue.close.run
  }
}

private[netty] object Client {
  def apply(to: InetSocketAddress, config: ClientConfig)(implicit pool: ExecutorService, S: Strategy): Task[Client] = Task delay {
    //val client = new Client(config.limit)
    val bootstrap = new Bootstrap

    val queue = BPAwareQueue[ByteVector](config.limit)
    val halt = new AtomicReference[Cause](Cause.End)

    bootstrap.group(Netty.clientWorkerGroup)
    bootstrap.channel(classOf[NioSocketChannel])
    bootstrap.option[java.lang.Boolean](ChannelOption.SO_KEEPALIVE, config.keepAlive)
    bootstrap.option[java.lang.Boolean](ChannelOption.TCP_NODELAY, config.tcpNoDelay)
    config.soSndBuf.foreach(bootstrap.option[java.lang.Integer](ChannelOption.SO_SNDBUF, _))
    config.soRcvBuf.foreach(bootstrap.option[java.lang.Integer](ChannelOption.SO_RCVBUF, _))

    bootstrap.handler(new ChannelInitializer[SocketChannel] {
      def initChannel(ch: SocketChannel): Unit = {
        ch.pipeline
          .addLast("frame encoding", new LengthFieldPrepender(4))
          .addLast("frame decoding", new LengthFieldBasedFrameDecoder(Int.MaxValue, 0, 4, 0, 4))
          .addLast("incoming handler", new ClientHandler(queue, halt))
      }
    })

    val connectF = bootstrap.connect(to)

    for {
      _ <- Netty toTask connectF
      client <- Task delay {
        new Client(connectF.channel(), queue, halt)
      }
    } yield client
  } join
}

final case class ClientConfig(keepAlive: Boolean, limit: Int, tcpNoDelay: Boolean, soSndBuf: Option[Int], soRcvBuf: Option[Int])

object ClientConfig {
  val Default = ClientConfig(true, 1000, false, None, None)
}
