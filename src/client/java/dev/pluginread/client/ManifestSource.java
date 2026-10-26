package dev.pluginread.client;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.io.InputStream;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * 清单来源：按配置 URL + 自动探测路径获取服务器转载文件的 manifest。
 */
public final class ManifestSource {
	public static final HttpClient HTTP = HttpClient.newBuilder()
			.followRedirects(HttpClient.Redirect.NORMAL)
			.connectTimeout(Duration.ofSeconds(10))
			.build();

	public record FetchResult(List<DownloadManager.ServerFile> files, String url) {
	}

	private static final Map<String, Cached> CACHE = new HashMap<>();

	private record Cached(List<DownloadManager.ServerFile> files, String url) {
	}

	private ManifestSource() {
	}

	public static List<String> candidateUrls(String ip) {
		ClientConfig cfg = ClientConfig.get();
		List<String> urls = new ArrayList<>();

		ClientConfig.ServerEntry entry = cfg.servers.get(ip);

		if (entry != null && entry.manifestUrls != null) {
			urls.addAll(entry.manifestUrls);
		}

		ClientConfig.ServerEntry wildcard = cfg.servers.get("*");

		if (wildcard != null && wildcard.manifestUrls != null) {
			urls.addAll(wildcard.manifestUrls);
		}

		if (cfg.autoProbe) {
			String host = stripPort(ip);

			urls.add("http://" + host + "/pluginread/manifest.json");
			urls.add("https://" + host + "/pluginread/manifest.json");
			urls.add("http://" + host + "/manifest.json");
		}

		return urls;
	}

	public static String stripPort(String ip) {
		if (ip == null || ip.isEmpty()) {
			return "";
		}

		int idx = ip.lastIndexOf(':');

		if (idx > 0 && ip.indexOf(':') == idx) {
			String port = ip.substring(idx + 1);

			if (port.chars().allMatch(Character::isDigit)) {
				return ip.substring(0, idx);
			}
		}

		return ip;
	}

	public static boolean shouldProbe(String ip) {
		ClientConfig cfg = ClientConfig.get();
		ClientConfig.ServerEntry entry = cfg.servers.get(ip);

		if (entry != null && entry.manifestUrls != null && !entry.manifestUrls.isEmpty()) {
			return true;
		}

		return cfg.autoProbe;
	}

	/** 在 IO 线程调用：依次尝试候选 URL，返回首个成功解析的清单。 */
	public static FetchResult fetch(String ip) {
		for (String url : candidateUrls(ip)) {
			try {
				HttpRequest request = HttpRequest.newBuilder(URI.create(url))
						.timeout(Duration.ofMillis(Math.max(1500, ClientConfig.get().probeTimeoutMs)))
						.header("User-Agent", "PluginRead/1.0 (Minecraft client mod)")
						.GET()
						.build();

				HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());

				if (response.statusCode() / 100 != 200) {
					continue;
				}

				List<DownloadManager.ServerFile> files = parseManifest(response.body(), url);

				if (files == null) {
					continue;
				}

				synchronized (CACHE) {
					CACHE.put(ip, new Cached(files, url));
				}

				return new FetchResult(files, url);
			} catch (Exception ignored) {
			}
		}

		return null;
	}

	/** 解析 manifest：接受数组或 {"files": [...]} / {"mods": [...]}。 */
	public static List<DownloadManager.ServerFile> parseManifest(String body, String manifestUrl) {
		try {
			JsonElement root = JsonParser.parseString(body);
			JsonArray arr;

			if (root.isJsonArray()) {
				arr = root.getAsJsonArray();
			} else if (root.isJsonObject()) {
				JsonObject obj = root.getAsJsonObject();

				if (obj.has("files") && obj.get("files").isJsonArray()) {
					arr = obj.getAsJsonArray("files");
				} else if (obj.has("mods") && obj.get("mods").isJsonArray()) {
					arr = obj.getAsJsonArray("mods");
				} else {
					return null;
				}
			} else {
				return null;
			}

			if (arr.size() > 256) {
				return null;
			}

			List<DownloadManager.ServerFile> files = new ArrayList<>();

			for (JsonElement el : arr) {
				if (!el.isJsonObject()) {
					continue;
				}

				JsonObject obj = el.getAsJsonObject();

				if (!obj.has("name") || !obj.get("name").isJsonPrimitive()) {
					continue;
				}

				String name = obj.get("name").getAsString();

				if (name.isBlank() || name.contains("/") || name.contains("\\") || name.contains("..")) {
					continue;
				}

				String url = obj.has("url") && obj.get("url").isJsonPrimitive() ? obj.get("url").getAsString() : null;
				url = resolveUrl(url, manifestUrl, name);

				long size = obj.has("size") && obj.get("size").isJsonPrimitive() ? obj.get("size").getAsLong() : -1L;
				String sha256 = obj.has("sha256") && obj.get("sha256").isJsonPrimitive() ? obj.get("sha256").getAsString() : null;

				files.add(new DownloadManager.ServerFile(name, size, sha256, url));
			}

			return files;
		} catch (Exception e) {
			return null;
		}
	}

	private static String resolveUrl(String url, String manifestUrl, String name) {
		if (url != null && url.contains("://")) {
			return url;
		}

		try {
			URI base = URI.create(manifestUrl);
			String dir = manifestUrl.substring(0, manifestUrl.lastIndexOf('/') + 1);

			if (url == null || url.isBlank()) {
				return dir + name;
			}

			if (url.startsWith("/")) {
				String origin = base.getScheme() + "://" + base.getRawAuthority();
				return origin + url;
			}

			return dir + url;
		} catch (Exception e) {
			return url;
		}
	}

	public static String bytesToHex(byte[] hash) {
		StringBuilder sb = new StringBuilder(hash.length * 2);

		for (byte b : hash) {
			sb.append(String.format("%02x", b));
		}

		return sb.toString();
	}

	public static String sha256Hex(java.nio.file.Path file) throws Exception {
		java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");

		try (InputStream in = java.nio.file.Files.newInputStream(file)) {
			byte[] buffer = new byte[8192];
			int n;

			while ((n = in.read(buffer)) > 0) {
				digest.update(buffer, 0, n);
			}
		}

		return bytesToHex(digest.digest());
	}

}
