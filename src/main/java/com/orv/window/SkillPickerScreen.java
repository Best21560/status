package com.orv.window;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * List of passives with tabs by category, search and scroll.
 * Op tool: click a row to add/remove the passive for free.
 */
public class SkillPickerScreen extends Screen {
	private static final int ROW_H = 16;
	private static final int COL_W = 190;

	private final Screen parent;
	private int tab = 0; // 0 = all, 1.. = Passives.CATEGORIES.get(tab - 1)
	private int scroll = 0;
	private String query = "";
	private final List<ButtonWidget> tabButtons = new ArrayList<>();
	private List<Passives.Def> shown = new ArrayList<>();
	private TextFieldWidget search;

	private int listLeft;
	private int listTop;
	private int listBottom;
	private int visibleRows;

	public SkillPickerScreen(Screen parent) {
		super(Text.translatable("orvwindow.picker.title"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		tabButtons.clear();
		int tabCount = Passives.CATEGORIES.size() + 1;
		int perRow = 6;
		int gap = 3;
		int tw = Math.min(110, (this.width - 20 - gap * (perRow - 1)) / perRow);
		int totalW = tw * perRow + gap * (perRow - 1);
		int left = (this.width - totalW) / 2;
		for (int i = 0; i < tabCount; i++) {
			final int idx = i;
			int x = left + (i % perRow) * (tw + gap);
			int y = 30 + (i / perRow) * 22;
			ButtonWidget b = ButtonWidget.builder(tabLabel(i), btn -> {
				tab = idx;
				scroll = 0;
				refilter();
				refreshTabs();
			}).dimensions(x, y, tw, 18).build();
			tabButtons.add(b);
			this.addDrawableChild(b);
		}

		search = new TextFieldWidget(this.textRenderer, this.width / 2 - 100, 78, 200, 16, Text.empty());
		search.setMaxLength(32);
		search.setSuggestion(Text.translatable("orvwindow.picker.search").getString());
		search.setText(query);
		search.setChangedListener(s -> {
			query = s;
			search.setSuggestion(s.isEmpty() ? Text.translatable("orvwindow.picker.search").getString() : "");
			scroll = 0;
			refilter();
		});
		this.addDrawableChild(search);

		listLeft = (this.width - COL_W * 2) / 2;
		listTop = 100;
		listBottom = this.height - 22;
		visibleRows = Math.max(1, (listBottom - listTop) / ROW_H);
		refilter();
		refreshTabs();
	}

	private Text tabLabel(int i) {
		String key = i == 0 ? "all" : Passives.CATEGORIES.get(i - 1);
		Text t = Text.translatable("orvwindow.cat." + key);
		return i == tab ? t.copy().formatted(Formatting.AQUA) : t;
	}

	private void refreshTabs() {
		for (int i = 0; i < tabButtons.size(); i++) {
			tabButtons.get(i).setMessage(tabLabel(i));
		}
	}

	private void refilter() {
		String q = query.trim().toLowerCase(Locale.ROOT);
		shown = new ArrayList<>();
		for (Passives.Def d : Passives.ALL) {
			if (q.isEmpty()) {
				if (tab != 0 && !d.category().equals(Passives.CATEGORIES.get(tab - 1))) {
					continue;
				}
			} else if (!d.name().toLowerCase(Locale.ROOT).contains(q) && !d.id().contains(q)) {
				continue;
			}
			shown.add(d);
		}
		clampScroll();
	}

	private int maxScroll() {
		int rows = (shown.size() + 1) / 2;
		return Math.max(0, rows - visibleRows);
	}

	private void clampScroll() {
		scroll = Math.max(0, Math.min(scroll, maxScroll()));
	}

	/** Index into `shown` under the mouse, or -1. */
	private int indexAt(double mx, double my) {
		if (my < listTop || my >= listTop + visibleRows * ROW_H) {
			return -1;
		}
		int col = (int) ((mx - listLeft) / COL_W);
		if (mx < listLeft || col < 0 || col > 1) {
			return -1;
		}
		int row = (int) ((my - listTop) / ROW_H);
		int idx = (scroll + row) * 2 + col;
		return idx >= 0 && idx < shown.size() ? idx : -1;
	}

	@Override
	public boolean mouseScrolled(double mx, double my, double amount) {
		scroll -= (int) Math.signum(amount);
		clampScroll();
		return true;
	}

	@Override
	public boolean mouseClicked(double mx, double my, int button) {
		if (button == 0) {
			int idx = indexAt(mx, my);
			if (idx >= 0) {
				Passives.Def d = shown.get(idx);
				boolean owned = OrvWindowClient.mySkills.contains(d.id());
				OrvWindowClient.sendGrant(d.id(), !owned);
				return true;
			}
		}
		return super.mouseClicked(mx, my, button);
	}

	@Override
	public void close() {
		this.client.setScreen(parent);
	}

	@Override
	public boolean shouldPause() {
		return false;
	}

	@Override
	public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
		this.renderBackground(ctx);
		ctx.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, 8, 0xFFFFFFFF);
		ctx.drawCenteredTextWithShadow(this.textRenderer,
				Text.translatable("orvwindow.picker.hint"),
				this.width / 2, 19, 0xFF9FB4C8);
		super.render(ctx, mouseX, mouseY, delta);

		int hover = indexAt(mouseX, mouseY);
		for (int r = 0; r < visibleRows; r++) {
			for (int c = 0; c < 2; c++) {
				int idx = (scroll + r) * 2 + c;
				if (idx >= shown.size()) {
					break;
				}
				Passives.Def d = shown.get(idx);
				boolean owned = OrvWindowClient.mySkills.contains(d.id());
				int x = listLeft + c * COL_W;
				int y = listTop + r * ROW_H;
				int bg = idx == hover ? 0xB0305F8F : (owned ? 0x80204A28 : 0x80000000);
				ctx.fill(x, y, x + COL_W - 4, y + ROW_H - 2, bg);
				ctx.drawTextWithShadow(this.textRenderer, d.name(), x + 5, y + 4, owned ? 0xFF55FF55 : 0xFFFFFFFF);
			}
		}

		ctx.drawTextWithShadow(this.textRenderer, Text.translatable("orvwindow.picker.count", shown.size()),
				listLeft, this.height - 14, 0xFF9FB4C8);


		if (hover >= 0) {
			ctx.drawTooltip(this.textRenderer, Passives.tooltip(shown.get(hover)), mouseX, mouseY);
		}
	}
}
