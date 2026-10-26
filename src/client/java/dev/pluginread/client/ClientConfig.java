package dev.pluginread.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 客户端配置：config/pluginread-client.json
 */
public final class ClientConfig {
	private static final Logger LOGGER = LoggerFactory.getLogger(ClientConfig.class);
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

	private static ClientConfig instance;

	public boolean autoProbe = true;
	public boolean notifyOnJoin = true;
	public int probeTimeoutMs = 3000;
	public int maxFileMb = 64;
	public Map<String, ServerEntry> servers = new LinkedHashMap<>();

	public static final class ServerEntry {
		public List<String> manifestUrls = new ArrayList<>();
		public boolean autoDownload = false;
	}

	public static synchronized ClientConfig get() {
		if (instance != null) {
			return instance;
		}

		Path file = FabricLoader.getInstance().getConfigDir().resolve("pluginread-client.json");

		try {
			if (Files.exists(file)) {
				instance = GSON.fromJson(Files.readString(file), ClientConfig.class);
			}
		} catch (Exception e) {
			LOGGER.warn("Plugin Read: 读取配置失败，使用默认配置", e);
		}

		if (instance == null) {
			instance = new ClientConfig();
		}

		instance.normalize();
		instance.save();
		return instance;
	}

	private void normalize() {
		if (servers == null) {
			servers = new LinkedHashMap<>();
		}

		for (ServerEntry entry : servers.values()) {
			if (entry.manifestUrls == null) {
				entry.manifestUrls = new ArrayList<>();
			}
		}
	}

	public synchronized void save() {
		Path file = FabricLoader.getInstance().getConfigDir().resolve("pluginread-client.json");

		try {
			Files.createDirectories(file.getParent());
			Files.writeString(file, GSON.toJson(this));
		} catch (Exception e) {
			LOGGER.warn("Plugin Read: 保存配置失败", e);
		}
	}

	public ServerEntry forServer(String ip) {
		ServerEntry entry = servers.get(ip);

		if (entry == null) {
			entry = new ServerEntry();
			servers.put(ip, entry);
			save();
		}

		return entry;
	}

	public boolean autoDownloadFor(String ip) {
		ServerEntry entry = servers.get(ip);
		return entry != null && entry.autoDownload;
	}
}
