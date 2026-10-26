package dev.pluginread.client;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * 解析 Server List Ping JSON（Forge/NeoForge 的 modinfo），缓存最近一次结果。
 */
public final class ServerModsParser {
	public record ServerMod(String modid, String marker) {
	}

	public record PingInfo(String description, String version, int online, int max, List<ServerMod> mods) {
		public boolean hasMods() {
			return mods != null && !mods.isEmpty();
		}
	}

	private static volatile PingInfo last;

	private ServerModsParser() {
	}

	public static PingInfo last() {
		return last;
	}

	public static void clear() {
		last = null;
	}

	public static void pingAsync(String ip) {
		DownloadManager.get().io().execute(() -> {
			try {
				String host = ManifestSource.stripPort(ip);
				int port = 25565;
				int idx = ip.lastIndexOf(':');

				if (idx > 0 && ip.indexOf(':') == idx) {
					String portStr = ip.substring(idx + 1);

					if (portStr.chars().allMatch(Character::isDigit) && !portStr.isEmpty()) {
						port = Integer.parseInt(portStr);
					}
				}

				PluginReadClient.setLastPing(host, port);
				int timeout = Math.max(1000, ClientConfig.get().probeTimeoutMs);
				String json = ServerStatusReader.ping(host, port, timeout).get(timeout + 2000L, TimeUnit.MILLISECONDS);
				last = parse(json);
			} catch (Exception ignored) {
			}
		});
	}

	public static PingInfo parse(String json) {
		try {
			JsonObject root = JsonParser.parseString(json).getAsJsonObject();
			String description = root.has("description") ? asText(root.get("description")) : null;
			String version = root.has("version") ? asText(root.get("version")) : null;
			int online = 0;
			int max = 0;

			if (root.has("players") && root.get("players").isJsonObject()) {
				JsonObject players = root.getAsJsonObject("players");

				if (players.has("online") && players.get("online").isJsonPrimitive()) {
					online = players.get("online").getAsInt();
				}

				if (players.has("max") && players.get("max").isJsonPrimitive()) {
					max = players.get("max").getAsInt();
				}
			}

			List<ServerMod> mods = null;

			if (root.has("modinfo") && root.get("modinfo").isJsonObject()) {
				JsonObject modinfo = root.getAsJsonObject("modinfo");

				if (modinfo.has("modList") && modinfo.get("modList").isJsonArray()) {
					mods = new ArrayList<>();

					for (JsonElement el : modinfo.getAsJsonArray("modList")) {
						if (!el.isJsonObject()) {
							continue;
						}

						JsonObject m = el.getAsJsonObject();

						if (m.has("modid") && m.get("modid").isJsonPrimitive()) {
							String marker = m.has("modmarker") && m.get("modmarker").isJsonPrimitive() ? m.get("modmarker").getAsString() : null;
							mods.add(new ServerMod(m.get("modid").getAsString(), marker));

							if (mods.size() >= 512) {
								break;
							}
						}
					}
				}
			}

			return new PingInfo(description, version, online, max, mods);
		} catch (Exception e) {
			return null;
		}
	}

	private static String asText(JsonElement el) {
		if (el.isJsonPrimitive()) {
			return el.getAsString();
		}

		if (el.isJsonObject()) {
			JsonObject obj = el.getAsJsonObject();

			if (obj.has("text") && obj.get("text").isJsonPrimitive()) {
				return obj.get("text").getAsString();
			}

			if (obj.has("name") && obj.get("name").isJsonPrimitive()) {
				return obj.get("name").getAsString();
			}
		}

		return null;
	}
}
