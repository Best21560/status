package com.orv.window;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.LinkedHashMap;
import java.util.Map;

/** Op-only list of passives: click to add (or remove). Hover for the description. */
public class SkillPickerScreen extends Screen {
	private final Map<Passives.Def, ButtonWidget> buttons = new LinkedHashMap<>();

	public SkillPickerScreen() {
		super(Text.translatable("orvwindow.picker.title"));
	}

	@Override
	protected void init() {
		buttons.clear();
		int count = Passives.ALL.size();
		int rows = (count + 1) / 2;
		int gap = 4;
		int bw = 150;
		int rowH = Math.max(14, Math.min(24, (this.height - 56) / rows));
		int bh = rowH - gap;
		int left = (this.width - (bw * 2 + gap)) / 2;
		int top = 34;

		for (int i = 0; i < count; i++) {
			Passives.Def d = Passives.ALL.get(i);
			int x = left + (i % 2) * (bw + gap);
			int y = top + (i / 2) * rowH;
			ButtonWidget btn = ButtonWidget.builder(label(d), b -> {
						boolean has = OrvWindowClient.mySkills.contains(d.id());
						OrvWindowClient.sendGrant(d.id(), !has);
					})
					.dimensions(x, y, bw, bh)
					.tooltip(Tooltip.of(Text.translatable("orvwindow.passive." + d.id())))
					.build();
			buttons.put(d, btn);
			this.addDrawableChild(btn);
		}
	}

	private static Text label(Passives.Def d) {
		boolean has = OrvWindowClient.mySkills.contains(d.id());
		return Text.literal(d.name()).formatted(has ? Formatting.GREEN : Formatting.WHITE);
	}

	@Override
	public void tick() {
		for (Map.Entry<Passives.Def, ButtonWidget> e : buttons.entrySet()) {
			e.getValue().setMessage(label(e.getKey()));
		}
	}

	@Override
	public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
		this.renderBackground(ctx);
		ctx.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, 10, 0xFFFFFFFF);
		ctx.drawCenteredTextWithShadow(this.textRenderer, Text.translatable("orvwindow.picker.hint"), this.width / 2, 22, 0xFF9FB4C8);
		super.render(ctx, mouseX, mouseY, delta);
	}
}
