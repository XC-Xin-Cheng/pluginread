package dev.pluginread.client;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * 从 jar 中解析模组 / 插件元数据（fabric.mod.json、plugin.yml、mods.toml）。
 */
public record JarMetadata(String type, String id, String name, String version, String environment, String description) {
	public static JarMetadata readJar(Path jar) {
		String fileName = jar.getFileName() != null ? jar.getFileName().toString() : jar.toString();

		try (InputStream in = Files.newInputStream(jar); ZipInputStream zip = new ZipInputStream(in, StandardCharsets.UTF_8)) {
			ZipEntry entry;

			while ((entry = zip.getNextEntry()) != null) {
				String n = entry.getName();

				if (n.equals("fabric.mod.json")) {
					JsonObject obj = JsonParser.parseString(readCapped(zip, 256 * 1024)).getAsJsonObject();
					String id = str(obj, "id");
					return new JarMetadata("fabric", id,
							obj.has("name") && !obj.get("name").isJsonNull() ? str(obj, "name") : id,
							str(obj, "version"), obj.has("environment") ? str(obj, "environment") : "*",
							str(obj, "description"));
				}

				if (n.equals("plugin.yml") || n.equals("paper-plugin.yml")) {
					return parsePluginYml(readCapped(zip, 256 * 1024), fileName);
				}

				if (n.equals("META-INF/neoforge.mods.toml") || n.equals("META-INF/mods.toml")) {
					return parseModsToml(readCapped(zip, 256 * 1024), fileName);
				}
			}
		} catch (Exception ignored) {
		}

		return new JarMetadata("unknown", null, fileName, null, null, null);
	}

	private static JarMetadata parsePluginYml(String content, String fileName) {
		String name = null;
		String version = null;

		for (String line : content.split("\r?\n")) {
			if (name == null && line.matches("^name:\\s*.+$")) {
				name = clean(line.substring(line.indexOf(':') + 1));
			} else if (version == null && line.matches("^version:\\s*.+$")) {
				version = clean(line.substring(line.indexOf(':') + 1));
			}
		}

		return new JarMetadata("plugin", name, name != null ? name : fileName, version, "server", null);
	}

	private static JarMetadata parseModsToml(String content, String fileName) {
		String modId = null;
		String displayName = null;
		String version = null;

		for (String raw : content.split("\r?\n")) {
			String line = raw.trim();

			if (modId == null && line.matches("modId\\s*=.*")) {
				modId = clean(line.substring(line.indexOf('=') + 1));
			} else if (displayName == null && line.matches("displayName\\s*=.*")) {
				displayName = clean(line.substring(line.indexOf('=') + 1));
			} else if (version == null && line.matches("version\\s*=.*")) {
				version = clean(line.substring(line.indexOf('=') + 1));
			}
		}

		if (modId == null) {
			return new JarMetadata("unknown", null, fileName, null, null, null);
		}

		return new JarMetadata("forge", modId, displayName != null ? displayName : modId, version, "*", null);
	}

	private static String clean(String v) {
		String s = v.trim();

		if (s.length() >= 2 && (s.startsWith("\"") && s.endsWith("\"") || s.startsWith("'") && s.endsWith("'"))) {
			s = s.substring(1, s.length() - 1);
		}

		return s.isEmpty() ? null : s;
	}

	private static String str(JsonObject obj, String key) {
		return obj.has(key) && !obj.get(key).isJsonNull() ? obj.get(key).getAsString() : null;
	}

	private static String readCapped(InputStream in, int cap) throws IOException {
		byte[] buffer = new byte[8192];
		int read = 0;
		int n;

		while (read < cap && (n = in.read(buffer, 0, Math.min(buffer.length, cap - read))) > 0) {
			read += n;
		}

		return new String(buffer, 0, read, StandardCharsets.UTF_8);
	}

	public String typeLabel() {
		return switch (type == null ? "unknown" : type) {
			case "fabric" -> "[Mod] ";
			case "forge" -> "[Mod(F)] ";
			case "plugin" -> "[插件] ";
			default -> "[文件] ";
		};
	}

	public String shortInfo() {
		List<String> parts = new ArrayList<>();

		if (name != null) {
			parts.add(name);
		}

		if (version != null) {
			parts.add("v" + version);
		}

		if (parts.isEmpty()) {
			return "";
		}

		return parts.get(0) + (parts.size() > 1 ? " " + parts.get(1) : "");
	}

	public boolean isFabricClientUsable() {
		return "fabric".equals(type) && (environment == null || environment.contains("*") || environment.contains("client"));
	}

	public static String formatSize(long bytes) {
		if (bytes < 0) {
			return "?";
		}

		if (bytes < 1024) {
			return bytes + " B";
		}

		if (bytes < 1024 * 1024) {
			return String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0);
		}

		return String.format(Locale.ROOT, "%.1f MB", bytes / 1024.0 / 1024.0);
	}
}
