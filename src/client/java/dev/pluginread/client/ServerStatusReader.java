package dev.pluginread.client;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.ByteToMessageDecoder;
import io.netty.handler.timeout.ReadTimeoutHandler;

/**
 * 原版 Server List Ping（仅状态查询），用于读取服务器广播的模组信息。
 */
public final class ServerStatusReader {
	private ServerStatusReader() {
	}

	public static CompletableFuture<String> ping(String host, int port, int timeoutMs) {
		CompletableFuture<String> result = new CompletableFuture<>();
		EventLoopGroup group = new NioEventLoopGroup(1);

		result.whenComplete((r, t) -> group.shutdownGracefully(0, 200, TimeUnit.MILLISECONDS));

		try {
			Bootstrap bootstrap = new Bootstrap()
					.group(group)
					.channel(NioSocketChannel.class)
					.option(io.netty.channel.ChannelOption.CONNECT_TIMEOUT_MILLIS, Math.max(500, timeoutMs))
					.remoteAddress(new InetSocketAddress(host, port))
					.handler(new ChannelInitializer<SocketChannel>() {
						@Override
						protected void initChannel(SocketChannel ch) {
							ch.pipeline().addLast(new ReadTimeoutHandler(Math.max(500, timeoutMs), TimeUnit.MILLISECONDS));
							ch.pipeline().addLast(new VarintFrameDecoder());
							ch.pipeline().addLast(new ResponseHandler(result, host, port));
						}
					});

			bootstrap.connect().addListener(f -> {
				if (!f.isSuccess()) {
					result.completeExceptionally(f.cause());
				}
			});
		} catch (Exception e) {
			result.completeExceptionally(e);
		}

		return result;
	}

	static int readVarInt(ByteBuf buf) {
		int value = 0;
		int shift = 0;

		while (true) {
			if (!buf.isReadable()) {
				return Integer.MIN_VALUE;
			}

			byte b = buf.readByte();
			value |= (b & 0x7F) << shift;

			if ((b & 0x80) == 0) {
				break;
			}

			shift += 7;

			if (shift > 28) {
				return Integer.MIN_VALUE;
			}
		}

		return value;
	}

	static void writeVarInt(ByteArrayOutputStream out, int value) {
		while ((value & ~0x7F) != 0) {
			out.write((value & 0x7F) | 0x80);
			value >>>= 7;
		}

		out.write(value);
	}

	static void writeVarInt(ByteBuf buf, int value) {
		while ((value & ~0x7F) != 0) {
			buf.writeByte((value & 0x7F) | 0x80);
			value >>>= 7;
		}

		buf.writeByte(value);
	}

	static final class VarintFrameDecoder extends ByteToMessageDecoder {
		@Override
		protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
			in.markReaderIndex();
			int len = readVarInt(in);

			if (len == Integer.MIN_VALUE) {
				in.resetReaderIndex();
				return;
			}

			if (len < 0 || len > 2 * 1024 * 1024) {
				ctx.close();
				return;
			}

			if (in.readableBytes() < len) {
				in.resetReaderIndex();
				return;
			}

			out.add(in.readRetainedSlice(len));
		}
	}

	static final class ResponseHandler extends SimpleChannelInboundHandler<ByteBuf> {
		private final CompletableFuture<String> result;
		private final String host;
		private final int port;
		private boolean done;

		ResponseHandler(CompletableFuture<String> result, String host, int port) {
			this.result = result;
			this.host = host;
			this.port = port;
		}

		@Override
		public void channelActive(ChannelHandlerContext ctx) {
			sendHandshakeAndRequest(ctx);
			ctx.fireChannelActive();
		}

		private void sendHandshakeAndRequest(ChannelHandlerContext ctx) {
			ByteArrayOutputStream hs = new ByteArrayOutputStream();
			writeVarInt(hs, 0x00);
			writeVarInt(hs, -1);
			byte[] hostBytes = host.getBytes(StandardCharsets.UTF_8);
			writeVarInt(hs, hostBytes.length);
			hs.writeBytes(hostBytes);
			hs.write((port >> 8) & 0xFF);
			hs.write(port & 0xFF);
			writeVarInt(hs, 1);

			ByteArrayOutputStream rq = new ByteArrayOutputStream();
			writeVarInt(rq, 0x00);

			ByteBuf out = Unpooled.buffer();
			writeVarInt(out, hs.size());
			out.writeBytes(hs.toByteArray());
			writeVarInt(out, rq.size());
			out.writeBytes(rq.toByteArray());
			ctx.writeAndFlush(out);
		}

		@Override
		protected void channelRead0(ChannelHandlerContext ctx, ByteBuf msg) {
			if (done) {
				return;
			}

			int id = readVarInt(msg);

			if (id != 0) {
				return;
			}

			int strLen = readVarInt(msg);

			if (strLen < 0 || strLen > msg.readableBytes()) {
				return;
			}

			String json = msg.toString(msg.readerIndex(), strLen, StandardCharsets.UTF_8);
			done = true;
			result.complete(json);
			ctx.close();
		}

		@Override
		public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
			result.completeExceptionally(cause);
			ctx.close();
		}

		@Override
		public void channelInactive(ChannelHandlerContext ctx) {
			if (!done) {
				result.completeExceptionally(new IOException("连接被关闭"));
			}
		}
	}
}
