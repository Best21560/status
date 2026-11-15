package com.orv.window;

/** Open/close animation state of one window. progress: 0 = collapsed, 1 = fully open. */
public class WindowAnim {
	public static final float OPEN_SECONDS = 0.45f;
	public static final float CLOSE_SECONDS = 0.28f;

	public boolean open = false;
	public float progress = 0f;
	private long last = System.nanoTime();

	public void set(boolean value) {
		if (this.open != value) {
			this.open = value;
			this.last = System.nanoTime();
		}
	}

	public void reset() {
		this.open = false;
		this.progress = 0f;
		this.last = System.nanoTime();
	}

	public boolean visible() {
		return open || progress > 0f;
	}

	/** Advance the animation using real time; call once per rendered frame. */
	public float update() {
		long now = System.nanoTime();
		float dt = (now - last) / 1_000_000_000f;
		last = now;
		if (dt > 0.1f) {
			dt = 0.1f;
		}
		if (open) {
			progress += dt / OPEN_SECONDS;
		} else {
			progress -= dt / CLOSE_SECONDS;
		}
		if (progress > 1f) {
			progress = 1f;
		}
		if (progress < 0f) {
			progress = 0f;
		}
		return progress;
	}
}
