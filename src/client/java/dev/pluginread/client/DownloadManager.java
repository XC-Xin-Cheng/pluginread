package dev.pluginread.client;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 下载状态管理：文件列表、下载（HTTP + SHA-256 校验）、存储目录。
 */
public final class DownloadManager {
	private static final Logger LOGGER = LoggerFactory.getLogger(DownloadManager.class);

	private static final DownloadManager INSTANCE = new DownloadManager();

	public static DownloadManager get() {
		return INSTANCE;
	}

	public enum State {
		PENDING,
		DOWNLOADING,
		DONE,
		FAILED
	}

	public static final class ServerFile {
		public final String name;
		public final long size;
		public final String sha256;
		public final String url;
		public volatile State state = State.PENDING;
		public volatile JarMetadata meta;
		public volatile long downloaded = -1;

		public ServerFile(String name, long size, String sha256, String url) {
			this.name = name;
			this.size = size;
			this.sha256 = sha256;
			this.url = url;
		}
	}

	private final ExecutorService io = Executors.newFixedThreadPool(2, r -> {
		Thread t = new Thread(r, "PluginRead-Worker");
		t.setDaemon(true);
		return t;
	});

	private volatile List<ServerFile> files = List.of();
	private volatile String serverIp = null;
	private volatile String statusText = "";
	private final Set<String> busy = ConcurrentHashMap.newKeySet();

	private DownloadManager() {
	}

	public ExecutorService io() {
		return io;
	}

	public void onJoin(String ip) {
		this.serverIp = ip == null || ip.isBlank() ? "unknown-server" : ip;
		this.files = List.of();
		this.statusText = "";
		busy.clear();
	}

	public void onDisconnect() {
		this.serverIp = null;
		this.files = List.of();
		this.statusText = "";
	}

	public void setFiles(List<ServerFile> list, String sourceUrl) {
		this.files = List.copyOf(list);
		this.statusText = "来源: " + sourceUrl + "  |  " + list.size() + " 个文件";

		ClientConfig cfg = ClientConfig.get();

		if (serverIp != null && cfg.autoDownloadFor(serverIp)) {
			requestAll();
		}
	}

	public void setNotFound() {
		this.statusText = "未找到清单（可在 config/pluginread-client.json 中配置 URL）";
	}

	public List<ServerFile> files() {
		return files;
	}

	public boolean hasFiles() {
		return !files.isEmpty();
	}

	public String serverIp() {
		return serverIp;
	}

	public String statusText() {
		return statusText;
	}

	public void requestFile(String name) {
		String ip = serverIp;

		if (ip == null) {
			return;
		}

		for (ServerFile f : files) {
			if (f.name.equals(name)) {
				startDownload(f, ip);
				return;
			}
		}
	}

	public void requestAll() {
		String ip = serverIp;

		if (ip == null) {
			return;
		}

		for (ServerFile f : new ArrayList<>(files)) {
			if (f.state != State.DONE) {
				startDownload(f, ip);
			}
		}
	}

	private void startDownload(ServerFile f, String ip) {
		if (f.url == null) {
			f.state = State.FAILED;
			PluginReadClient.chat("Plugin Read: 无下载地址: " + f.name);
			return;
		}

		if (!busy.add(f.name)) {
			return;
		}

		f.state = State.DOWNLOADING;
		io.execute(() -> download(f, ip));
	}

	private void download(ServerFile f, String ip) {
		Path part = null;

		try {
			Path dir = serverDir(ip);
			Files.createDirectories(dir);
			part = dir.resolve(f.name + ".part");
			Path target = dir.resolve(f.name);

			long maxBytes = Math.max(1, ClientConfig.get().maxFileMb) * 1024L * 1024L;

			HttpRequest request = HttpRequest.newBuilder(URI.create(f.url))
					.timeout(Duration.ofSeconds(60))
					.header("User-Agent", "PluginRead/1.0 (Minecraft client mod)")
					.GET()
					.build();

			HttpResponse<java.io.InputStream> response = HTTP().send(request, HttpResponse.BodyHandlers.ofInputStream());

			if (response.statusCode() / 100 != 200) {
				throw new IOException("HTTP " + response.statusCode());
			}

			f.downloaded = 0;

			try (java.io.InputStream in = response.body();
					java.io.OutputStream out = Files.newOutputStream(part,
							StandardOpenOption.CREATE,
							StandardOpenOption.WRITE,
							StandardOpenOption.TRUNCATE_EXISTING)) {
				byte[] buffer = new byte[8192];
				long total = 0;
				int n;

				while ((n = in.read(buffer)) > 0) {
					out.write(buffer, 0, n);
					total += n;
					f.downloaded = total;

					if (total > maxBytes) {
						throw new IOException("文件大小超过上限 " + ClientConfig.get().maxFileMb + " MB");
					}
				}
			}

			long actual = Files.size(part);

			if (actual > maxBytes) {
				throw new IOException("文件大小 " + JarMetadata.formatSize(actual) + " 超过上限 " + ClientConfig.get().maxFileMb + " MB");
			}

			if (f.sha256 != null && f.sha256.length() == 64) {
				String hex = ManifestSource.sha256Hex(part);

				if (!hex.equalsIgnoreCase(f.sha256)) {
					throw new IOException("SHA-256 校验失败");
				}
			}

			Files.move(part, target, StandardCopyOption.REPLACE_EXISTING);
			part = null;

			f.meta = JarMetadata.readJar(target);
			f.state = State.DONE;
			f.downloaded = actual;
			PluginReadClient.runOnClient(() -> PluginReadClient.chat(
					"Plugin Read: 已下载 " + f.name + "（" + JarMetadata.formatSize(actual) + "）→ pluginread/" + sanitize(ip) + "/"));
		} catch (Exception e) {
			LOGGER.warn("Plugin Read: 下载失败: {}", f.name, e);
			f.state = State.FAILED;
			f.downloaded = -1;
			PluginReadClient.runOnClient(() -> PluginReadClient.chat("Plugin Read: 下载失败 " + f.name + "（" + e.getMessage() + "）"));
		} finally {
			if (part != null) {
				try {
					Files.deleteIfExists(part);
				} catch (Exception ignored) {
				}
			}

			busy.remove(f.name);
		}
	}

	public void openFolder() {
		try {
			Path dir = serverDir(serverIp == null ? "unknown-server" : serverIp);
			Files.createDirectories(dir);

			if (java.awt.Desktop.isDesktopSupported()) {
				java.awt.Desktop.getDesktop().open(dir.toFile());
			} else {
				Runtime.getRuntime().exec(new String[] { "explorer", dir.toAbsolutePath().toString() });
			}
		} catch (Exception e) {
			LOGGER.warn("Plugin Read: 打开文件夹失败", e);
		}
	}

	public static Path serverDir(String ip) {
		return net.fabricmc.loader.api.FabricLoader.getInstance().getGameDir()
				.resolve("pluginread")
				.resolve(sanitize(ip));
	}

	public static String sanitize(String s) {
		StringBuilder sb = new StringBuilder(s.length());

		for (char c : s.toCharArray()) {
			sb.append(Character.isLetterOrDigit(c) || c == '.' || c == '-' || c == '_' ? c : '_');
		}

		return sb.length() == 0 ? "unknown-server" : sb.toString();
	}

	private static java.net.http.HttpClient HTTP() {
		return ManifestSource.HTTP;
	}

	public static byte[] readAllUtf8(Path path) throws IOException {
		return Files.readString(path, StandardCharsets.UTF_8).getBytes(StandardCharsets.UTF_8);
	}
}
