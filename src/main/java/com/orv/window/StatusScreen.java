package com.orv.window;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

public class StatusScreen extends Screen {
	private static final int W = 240;
	private static final int H = 150;

	private static final int COLOR_BG = 0xE6061426;
	private static final int COLOR_HEADER = 0xFF0B2A4A;
	private static final int COLOR_LINE = 0xFF4FC3F7;
	private static final int COLOR_LABEL = 0xFF9BD7FF;
	private static final int COLOR_NONE = 0xFFB0B8C0;

	private TextFieldWidget nameField;
	private TextFieldWidget ageField;
	private ButtonWidget genderButton;

	private int left;
	private int top;

	public StatusScreen() {
		super(Text.translatable("orvwindow.title"));
	}

	@Override
	protected void init() {
		left = (this.width - W) / 2;
		top = (this.height - H) / 2;

		nameField = new TextFieldWidget(this.textRenderer, left + 96, top + 32, 132, 18, Text.translatable("orvwindow.label.name"));
		nameField.setMaxLength(16);
		String shown = PlayerData.name;
		if (shown.isEmpty() && this.client != null && this.client.player != null) {
			shown = this.client.player.getName().getString();
		}
		nameField.setText(shown);
		this.addDrawableChild(nameField);

		ageField = new TextFieldWidget(this.textRenderer, left + 96, top + 58, 132, 18, Text.translatable("orvwindow.label.age"));
		ageField.setMaxLength(3);
		ageField.setTextPredicate(s -> s.matches("\\d*"));
		ageField.setText(String.valueOf(PlayerData.age));
		this.addDrawableChild(ageField);

		genderButton = ButtonWidget.builder(genderText(), b -> {
			PlayerData.gender = (PlayerData.gender + 1) % 3;
			b.setMessage(genderText());
		}).dimensions(left + 96, top + 84, 132, 18).build();
		this.addDrawableChild(genderButton);
	}

	private Text genderText() {
		return Text.translatable("orvwindow.gender." + PlayerData.gender);
	}

	@Override
	public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
		this.renderBackground(ctx);

		// panel
		ctx.fill(left, top, left + W, top + H, COLOR_BG);
		ctx.fill(left, top, left + W, top + 22, COLOR_HEADER);
		// border
		ctx.fill(left, top, left + W, top + 1, COLOR_LINE);
		ctx.fill(left, top + H - 1, left + W, top + H, COLOR_LINE);
		ctx.fill(left, top, left + 1, top + H, COLOR_LINE);
		ctx.fill(left + W - 1, top, left + W, top + H, COLOR_LINE);
		// header underline
		ctx.fill(left, top + 22, left + W, top + 23, COLOR_LINE);

		ctx.drawCenteredTextWithShadow(this.textRenderer, this.title, left + W / 2, top + 7, 0xFFFFFFFF);

		ctx.drawTextWithShadow(this.textRenderer, Text.translatable("orvwindow.label.name"), left + 12, top + 37, COLOR_LABEL);
		ctx.drawTextWithShadow(this.textRenderer, Text.translatable("orvwindow.label.age"), left + 12, top + 63, COLOR_LABEL);
		ctx.drawTextWithShadow(this.textRenderer, Text.translatable("orvwindow.label.gender"), left + 12, top + 89, COLOR_LABEL);
		ctx.drawTextWithShadow(this.textRenderer, Text.translatable("orvwindow.label.constellation"), left + 12, top + 115, COLOR_LABEL);
		ctx.drawTextWithShadow(this.textRenderer, Text.translatable("orvwindow.constellation.none"), left + 100, top + 115, COLOR_NONE);

		ctx.drawCenteredTextWithShadow(this.textRenderer, Text.translatable("orvwindow.hint"), left + W / 2, top + H - 14, 0xFF6F8AA6);

		super.render(ctx, mouseX, mouseY, delta);
	}

	@Override
	public void removed() {
		PlayerData.name = nameField.getText().trim();
		try {
			int a = Integer.parseInt(ageField.getText().trim());
			if (a >= 1 && a <= 999) {
				PlayerData.age = a;
			}
		} catch (NumberFormatException ignored) {
		}
		PlayerData.save();
	}
}
