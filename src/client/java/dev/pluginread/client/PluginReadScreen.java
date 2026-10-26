package dev.pluginread.client;

import java.util.List;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

/**
 * 主界面：服务器转载文件列表 + 服务器运行的模组信息。
 * 26.1 渲染重构后使用 extractRenderState(GuiGraphicsExtractor,...) 绘制。
 */
public class PluginReadScreen extends Screen {
	private static final int ROW_H = 18;

	private final Screen parent;
	private int scroll = 0;

	public PluginReadScreen(Screen parent) {
		super(Component.translatable("gui.pluginread.title"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		int y = this.height - 30;
		this.addRenderableWidget(Button.builder(Component.translatable("gui.pluginread.download_all"),
				b -> DownloadManager.get().requestAll()).bounds(8, y, 100, 20).build());
		this.addRenderableWidget(Button.builder(Component.translatable("gui.pluginread.open_folder"),
				b -> DownloadManager.get().openFolder()).bounds(116, y, 100, 20).build());
		this.addRenderableWidget(Button.builder(Component.translatable("gui.pluginread.refresh"),
				b -> PluginReadClient.refresh()).bounds(224, y, 100, 20).build());
		this.addRenderableWidget(Button.builder(Component.translatable("gui.pluginread.close"),
				b -> this.onClose()).bounds(332, y, 100, 20).build());
	}

	private int filesTop() {
		return 42;
	}

	private int visibleRows() {
		int pad = ServerModsParser.last() != null ? 78 : 44;
		return Math.max(1, (this.height - pad - filesTop()) / ROW_H);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
		super.extractRenderState(g, mouseX, mouseY, delta);
		g.centeredText(this.font, this.title, this.width / 2, 10, 0xFFFFFF);

		DownloadManager dm = DownloadManager.get();
		String status = dm.statusText().isEmpty() ? Component.translatable("gui.pluginread.click_hint").getString() : dm.statusText();
		g.text(this.font, truncate(g, status), 10, 24, 0xAAAAAA);

		List<DownloadManager.ServerFile> files = dm.files();
		int vis = visibleRows();
		this.scroll = Mth.clamp(this.scroll, 0, Math.max(0, files.size() - vis));

		if (files.isEmpty()) {
			g.centeredText(this.font, Component.translatable("gui.pluginread.empty"), this.width / 2, 60, 0xAAAAAA);
		} else {
			for (int i = 0; i < vis && this.scroll + i < files.size(); i++) {
				int y = filesTop() + i * ROW_H;
				DownloadManager.ServerFile f = files.get(this.scroll + i);

				if (mouseY >= y && mouseY < y + ROW_H && mouseX >= 6 && mouseX <= this.width - 6) {
					g.fill(6, y, this.width - 6, y + ROW_H, 0x30FFFFFF);
				}

				String line = (this.scroll + i + 1) + ". "
						+ (f.meta != null ? f.meta.typeLabel() : "")
						+ f.name
						+ (f.size >= 0 ? "  " + JarMetadata.formatSize(f.size) : "")
						+ stateLabel(f);
				g.text(this.font, truncate(g, line), 10, y + 5, stateColor(f.state));
			}
		}

		ServerModsParser.PingInfo info = ServerModsParser.last();
		int modsY = this.height - 74;

		if (info != null) {
			if (info.hasMods()) {
				g.text(this.font, Component.translatable("gui.pluginread.server_mods", info.mods().size()), 10, modsY, 0xAAAAAA);
				g.text(this.font, truncate(g, modsSummary(info)), 20, modsY + 12, 0x888888);
			} else {
				g.text(this.font, Component.translatable("gui.pluginread.server_mods_none"), 10, modsY, 0x666666);
			}
		}
	}

	private String modsSummary(ServerModsParser.PingInfo info) {
		StringBuilder sb = new StringBuilder();
		int shown = 0;

		for (ServerModsParser.ServerMod m : info.mods()) {
			if (shown >= 12) {
				sb.append(" …");
				break;
			}

			if (shown > 0) {
				sb.append(", ");
			}

			sb.append(m.modid());

			if (shown < 6 && m.marker() != null && !"1".equals(m.marker())) {
				sb.append('(').append(m.marker()).append(')');
			}

			shown++;
		}

		return sb.toString();
	}

	private String stateLabel(DownloadManager.ServerFile f) {
		if (f.state == DownloadManager.State.DOWNLOADING && f.downloaded >= 0) {
			if (f.size > 0) {
				return "  [↓" + Math.min(100, f.downloaded * 100 / f.size) + "%]";
			}

			return "  [↓" + JarMetadata.formatSize(f.downloaded) + "]";
		}

		return switch (f.state) {
			case PENDING -> f.url == null ? "  [无直链]" : "";
			case DOWNLOADING -> "  [连接中]";
			case DONE -> "  [完成]";
			case FAILED -> "  [失败，点击重试]";
		};
	}

	private int stateColor(DownloadManager.State state) {
		return switch (state) {
			case DONE -> 0x55FF55;
			case FAILED -> 0xFF5555;
			case DOWNLOADING -> 0xFFFF55;
			default -> 0xE0E0E0;
		};
	}

	private String truncate(GuiGraphicsExtractor g, String s) {
		String line = s;
		int guard = 0;

		while (this.font.width(line) > this.width - 24 && line.length() > 4 && guard++ < 400) {
			line = line.substring(0, line.length() - 2) + "…";
		}

		return line;
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		this.scroll = Mth.clamp(this.scroll - (int) Math.signum(scrollY),
				0, Math.max(0, DownloadManager.get().files().size() - visibleRows()));
		return true;
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (event.button() == 0) {
			double mouseX = event.x();
			double mouseY = event.y();
			List<DownloadManager.ServerFile> files = DownloadManager.get().files();

			for (int i = 0; i < visibleRows() && this.scroll + i < files.size(); i++) {
				int y = filesTop() + i * ROW_H;

				if (mouseY >= y && mouseY < y + ROW_H && mouseX >= 6 && mouseX <= this.width - 6) {
					DownloadManager.get().requestFile(files.get(this.scroll + i).name);
					return true;
				}
			}
		}

		return super.mouseClicked(event, doubleClick);
	}

	@Override
	public void onClose() {
		this.minecraft.setScreen(parent);
	}
}
