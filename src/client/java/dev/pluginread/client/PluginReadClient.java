package dev.pluginread.client;

import com.mojang.brigadier.Command;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.network.chat.Component;

import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.mojang.blaze3d.platform.InputConstants;

/**
 * Plugin Read 客户端入口：按键 / 指令 / 进服自动探测。
 */
public final class PluginReadClient implements ClientModInitializer {
	public static final Logger LOGGER = LoggerFactory.getLogger("pluginread");

	public static final KeyMapping OPEN_GUI = KeyMappingHelper.registerKeyMapping(
			new KeyMapping("key.pluginread.open", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_V, KeyMapping.Category.MISC));

	private static volatile String lastPingHost = "";
	private static volatile int lastPingPort = 25565;

	@Override
	public void onInitializeClient() {
		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
			String ip = currentServerIp();
			DownloadManager.get().onJoin(ip);
			ServerModsParser.clear();

			if (ip != null && !ip.isBlank() && ManifestSource.shouldProbe(ip)) {
				fetchAsync(ClientConfig.get().notifyOnJoin);
			} else {
				DownloadManager.get().setNotFound();
			}

			if (ip != null && !ip.isBlank()) {
				ServerModsParser.pingAsync(ip);
			}
		});

		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
			DownloadManager.get().onDisconnect();
			ServerModsParser.clear();
		});

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			while (OPEN_GUI.consumeClick()) {
				client.setScreen(new PluginReadScreen(null));
			}
		});

		ClientCommandRegistrationCallback.EVENT.register((dispatcher, buildContext) -> dispatcher.register(
				ClientCommands.literal("pluginread")
						.executes(ctx -> {
							Minecraft.getInstance().setScreen(new PluginReadScreen(null));
							return Command.SINGLE_SUCCESS;
						})
						.then(ClientCommands.literal("refresh").executes(ctx -> {
							String ip = currentServerIp();

							if (ip == null || ip.isBlank()) {
								ctx.getSource().sendError(Component.literal("当前未连接服务器"));
								return 0;
							}

							chat("Plugin Read: 正在刷新…");
							fetchAsync(false);
							ServerModsParser.pingAsync(ip);
							return Command.SINGLE_SUCCESS;
						}))
						.then(ClientCommands.literal("folder").executes(ctx -> {
							DownloadManager.get().openFolder();
							return Command.SINGLE_SUCCESS;
						}))));
	}

	public static void refresh() {
		String ip = currentServerIp();

		if (ip == null || ip.isBlank()) {
			return;
		}

		fetchAsync(false);
		ServerModsParser.pingAsync(ip);
	}

	/** 在 IO 线程拉取清单，结果回主线程更新状态。 */
	private static void fetchAsync(boolean notify) {
		DownloadManager.get().io().execute(() -> {
			String ip = DownloadManager.get().serverIp();

			if (ip == null) {
				return;
			}

			ManifestSource.FetchResult result = ManifestSource.fetch(ip);
			runOnClient(() -> {
				if (result != null) {
					DownloadManager.get().setFiles(result.files(), result.url());

					if (notify && !result.files().isEmpty()) {
						chat("Plugin Read: 发现服务器转载文件 " + result.files().size() + " 个，按 V 打开界面查看");
					}
				} else {
					DownloadManager.get().setNotFound();
				}
			});
		});
	}

	public static String currentServerIp() {
		ServerData data = Minecraft.getInstance().getCurrentServer();
		return data != null ? data.ip : "";
	}

	public static void chat(String msg) {
		Minecraft mc = Minecraft.getInstance();

		if (mc.player != null) {
			mc.player.sendSystemMessage(Component.literal(msg));
		}
	}

	public static void runOnClient(Runnable r) {
		Minecraft.getInstance().execute(r);
	}

	public static void setLastPing(String host, int port) {
		lastPingHost = host;
		lastPingPort = port;
	}

	public static String lastPingHost() {
		return lastPingHost;
	}

	public static int lastPingPort() {
		return lastPingPort;
	}
}
