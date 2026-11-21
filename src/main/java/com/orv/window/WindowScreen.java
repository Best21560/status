package com.orv.window;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import org.lwjgl.glfw.GLFW;

import java.util.List;

/**
 * Invisible screen shown while the floating window is open: it frees the mouse cursor.
 * The cursor is mapped onto the window floating in the world (see StatusRenderer.pick).
 */
public class WindowScreen extends Screen {
	private TextFieldWidget nameField;
	private boolean editing = false;
	private boolean switchingToChild = false;

	public WindowScreen() {
		super(Text.translatable("orvwindow.title"));
	}

	@Override
	public boolean shouldPause() {
		return false;
	}

	@Override
	protected void init() {
		editing = false;
		nameField = new TextFieldWidget(this.textRenderer, this.width / 2 - 100, this.height - 54, 200, 18, Text.empty());
		nameField.setMaxLength(16);
		nameField.setVisible(false);
		this.addDrawableChild(nameField);
	}

	private void startEdit() {
		editing = true;
		nameField.setText(PlayerData.displayName());
		nameField.setVisible(true);
		nameField.setFocused(true);
		this.setFocused(nameField);
		StatusRenderer.setHover(StatusRenderer.Pick.NONE);
	}

	private void stopEdit(boolean save) {
		if (save) {
			PlayerData.name = nameField.getText().trim();
			PlayerData.save();
		}
		editing = false;
		nameField.setVisible(false);
		nameField.setFocused(false);
		this.setFocused(null);
	}

	@Override
	public boolean mouseClicked(double mx, double my, int button) {
		if (editing) {
			return super.mouseClicked(mx, my, button);
		}
		if (button == 0) {
			StatusRenderer.Pick pk = StatusRenderer.pick(mx, my, this.width, this.height);
			switch (pk.kind()) {
				case PLUS -> {
					switchingToChild = true;
					this.client.setScreen(new SkillPickerScreen(this));
					return true;
				}
				case STAT -> {
					if (OrvWindowClient.myStatPoints > 0) {
						OrvWindowClient.sendStatUp(pk.index());
					}
					return true;
				}
				case NAME -> {
					startEdit();
					return true;
				}
				default -> {
				}
			}
		}
		return super.mouseClicked(mx, my, button);
	}

	@Override
	public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
		if (editing) {
			if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
				stopEdit(true);
				return true;
			}
			if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
				stopEdit(false);
				return true;
			}
			return super.keyPressed(keyCode, scanCode, modifiers);
		}
		if (OrvWindowClient.openKey.matchesKey(keyCode, scanCode)) {
			this.close();
			return true;
		}
		return super.keyPressed(keyCode, scanCode, modifiers);
	}

	@Override
	public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
		// no dark background: the world (and the window) stay visible
		StatusRenderer.Pick pk = editing ? StatusRenderer.Pick.NONE : StatusRenderer.pick(mouseX, mouseY, this.width, this.height);
		StatusRenderer.setHover(pk);

		if (!this.client.options.getPerspective().isFirstPerson()) {
			ctx.drawCenteredTextWithShadow(this.textRenderer, Text.translatable("orvwindow.hint.firstperson"),
					this.width / 2, this.height - 30, 0xFFE0E0E0);
		}

		super.render(ctx, mouseX, mouseY, delta);

		if (editing) {
			ctx.drawCenteredTextWithShadow(this.textRenderer, Text.translatable("orvwindow.edit.label"),
					this.width / 2, this.height - 68, 0xFFFFFFFF);
			return;
		}

		switch (pk.kind()) {
			case SKILL -> {
				List<Passives.Def> owned = PlayerData.ownedDefs();
				if (pk.index() >= 0 && pk.index() < owned.size()) {
					Passives.Def d = owned.get(pk.index());
					ctx.drawTooltip(this.textRenderer, Passives.tooltip(d), mouseX, mouseY);
				}
			}
			case NAME -> ctx.drawTooltip(this.textRenderer, List.of(Text.translatable("orvwindow.tip.name")), mouseX, mouseY);
			case PLUS -> ctx.drawTooltip(this.textRenderer, List.of(Text.translatable("orvwindow.tip.plus")), mouseX, mouseY);
			case STAT -> {
				int i = Math.max(0, Math.min(4, pk.index()));
				ctx.drawTooltip(this.textRenderer, List.of(
						Text.translatable("orvwindow.stat.name." + i).formatted(Formatting.AQUA),
						Text.translatable("orvwindow.stat.tip." + i),
						Text.translatable("orvwindow.stat.points", OrvWindowClient.myStatPoints).formatted(Formatting.YELLOW)
				), mouseX, mouseY);
			}
			default -> {
			}
		}
	}

	@Override
	public void removed() {
		if (switchingToChild) {
			switchingToChild = false;
			return;
		}
		StatusRenderer.setHover(StatusRenderer.Pick.NONE);
		OrvWindowClient.closeWindow();
	}
}
